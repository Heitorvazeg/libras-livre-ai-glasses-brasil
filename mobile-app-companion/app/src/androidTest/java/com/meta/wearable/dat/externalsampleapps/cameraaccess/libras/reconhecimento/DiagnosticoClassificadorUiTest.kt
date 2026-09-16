package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CartaoClassificador
import org.junit.Rule
import org.junit.Test

class DiagnosticoClassificadorUiTest {
  @get:Rule val compose = createComposeRule()

  @Test fun modoLimiarEIdentidadeSaoVisiveisSemMetricas() {
    val id = IdentidadeClassificador("baseline-v1", "ab".repeat(32), "cd".repeat(32), "ef".repeat(32), "ausente_nao_calibrado")
    val d = mutableStateOf(DiagnosticoClassificador(ModoClassificador.REAL_EXPERIMENTAL, id))
    val limiar = mutableStateOf(0.6f)
    compose.setContent { MaterialTheme { CartaoClassificador(d.value, limiar.value) } }
    compose.onNodeWithText("REAL EXPERIMENTAL", substring = true).assertIsDisplayed()
    compose.onNodeWithText("limiar manual: 0.6", substring = true).assertIsDisplayed()
    compose.runOnIdle { limiar.value = 0.7f }
    compose.onNodeWithText("limiar manual: 0.7", substring = true).assertIsDisplayed()
    compose.onNodeWithTag("diagnostico-classificador").performClick()
    compose.onNodeWithText(id.checkpointSha256, substring = true).assertExists()
    compose.onNodeWithText("Fechar").performClick()
    compose.runOnIdle { d.value = DiagnosticoClassificador(ModoClassificador.RECUSADO, motivo = "hash divergente") }
    compose.onNodeWithText("RECUSADO", substring = true).assertIsDisplayed()
    compose.onNodeWithText("hash divergente").assertIsDisplayed()
    compose.runOnIdle { d.value = DiagnosticoClassificador(ModoClassificador.SIMULADO) }
    compose.onNodeWithText("não reconhece sinais reais", substring = true).assertIsDisplayed()
  }
}