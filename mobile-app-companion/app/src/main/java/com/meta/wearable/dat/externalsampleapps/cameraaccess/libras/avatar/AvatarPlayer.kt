/*
 * Libras Livre — avatar VLibras numa WebView (docs/vlibras-webview-plano.md §3, §4.2).
 *
 * Renderiza a glosa em Libras por um avatar 3D. Por dentro é o player oficial
 * (vlibras-player-webjs): Unity compilado para WebAssembly, desenhando por WebGL 2.0 dentro de
 * uma WebView. NADA aqui escreve WebGL — o Unity o faz; a WebView só precisa oferecer o contexto.
 *
 * MEDIÇÕES QUE MOLDARAM ESTA CLASSE (sonda de 2026-09-12, §0.7 do plano):
 *
 *  - O Unity leva 6 a 9 SEGUNDOS para ficar pronto. Por isso CRIAR e MOSTRAR são separados:
 *    [prepare] é chamado quando o atendimento começa e carrega escondido, atrás dos estados
 *    ②③④⑤⑥ da conversa (20 s a 1 min); [play] no ⑦ só torna visível, instantâneo.
 *
 *  - O Unity vive no processo do renderer da WebView (~307 MB PSS), não no heap do app (que ficou
 *    em 3-5 MB de 192 MB). Consequência boa: sem OutOfMemoryError. Consequência ruim: o sistema
 *    pode matar esse processo, e SEM onRenderProcessGone isso derruba o app inteiro.
 *
 *  - Os .unityweb são servidos pelo AssetsPathHandler como text/plain e o UnityLoader carrega
 *    assim mesmo (baixa por XHR como arraybuffer e descomprime sozinho). Não é preciso handler
 *    de MIME nem Content-Encoding.
 *
 *  - Dirigir o Unity por SendMessage direto FUNCIONA mas quebra os callbacks: o Unity chama
 *    globais de JS que só o wrapper vlibras.js define, e sem elas some o gloss:end — justamente
 *    o sinal de que ⑦ acabou. Por isso index.html usa o wrapper, e não SendMessage na mão.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

private const val TAG = "Libras:Avatar"

/** Origem virtual do WebViewAssetLoader. Nunca usar file:// — quebra o loader do WASM. */
private const val BASE = "https://appassets.androidplatform.net/assets/vlibras/"

/**
 * Teto para o Unity ficar pronto. A sonda mediu 6-9 s; o dobro disso dá folga para aparelho lento
 * sem prender o ⑦.
 *
 * Existe porque o player só emite "error" na tradução (que não usamos — quem traduz é o
 * [VLibrasGlosaTranslator]): um Unity que trava na carga não avisa ninguém. Sem este relógio o
 * estado ficaria em CARREGANDO para sempre e CADA resposta pagaria o timeout inteiro de quem
 * espera a animação, em vez de cair na legenda de uma vez.
 */
private const val CARGA_TIMEOUT_MS = 20_000L

/** Estado do avatar, refletido na UI para o operador saber se pode contar com ele. */
enum class AvatarState {
  /** Nada criado. Zero custo de memória. */
  OCIOSO,
  /** WebView criada, Unity carregando (6-9 s). Invisível. */
  CARREGANDO,
  /** Unity pronto, esperando glosa. Invisível até [play]. */
  PRONTO,
  /** Animando uma glosa. */
  ANIMANDO,
  /** Não subiu (sem WebGL, assets ausentes, renderer morto). Quem chama cai no fallback. */
  FALHOU,
}

/**
 * Dono do ciclo de vida da WebView do avatar.
 *
 * A granularidade é o ATENDIMENTO, não o estado da conversa: [prepare] no início, [play] a cada
 * resposta, [release] quando o atendimento encerra. Destruir a cada turno faria a pessoa surda
 * esperar 6-9 s por fala (§4.2 do plano).
 */
