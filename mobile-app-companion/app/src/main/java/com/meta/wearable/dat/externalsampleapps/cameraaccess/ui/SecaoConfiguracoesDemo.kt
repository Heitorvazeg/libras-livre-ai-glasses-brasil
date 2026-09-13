/*
 * Libras Livre — seção "Configurações de demo" do menu de debug (docs/prontidao-demo/10-tela.md §10.6).
 *
 * Onda 2: só o que o primeiro teste com os óculos e o fluxo no mock precisam — gravador de sessão
 * (1.9), painel de métricas (3.8) e o modo do classificador placeholder (2.6). A onda 4 completa. O
 * menu só existe no build debug (BuildConfig.DEBUG), que é o APK da demo.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico.ConfiguracoesDemo
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoPlaceholder

@Composable
fun SecaoConfiguracoesDemo(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val configuracoes = remember { ConfiguracoesDemo.de(context) }
  val valores by configuracoes.valores.collectAsStateWithLifecycle()

  Column(
      modifier = modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
          text = stringResource(R.string.demo_config_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
      )
      TextButton(onClick = configuracoes::voltarAoPadrao) { Text(stringResource(R.string.demo_config_reset)) }
    }
    Interruptor(
        rotulo = stringResource(R.string.demo_config_recorder),
        ligado = valores.gravadorSessao,
        tag = "demo_gravador",
        onMudar = { v -> configuracoes.atualizar { it.copy(gravadorSessao = v) } },
    )
    Interruptor(
        rotulo = stringResource(R.string.demo_config_metrics),
        ligado = valores.painelMetricas,
        tag = "demo_painel_metricas",
        onMudar = { v -> configuracoes.atualizar { it.copy(painelMetricas = v) } },
    )
    Text(text = stringResource(R.string.demo_config_placeholder), style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      ModoPlaceholder.entries.forEach { modo ->
        FilterChip(
            selected = valores.modoPlaceholder == modo,
            onClick = { configuracoes.atualizar { it.copy(modoPlaceholder = modo) } },
            label = { Text(modo.name.lowercase()) },
            modifier = Modifier.testTag("demo_placeholder_${modo.name.lowercase()}"),
        )
      }
    }
  }
}

@Composable
private fun Interruptor(rotulo: String, ligado: Boolean, tag: String, onMudar: (Boolean) -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(text = rotulo, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Switch(checked = ligado, onCheckedChange = onMudar, modifier = Modifier.testTag(tag))
  }
}
