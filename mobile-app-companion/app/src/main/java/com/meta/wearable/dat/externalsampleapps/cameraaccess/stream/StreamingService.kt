/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R

object ForegroundServiceNotificationIds {
  const val ACTIVE_RECORDING_NOTIFICATION_ID = 1001
}

/**
 * Foreground service that keeps the camera streaming alive when the screen is locked or the app is
 * in the background.
 *
 * This service:
 * - Displays a persistent notification while streaming
 * - Acquires a partial wake lock to prevent CPU sleep
 * - Allows the streaming and recording to continue when the app is backgrounded
 */
class StreamingService : Service() {

  companion object {
    private const val TAG = "StreamingService"
    private const val CHANNEL_ID = "streaming_channel"
    private const val WAKELOCK_TAG = "CameraAccess::StreamingWakeLock"
    // Safety backstop for a leaked lock; releaseWakeLock() is the primary release.
    private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L
    private const val ACTION_STOP =
        "com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.STOP"

    fun start(context: Context) {
      val intent =
          Intent(context, StreamingService::class.java).apply { `package` = context.packageName }
      context.startForegroundService(intent)
    }

    /**
     * Chamar de novo, com o stream já rodando, quando RECORD_AUDIO acaba de ser concedido em
     * tempo de execução (ver CameraViewModel.onWakeWordButton) — reinvoca onStartCommand, que
     * reavalia a permissão e chama startForeground() de novo com o tipo "microphone" incluído
     * (idempotente: startForeground()/acquireWakeLock() já toleram ser chamados de novo).
     * Sem isso, uma sessão cujo RECORD_AUDIO só foi concedido DEPOIS do stream já ter começado
     * ficaria sem a proteção de foreground service pro mic até a PRÓXIMA vez que o stream
     * reiniciar.
     */
    fun refreshForegroundServiceType(context: Context) = start(context)

    fun stop(context: Context) {
      // Route the stop through onStartCommand (a STOP-action start) rather than stopService(). Once
      // startForegroundService() is called the system requires startForeground() to follow; calling
      // stopService() while that start is still pending tears the service down with the foreground
      // promise unfulfilled, which crashes the app with
      // ForegroundServiceDidNotStartInTimeException.
      // Going through onStartCommand guarantees startForeground() runs before the service stops.
      val intent =
          Intent(context, StreamingService::class.java).apply {
            `package` = context.packageName
            action = ACTION_STOP
          }
      context.startForegroundService(intent)
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  inner class LocalBinder : Binder() {
    fun getService(): StreamingService = this@StreamingService
  }

  private val binder = LocalBinder()

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onCreate() {
    super.onCreate()
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Always enter the foreground first — even for a STOP request that may have raced ahead of a
    // pending start — so the startForegroundService() contract is always satisfied.
    //
    // CONNECTED_DEVICE for the wearable streaming (doesn't require CAMERA permission since the
    // phone's camera isn't used) is always included. MICROPHONE is included only when
    // RECORD_AUDIO is already granted — Libras Livre requests that permission lazily, in
    // context, right before it's first needed (ver
    // MainActivity.requestRecordAudioPermission()) — which is AFTER this service typically
    // already started. Declaring MICROPHONE without the permission granted makes
    // startForeground() throw SecurityException, which would break the core streaming flow for
    // every user on first use. refreshForegroundServiceType() re-enters here once the permission
    // is granted mid-session, upgrading the type without needing to restart the stream.
    val hasRecordAudio =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    val foregroundServiceType =
        if (hasRecordAudio) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
              ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
    try {
      startForeground(
          ForegroundServiceNotificationIds.ACTIVE_RECORDING_NOTIFICATION_ID,
          createNotification(),
          foregroundServiceType,
      )
    } catch (e: Exception) {
      Log.e(TAG, "Failed to enter foreground; stopping service", e)
      stopSelf()
      return START_NOT_STICKY
    }

    if (intent?.action == ACTION_STOP) {
      Log.d(TAG, "Service stopping")
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
      return START_NOT_STICKY
    }

    Log.d(TAG, "Service started")
    acquireWakeLock()
    return START_STICKY
  }

  override fun onDestroy() {
    Log.d(TAG, "Service destroyed")
    releaseWakeLock()
    super.onDestroy()
  }

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    )
        .apply {
          description = getString(R.string.notification_channel_description)
          setShowBadge(false)
        }

    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.createNotificationChannel(channel)
  }

  private fun createNotification(): Notification {
    val pendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
              flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_body))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock =
          powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire(WAKELOCK_TIMEOUT_MS)
          }
      Log.d(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }
}