class AvatarPlayer(
    private val context: Context,
    private val onState: (AvatarState) -> Unit = {},
    /** Disparado quando a animação da glosa termina — é o gancho para ⑦ -> ①. */
    private val onGlossEnd: () -> Unit = {},
) {

  private var webView: WebView? = null
  private var glosaPendente: String? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val cargaTimeout = Runnable {
    Log.e(TAG, "Unity não ficou pronto em ${CARGA_TIMEOUT_MS}ms — avatar indisponível")
    descartarView()
    state = AvatarState.FALHOU
  }

  var state: AvatarState = AvatarState.OCIOSO
    private set(value) {
      if (field != value) {
        field = value
        Log.i(TAG, "estado -> $value")
        onState(value)
      }
    }

  /** A View a ser anexada pela UI. Null enquanto [prepare] não foi chamado. */
  val view: WebView?
    get() = webView

  /**
   * Cria a WebView e começa a carregar o Unity, INVISÍVEL. Idempotente.
   *
   * Dois chamadores, com intenções diferentes e o mesmo efeito:
   *  - o DialogOrchestrator, no início do atendimento, para que o ⑦ não pague os 6-9 s;
   *  - a UI, quando o operador reabre a tela do avatar depois de a ter fechado.
   *
   * Uma falha anterior NÃO é permanente: reabrir explicitamente é um pedido de nova tentativa
   * (o renderer pode ter morrido por pressão de memória que já passou).
   */
  @SuppressLint("SetJavaScriptEnabled")
  fun prepare() {
    // Uma falha vinda do JS (sem WebGL, vlibras.js ausente) deixa a WebView de pé: sem descartá-la
    // aqui, o `webView != null` abaixo transformaria a retentativa num silêncio — o avatar ficaria
    // FALHOU para sempre até alguém chamar [release].
    if (state == AvatarState.FALHOU) {
      Log.i(TAG, "retentando depois de falha anterior")
      descartarView()
    }
    if (webView != null) return
    Log.i(TAG, "prepare() — carregando o Unity escondido")
    state = AvatarState.CARREGANDO
    mainHandler.postDelayed(cargaTimeout, CARGA_TIMEOUT_MS)

    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    webView = WebView(context).apply {
      settings.javaScriptEnabled = true
      settings.domStorageEnabled = true
      settings.mediaPlaybackRequiresUserGesture = false
      // O avatar é decorativo para o sistema de acessibilidade do Android: quem precisa dele
      // está olhando, não ouvindo o TalkBack.
      importantForAccessibility = WebView.IMPORTANT_FOR_ACCESSIBILITY_NO

      webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            v: WebView,
            req: WebResourceRequest,
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(req.url)

        /**
         * OBRIGATÓRIO. O Unity vive no processo do renderer; quando o sistema o mata sob pressão
         * de memória, sem este retorno true o processo do APP é terminado junto.
         */
        override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
          Log.e(TAG, "renderer morreu (crash=${detail.didCrash()}) — avatar indisponível")
          descartarView()
          state = AvatarState.FALHOU
          return true
        }
      }

      addJavascriptInterface(Bridge(), "AvatarBridge")
      loadUrl(BASE + "index.html")
    }
  }

  /**
   * Anima [glosa]. Se o Unity ainda não terminou de carregar, a glosa fica pendente e toca
   * assim que ficar pronto — quem chama não precisa saber em que ponto da carga estamos.
   */
  fun play(glosa: String) {
    if (glosa.isBlank()) return
    when (state) {
      AvatarState.OCIOSO -> {
        prepare()
        glosaPendente = glosa
      }
      AvatarState.CARREGANDO -> glosaPendente = glosa
      AvatarState.PRONTO, AvatarState.ANIMANDO -> enviar(glosa)
      // Não retentamos sozinhos aqui: uma falha no meio da conversa deve virar legenda na hora,
      // não uma espera de 6-9 s. Reabrir é decisão explícita de quem opera (ver [prepare]).
      AvatarState.FALHOU -> Log.w(TAG, "play() ignorado: avatar falhou, use o fallback")
    }
  }

  private fun enviar(glosa: String) {
    val wv = webView ?: return
    state = AvatarState.ANIMANDO
    // A glosa vem acentuada e com '&' de desambiguação (MARCAR&REGISTRAR). Passar por JSON
    // resolve aspas, barras e acentos de uma vez — concatenar string aqui é bug garantido.
    val literal = org.json.JSONObject().put("g", glosa).toString()
    wv.evaluateJavascript("window.avatarPlay($literal.g);", null)
  }

  /**
   * App em background: congela sem descarregar. Voltar custa ~nada.
   *
   * NÃO chama `avatarStop`: parar a animação aqui mataria o `gloss:end` no meio do caminho, e
   * quem espera o fim do ⑦ ficaria pendurado até o teto de tempo — trocar de app por um segundo
   * não deve custar um turno. Interromper de verdade continua sendo `window.avatarStop`, para
   * quem quiser chamar.
   */
  fun pause() {
    webView?.let {
      it.onPause()
      it.pauseTimers()
    }
  }

  fun resume() {
    webView?.let {
      it.resumeTimers()
      it.onResume()
    }
  }

  /**
   * Devolve os ~300 MB do processo do renderer de uma vez. Chamadores:
   *  - o DialogOrchestrator, quando o atendimento encerra por inatividade;
   *  - a UI, quando o operador fecha a tela do avatar;
   *  - `onTrimMemory`, sob pressão de memória.
   *
   * Sempre volta para OCIOSO — inclusive saindo de FALHOU —, porque a próxima [prepare] é uma
   * tentativa nova e não deve herdar o veredito da anterior.
   */
  fun release() {
    if (webView == null && state == AvatarState.OCIOSO) return
    Log.i(TAG, "release() — destruindo a WebView (estado anterior: $state)")
    descartarView()
    state = AvatarState.OCIOSO
  }

  /**
   * O avatar está utilizável agora, ou consegue ficar sem intervenção? Usado pela UI para decidir
   * entre mostrar o avatar e mostrar a legenda, sem precisar conhecer o enum inteiro.
   */
  val disponivel: Boolean
    get() = state != AvatarState.FALHOU

  private fun descartarView() {
    mainHandler.removeCallbacks(cargaTimeout)
    webView?.let { wv ->
      (wv.parent as? ViewGroup)?.removeView(wv)
      wv.stopLoading()
      wv.destroy()
    }
    webView = null
    glosaPendente = null
  }

  private inner class Bridge {
    @JavascriptInterface
    fun onReady() {
      // Vem da thread do JS; tudo que toca a WebView precisa voltar para a main thread.
      webView?.post {
        mainHandler.removeCallbacks(cargaTimeout)
        state = AvatarState.PRONTO
        glosaPendente?.let { glosaPendente = null; enviar(it) }
      }
    }

    @JavascriptInterface
    fun onGlossEnd() {
      webView?.post {
        if (state == AvatarState.ANIMANDO) state = AvatarState.PRONTO
        onGlossEnd()
      }
    }

    @JavascriptInterface
    fun onError(motivo: String) {
      Log.e(TAG, "erro no player: $motivo")
      webView?.post { state = AvatarState.FALHOU }
    }

    @JavascriptInterface
    fun log(msg: String) = Log.d(TAG, "js: $msg")
  }
}
