/*
 * Libras Livre — seção "Configurações de demo" do menu de debug (docs/prontidao-demo/10-tela.md §10.6).
 *
 * Todos os seletores num lugar só, salvos entre execuções, com "voltar ao padrão". O menu só existe no
 * build debug (BuildConfig.DEBUG), que é o APK da demo. Os valores numéricos são aplicados ao
 * confirmar no teclado; um valor inválido (ex.: limiar de saída acima do de entrada) é recusado e o
 * anterior fica.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MicrofoneResposta
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.MotorWakeWord
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.SaidaVoz
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ValoresDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoPlaceholder

@Composable
fun SecaoConfiguracoesDemo(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val configuracoes = remember { ConfiguracoesDemo.de(context) }
  val v by configuracoes.valores.collectAsStateWithLifecycle()
  var aberta by rememberSaveable { mutableStateOf(false) }

  // Aplica uma mudança; devolve false se os valores ficaram inválidos (nada é gravado).
  fun aplicar(mudar: (ValoresDemo) -> ValoresDemo): Boolean = runCatching { configuracoes.atualizar(mudar) }.isSuccess

  Column(
      modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = { aberta = !aberta }, modifier = Modifier.weight(1f).testTag("demo_config_abrir")) {
        Text(
            text = stringResource(R.string.demo_config_title) + if (aberta) " ▴" else " ▾",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
        )
      }
      TextButton(onClick = configuracoes::voltarAoPadrao) { Text(stringResource(R.string.demo_config_reset)) }
    }
    if (!aberta) return@Column

    Column(
        modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      // 1.9, 3.8, 2.6
      Interruptor(stringResource(R.string.demo_config_recorder), v.gravadorSessao, "demo_gravador") { x -> aplicar { it.copy(gravadorSessao = x) } }
      Interruptor(stringResource(R.string.demo_config_metrics), v.painelMetricas, "demo_painel_metricas") { x -> aplicar { it.copy(painelMetricas = x) } }
      Rotulo(stringResource(R.string.demo_config_placeholder))
      Chips(ModoPlaceholder.entries, v.modoPlaceholder, "demo_placeholder") { x -> aplicar { it.copy(modoPlaceholder = x) } }
      CampoNumerico("Limiar de confiança (0–1)", v.limiarConfianca.toString(), "demo_limiar") { t ->
        t.toFloatOrNull()?.takeIf { it in 0f..1f }?.let { x -> aplicar { it.copy(limiarConfianca = x) } } ?: false
      }

      // Toggle de DEBUG: modelo neural de contextualização (sob guarda) no lugar do template.
      // 11,9% de taxa de invenção medida (Guardas.kt) — nunca ligar num atendimento de verdade,
      // só pra coletar dado real de quando erra. Vale a partir do próximo lançamento do app
      // (o glossContextualizer nasce uma vez no CameraViewModel, não é recriado por sessão).
      Interruptor(
          stringResource(R.string.demo_config_modelo_contextualizacao),
          v.modeloContextualizacaoAtivo,
          "demo_modelo_contextualizacao",
      ) { x -> aplicar { it.copy(modeloContextualizacaoAtivo = x) } }

      // 1.8
      Titulo(stringResource(R.string.demo_config_segmentacao))
      val s = v.segmentacao
      CampoNumerico("Limiar de entrada (ombros/s)", s.limiarEntrada.toString(), "seg_entrada") { t ->
        t.toFloatOrNull()?.let { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(limiarEntrada = x)) } } ?: false
      }
      CampoNumerico("Limiar de saída (ombros/s)", s.limiarSaida.toString(), "seg_saida") { t ->
        t.toFloatOrNull()?.let { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(limiarSaida = x)) } } ?: false
      }
      CampoLong("Janela de velocidade (ms)", s.janelaVelocidadeMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(janelaVelocidadeMs = x)) } }
      CampoNumerico("α da média móvel (0–1)", s.alfaSuavizacao.toString(), "seg_alfa") { t ->
        t.toFloatOrNull()?.let { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(alfaSuavizacao = x)) } } ?: false
      }
      CampoLong("Pausa que fecha o sinal (ms)", s.pausaMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(pausaMs = x)) } }
      CampoLong("Teto de oclusão (ms)", s.tetoOclusaoMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(tetoOclusaoMs = x)) } }
      CampoLong("Duração mínima do movimento (ms)", s.duracaoMinimaMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(duracaoMinimaMs = x)) } }
      CampoLong("Duração máxima (ms)", s.duracaoMaximaMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(duracaoMaximaMs = x)) } }
      CampoLong("Margem antes (ms)", s.preRollMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(preRollMs = x)) } }
      CampoLong("Margem depois (ms)", s.posRollMs) { x -> aplicar { it.copy(segmentacao = it.segmentacao.copy(posRollMs = x)) } }

      // 4.3, 5.3
      Titulo(stringResource(R.string.demo_config_tempos))
      CampoLong("Captura sem sinal (ms)", v.tetoCapturaMs) { x -> aplicar { it.copy(tetoCapturaMs = x) } }
      CampoLong("Escuta (ms)", v.tetoEscutaMs) { x -> aplicar { it.copy(tetoEscutaMs = x) } }
      CampoLong("Folga após a fala (ms)", v.folgaAposFalaMs) { x -> aplicar { it.copy(folgaAposFalaMs = x) } }

      // 4.5, 4.6, 5.1, 5.2
      Titulo(stringResource(R.string.demo_config_audio))
      Interruptor(stringResource(R.string.comando_de_voz), v.comandoDeVoz, "demo_comando_de_voz") { x -> aplicar { it.copy(comandoDeVoz = x) } }
      Rotulo("Motor da wake word")
      Chips(MotorWakeWord.entries, v.motorWakeWord, "demo_motor") { x -> aplicar { it.copy(motorWakeWord = x) } }
      Rotulo("Saída de voz")
      Chips(SaidaVoz.entries, v.saidaVoz, "demo_saida") { x -> aplicar { it.copy(saidaVoz = x) } }
      Rotulo("Microfone da resposta")
      Chips(MicrofoneResposta.entries, v.microfoneResposta, "demo_microfone") { x -> aplicar { it.copy(microfoneResposta = x) } }

      // 9.1, 8.1, 9.5
      Titulo(stringResource(R.string.demo_config_avatar))
      CampoLong("Teto da tradução (ms)", v.tetoTraducaoMs) { x -> aplicar { it.copy(tetoTraducaoMs = x) } }
      CampoLong("Teto da animação: base (ms)", v.tetoAnimacaoBaseMs) { x -> aplicar { it.copy(tetoAnimacaoBaseMs = x) } }
      CampoLong("Teto da animação: por sinal (ms)", v.tetoAnimacaoPorSinalMs) { x -> aplicar { it.copy(tetoAnimacaoPorSinalMs = x) } }
      CampoNumerico("Limiar de memória (× threshold)", v.fatorLimiarMemoria.toString(), "demo_memoria") { t ->
        t.toFloatOrNull()?.takeIf { it >= 0f }?.let { x -> aplicar { it.copy(fatorLimiarMemoria = x) } } ?: false
      }
      OutlinedButton(
          onClick = { AcoesDeDemo.simularQuedaDoAvatar?.invoke() },
          enabled = AcoesDeDemo.simularQuedaDoAvatar != null,
          modifier = Modifier.testTag("demo_simular_queda"),
      ) {
        Text(stringResource(R.string.demo_config_simular_queda))
      }
    }
  }
}

@Composable
private fun Titulo(texto: String) {
  Text(text = texto, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Rotulo(texto: String) {
  Text(text = texto, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Interruptor(rotulo: String, ligado: Boolean, tag: String, onMudar: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(text = rotulo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Switch(checked = ligado, onCheckedChange = onMudar, modifier = Modifier.testTag(tag))
  }
}

@Composable
private fun <E : Enum<E>> Chips(opcoes: List<E>, selecionada: E, tagBase: String, onEscolher: (E) -> Unit) {
  Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    opcoes.forEach { opcao ->
      FilterChip(
          selected = opcao == selecionada,
          onClick = { onEscolher(opcao) },
          label = { Text(opcao.name.lowercase()) },
          modifier = Modifier.testTag("${tagBase}_${opcao.name.lowercase()}"),
      )
    }
  }
}

@Composable
private fun CampoLong(rotulo: String, valor: Long, onAplicar: (Long) -> Boolean) {
  CampoNumerico(rotulo, valor.toString(), tag = null) { t -> t.toLongOrNull()?.takeIf { it >= 0 }?.let(onAplicar) ?: false }
}

/** Campo aplicado ao confirmar no teclado; recusado, mostra o aviso e volta ao valor salvo. */
@Composable
private fun CampoNumerico(rotulo: String, valor: String, tag: String?, onAplicar: (String) -> Boolean) {
  var texto by remember(valor) { mutableStateOf(valor) }
  var invalido by remember(valor) { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text = rotulo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    OutlinedTextField(
        value = texto,
        onValueChange = { texto = it; invalido = false },
        singleLine = true,
        isError = invalido,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(onDone = {
              if (!onAplicar(texto.trim().replace(',', '.'))) {
                invalido = true
                texto = valor
              }
            }),
        modifier = Modifier.width(110.dp).let { if (tag != null) it.testTag(tag) else it },
    )
  }
  if (invalido) Text(text = stringResource(R.string.demo_config_valor_invalido), color = MaterialTheme.colorScheme.error)
}
