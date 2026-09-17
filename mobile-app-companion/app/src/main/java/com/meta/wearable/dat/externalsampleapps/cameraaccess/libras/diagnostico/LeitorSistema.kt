/*
 * Libras Livre — leitura do sistema para o painel de métricas (docs/prontidao-demo/03 §3.8, 07 §7.3).
 *
 * Folga e estado térmico (PowerManager), bateria do celular (BatteryManager) e RAM do app
 * (Debug.getPss, em MB). A bateria dos óculos fica de fora: o SDK não a expõe (7.4, a verificar).
 *
 * getPss custa alguns milissegundos: chamar uma vez por segundo, fora da main thread, e só com o
 * painel ou o gravador ligados.
 */
package com.meta.wearable.dat.externalsampleapps.cameraaccess.libras.diagnostico

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager

class LeitorSistema(context: Context) {

  private val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
  private val bateria = context.applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

  fun ler(): LeituraSistema =
      LeituraSistema(
          // Previsão para 10 s; NaN quando o aparelho não informa.
          folgaTermica = power?.getThermalHeadroom(10)?.takeUnless { it.isNaN() },
          estadoTermico = power?.currentThermalStatus,
          bateriaPct = bateria?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 },
          ramAppMb = runCatching { (Debug.getPss() / 1024).toInt() }.getOrNull(),
      )
}
