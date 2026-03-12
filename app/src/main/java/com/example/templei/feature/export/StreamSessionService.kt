package com.example.templei.feature.export

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.templei.R

/**
 * Foreground-capable service boundary for Screen 2 streaming session commands.
 *
 * TODO: Keep this boundary stable while native MPEG-TS + SRT internals are integrated.
 */
class StreamSessionService : Service() {
    private val tag = "TempleI-StreamSessionService"
    private val binder = LocalBinder()
    private var isForegroundActive = false
    private val sessionLock = Any()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    inner class LocalBinder : Binder() {
        fun startSession(config: ExportFeature.ObsStreamConfig): ExportFeature.StreamResult {
            synchronized(sessionLock) {
                val currentState = ExportFeature.currentState()
                if (currentState == ExportFeature.SessionState.Streaming || currentState == ExportFeature.SessionState.Starting) {
                    return ExportFeature.StreamResult(state = currentState)
                }

                return runCatching {
                    val captureReady = CaptureCoordinator.startCapturePathSession(this@StreamSessionService, config)
                    if (!captureReady.isReady) {
                        return ExportFeature.markFault("capture path not ready: ${captureReady.error.orEmpty()}")
                    }

                    val contractStatus = CaptureCoordinator.contractStatus(config.streamMode)
                    if (!contractStatus.ready) {
                        CaptureCoordinator.stopCapturePathSession(forceStop = true, reason = "capture-contract-failed")
                        return ExportFeature.markFault("capture contract failed: ${contractStatus.reason}")
                    }

                    val needsMicrophone = when (config.streamMode) {
                        CaptureCoordinator.StreamPathMode.AudioOnly,
                        CaptureCoordinator.StreamPathMode.FullAv,
                        -> true

                        CaptureCoordinator.StreamPathMode.ConnectionOnly,
                        CaptureCoordinator.StreamPathMode.VideoOnly,
                        -> false
                    }
                    if (needsMicrophone && !hasRecordAudioPermission()) {
                        CaptureCoordinator.stopCapturePathSession(forceStop = true, reason = "microphone-permission-missing")
                        return ExportFeature.markFault("microphone permission required for selected stream mode")
                    }

                    ensureForegroundNotification(includeMicrophone = needsMicrophone)
                    val streamResult = ExportFeature.startStream(config)
                    if (streamResult.state != ExportFeature.SessionState.Streaming) {
                        // Keep capture/session teardown symmetric when transport start fails.
                        CaptureCoordinator.stopCapturePathSession(forceStop = true, reason = "transport-start-failed")
                        stopForegroundSession()
                    }
                    streamResult
                }.getOrElse { error ->
                    CaptureCoordinator.stopCapturePathSession(forceStop = true, reason = "start-session-exception")
                    stopForegroundSession()
                    ExportFeature.markFault(
                        startSessionFailureMessage(
                            error = error,
                            needsMicrophone = selectedStreamNeedsMicrophone(config.streamMode),
                        ),
                    )
                }
            }
        }

        fun stopSession(): ExportFeature.StreamResult {
            CaptureCoordinator.stopCapturePathSession(forceStop = false, reason = "explicit-stop")
            val result = ExportFeature.stopStream()
            stopForegroundSession()
            return result
        }

        fun currentState(): ExportFeature.SessionState = ExportFeature.currentState()

        fun lastError(): String = ExportFeature.lastError()
    }

    private fun ensureForegroundNotification(includeMicrophone: Boolean) {
        if (isForegroundActive) {
            return
        }

        if (includeMicrophone && !hasRecordAudioPermission()) {
            throw SecurityException("microphone permission required for selected stream mode")
        }

        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.obs_service_notification_title))
            .setContentText(getString(R.string.obs_service_notification_text))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (includeMicrophone) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundActive = true
    }

    private fun startSessionFailureMessage(error: Throwable, needsMicrophone: Boolean): String {
        val message = error.message.orEmpty()
        val isMicrophoneForegroundFailure = needsMicrophone && (
            message.contains("FOREGROUND_SERVICE_MICROPHONE", ignoreCase = true) ||
                message.contains("type microphone", ignoreCase = true) ||
                message.contains("RECORD_AUDIO", ignoreCase = true)
            )

        if (isMicrophoneForegroundFailure) {
            return "microphone foreground permission/start policy blocked this stream start; grant microphone permission and retry, or switch to Video Only mode"
        }

        Log.e(tag, "start session crashed", error)
        return "start session crashed: ${message.ifBlank { error::class.java.simpleName }}"
    }

    private fun selectedStreamNeedsMicrophone(streamMode: CaptureCoordinator.StreamPathMode): Boolean {
        return when (streamMode) {
            CaptureCoordinator.StreamPathMode.AudioOnly,
            CaptureCoordinator.StreamPathMode.FullAv,
            -> true

            CaptureCoordinator.StreamPathMode.ConnectionOnly,
            CaptureCoordinator.StreamPathMode.VideoOnly,
            -> false
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
