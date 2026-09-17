package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.DiagnosticoClassificador
import com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.reconhecimento.ModoClassificador

/** Visível sem ligar métricas ou gravação. Toque revela os hashes completos. */
@Composable
fun CartaoClassificador(diagnostico: DiagnosticoClassificador?, limiar: Float) {
  var expandido by rememberSaveable { mutableStateOf(false) }
  Surface(
      modifier = Modifier.fillMaxWidth().testTag("diagnostico-classificador")
          .clickable(role = Role.Button, onClickLabel = "Mostrar ou ocultar identidade completa") { expandido = !expandido },
      shape = MaterialTheme.shapes.small,
      color = if (diagnostico?.modo == ModoClassificador.RECUSADO) MaterialTheme.colorScheme.errorContainer
          else MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Column(Modifier.padding(8.dp)) {
      Text(diagnostico?.titulo ?: "CLASSIFICADOR — verificando", style = MaterialTheme.typography.labelLarge)
      diagnostico?.let {
        Text(it.resumo(limiar), style = MaterialTheme.typography.bodySmall)
      }
    }
  }
  if (expandido && diagnostico != null) {
    AlertDialog(
        onDismissRequest = { expandido = false },
        title = { Text("Identidade do classificador") },
        text = {
          SelectionContainer {
            Text(diagnostico.detalhes(limiar), modifier = Modifier.verticalScroll(rememberScrollState()))
          }
        },
        confirmButton = { TextButton(onClick = { expandido = false }) { Text("Fechar") } },
    )
  }
}