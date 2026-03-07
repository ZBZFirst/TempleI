package com.example.templei.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.example.templei.R

/**
 * Foreground-capable service boundary for Screen 2 streaming session commands.
 *
 * TODO: Keep this boundary stable while native MPEG-TS + SRT internals are integrated.
 */
class StreamSessionService : Service() {
    private val binder = LocalBinder()
    private var isForegroundActive = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    inner class LocalBinder : Binder() {
        fun startSession(config: ExportFeature.ObsStreamConfig): ExportFeature.StreamResult {
            val captureReady = CaptureCoordinator.startVideoPathSession(config)
            if (!captureReady.isReady) {
                return ExportFeature.markFault(captureReady.error.orEmpty())
            }

            ensureForegroundNotification()
            return ExportFeature.startStream(config)
        }

        fun stopSession(): ExportFeature.StreamResult {
            CaptureCoordinator.stopVideoPathSession()
            val result = ExportFeature.stopStream()
            stopForegroundSession()
            return result
        }

        fun currentState(): ExportFeature.SessionState = ExportFeature.currentState()

        fun lastError(): String = ExportFeature.lastError()
    }

    private fun ensureForegroundNotification() {
        if (isForegroundActive) {
            return
        }

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.obs_service_notification_title))
            .setContentText(getString(R.string.obs_service_notification_text))
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        isForegroundActive = true
    }

    private fun stopForegroundSession() {
        if (!isForegroundActive) {
            return
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundActive = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.obs_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "obs_stream_session_channel"
        private const val NOTIFICATION_ID = 2401
    }
}
