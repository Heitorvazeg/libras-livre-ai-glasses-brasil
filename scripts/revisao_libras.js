"use strict";
const reviewFields = ["linguistic_status", "reviewer", "confirmed_label", "region", "start", "end", "signer", "notes", "permission_status", "permission_reviewer", "permission_evidence", "permission_scope"];
function validateDecision(decision, item) {
  if (!decision || typeof decision !== "object") throw Error("Avaliação inválida");
  if (!["pendente", "aprovado", "requer_recorte", "rejeitado"].includes(decision.linguistic_status)) throw Error("Decisão inválida");
  if (!["pendente", "documentada", "negada"].includes(decision.permission_status)) throw Error("Situação de permissão inválida");
  const text = key => typeof decision[key] === "string" && decision[key].trim().length > 0;
  if (decision.linguistic_status !== "pendente" && !text("reviewer")) throw Error("Informe o consultor responsável");
  if (decision.linguistic_status === "rejeitado" && !text("notes")) throw Error("Informe o motivo da rejeição");
  if (["aprovado", "requer_recorte"].includes(decision.linguistic_status)) {
    if (item.file_issue || !item.technical || !item.sha256) throw Error("Arquivo indisponível para aprovação");
    if (!text("confirmed_label") || !text("region")) throw Error("Informe rótulo e avaliação regional");
    const a = decision.start, b = decision.end;
    if (typeof a !== "number" || typeof b !== "number" || !Number.isFinite(a) || !Number.isFinite(b) || a < 0 || b <= a || b > item.technical.duration) throw Error("Trecho inválido: use 0 ≤ início < fim ≤ duração");
  }
  if (decision.permission_status === "documentada" && !["permission_reviewer", "permission_evidence", "permission_scope"].every(text)) throw Error("Registre responsável, evidência e escopo da permissão");
  const clean = Object.fromEntries(reviewFields.map(key => [key, decision[key] ?? ""]));
  return {...clean, key: item.key, sha256: item.sha256, training_ready: false, updated_at: new Date().toISOString()};
}
function validateImport(payload, queue) {
  if (!payload || payload.schema_version !== 1 || payload.queue_id !== queue.queue_id || !Array.isArray(payload.decisions)) throw Error("Avaliações pertencem a outra versão da fila");
  const result = Object.create(null);
  for (const decision of payload.decisions) {
    const item = queue.candidates.find(candidate => candidate.key === decision.key);
    if (!item || decision.sha256 !== item.sha256 || Object.hasOwn(result, decision.key)) throw Error("ID, hash ou duplicata inválidos na importação");
    result[item.key] = validateDecision(decision, item);
  }
  return result;
}
if (typeof module !== "undefined") module.exports = {validateDecision, validateImport};
if (typeof document !== "undefined") {
  const queue = JSON.parse(document.getElementById("queue-data").textContent);
  const el = id => document.getElementById(id);
  const storageKey = "libras-consultor:" + queue.queue_id;
  let decisions = Object.create(null), selected = 0, dirty = false;
  function message(text) { el("message").textContent = text; }
  const payload = () => ({schema_version: 1, queue_id: queue.queue_id, audit_sha256: queue.audit_sha256, decisions: Object.values(decisions), training_ready: false});
  function persist() {
    try { localStorage.setItem(storageKey, JSON.stringify(payload())); return true; }
    catch (_) { message("Navegador não permite salvar localmente. Exporte o JSON agora para não perder as avaliações."); return false; }
  }
  try { const stored = localStorage.getItem(storageKey); if (stored) decisions = validateImport(JSON.parse(stored), queue); }
  catch (_) { message("Rascunho local indisponível ou incompatível; importe um backup válido."); }
  function renderNav() {
    el("queue").replaceChildren();
    queue.candidates.forEach((item, index) => {
      const button = document.createElement("button");
      button.textContent = `${item.audit.palavra} · ${item.audit.dur_s}s · ${decisions[item.key]?.linguistic_status || "pendente"}`;
      button.className = index === selected ? "active" : "";
      button.onclick = () => { if (dirty && !confirm("Descartar alterações ainda não salvas neste formulário?")) return; selected = index; render(); };
      el("queue").append(button);
    });
  }
  function render() {
    renderNav();
    const item = queue.candidates[selected];
    if (!item) { el("review").hidden = true; return; }
    dirty = false;
    el("title").textContent = `${selected + 1}/${queue.candidates.length} · ${item.audit.palavra}`;
    el("metadata").textContent = `${item.audit.titulo} | ${item.audit.canal} | ${item.audit.id} | duração da triagem: ${item.audit.dur_s}s; arquivo: ${item.technical?.duration ?? "indisponível"}s`;
    el("alerts").textContent = ["Licença na triagem: " + item.audit.licenca, item.audit.alertas, item.audit.revisao_manual, item.file_issue, item.identical_files.length ? "Bytes idênticos a: " + item.identical_files.join(", ") : ""].filter(Boolean).join(" · ");
    el("video").pause();
    el("video").removeAttribute("src");
    if (item.media_url) el("video").src = item.media_url;
    el("video").load();
    el("source").href = item.source_url;
    const det = item.detection;
    el("detection").hidden = !det;
    if (det) {
      const j = det.janela_util_s;
      el("detection").textContent =
        `Detecção: pessoa em ${det.pose_pct}% dos quadros, mão em ${det.mao_pct}%, duas mãos em ${det.duas_maos_pct}%. `
        + `Maior trecho contínuo com pessoa e mão: ${j[0]}s a ${j[1]}s (${det.cobertura_pct}% do clipe).`
        + (det.alertas && det.alertas.length ? " ⚠ " + det.alertas.join("; ") : "");
      // Pré-preenche o trecho com a janela medida — é sugestão, o consultor ajusta.
      if (!decisions[item.key]) { el("start").value = j[0]; el("end").value = j[1]; }
    }
    const ref = item.reference;
    el("reference-box").hidden = !(ref && ref.imagem);
    if (ref && ref.imagem) {
      el("reference-img").src = ref.imagem;
      const fontes = (ref.referencias || []).map(r => `${r.corpus} (${r.arquivo})`).join(" · ");
      el("reference-note").textContent = fontes ? `Referências: ${fontes}` : "Sem referência nos corpora do projeto — não há com o que comparar.";
    } else if (ref && ref.convergencia === "sem_referencia") {
      el("reference-box").hidden = false;
      el("reference-img").removeAttribute("src");
      el("reference-note").textContent = "Esta palavra não existe em MALTA nem V-LIBRASIL: julgamento inteiramente do consultor.";
    }
    const decision = decisions[item.key] || {linguistic_status: "pendente", permission_status: "pendente", start: 0, end: item.technical?.duration ?? ""};
    reviewFields.forEach(key => { el(key).value = decision[key] ?? ""; });
  }
  el("summary").textContent = `${queue.summary.audit_rows} registros da triagem → ${queue.summary.candidates} candidatos em ${queue.summary.words} palavras; ${queue.summary.excluded} excluídos preservados. ${queue.summary.files_ready} arquivos localizados e sondados.`;
  el("exclusions").textContent = queue.exclusions.map(row => `${row.palavra} · ${row.id}: ${row.alertas || "sem alerta automático"} | ${row.revisao_manual || "sem nota manual"}`).join("\n");
  el("form").oninput = () => { dirty = true; };
  el("form").onsubmit = event => {
    event.preventDefault();
    try {
      const decision = Object.fromEntries(reviewFields.map(key => [key, ["start", "end"].includes(key) ? (el(key).value === "" ? null : Number(el(key).value)) : el(key).value]));
      const item = queue.candidates[selected];
      decisions[item.key] = validateDecision(decision, item);
      dirty = false;
      if (persist()) message("Avaliação salva neste navegador. Exporte o JSON para guardar e compartilhar com segurança.");
      renderNav();
    } catch (error) { message(error.message); }
  };
  for (const bound of ["start", "end"]) el("mark-" + bound).onclick = () => { el(bound).value = el("video").currentTime.toFixed(3); dirty = true; };
  el("export").onclick = () => {
    if (dirty) { message("Salve a avaliação em edição antes de exportar."); return; }
    const url = URL.createObjectURL(new Blob([JSON.stringify(payload(), null, 2)], {type: "application/json"}));
    const link = document.createElement("a"); link.href = url; link.download = `avaliacoes-libras-${queue.queue_id.slice(0, 12)}.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    message("Exportação solicitada. Confira o arquivo na pasta de downloads do navegador.");
  };
  el("import").onchange = async event => {
    try {
      const file = event.target.files[0]; if (!file) return;
      if (file.size > 2000000) throw Error("Arquivo de avaliações grande demais");
      const imported = validateImport(JSON.parse(await file.text()), queue);
      if (!confirm("Substituir as avaliações locais pelas do arquivo importado?")) return;
      decisions = imported; if (persist()) message("Avaliações importadas. Nenhuma aprovação para treino foi gerada."); render();
    } catch (error) { message(error.message); }
    finally { event.target.value = ""; }
  };
  window.addEventListener("beforeunload", event => { if (dirty) { event.preventDefault(); event.returnValue = ""; } });
  render();
}