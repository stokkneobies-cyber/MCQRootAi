
package com.mcqrootai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_STOP =
            "com.mcqrootai.ACTION_STOP_CAPTURE"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MCQ Root AI")
                .setContentText("Sesi capture aktif.")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode =
            intent?.getIntExtra("resultCode", 0) ?: 0

        val projectionData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(
                    "projectionData",
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra("projectionData")
            }

        if (resultCode == 0 || projectionData == null) {
            stopCapture()
            return START_NOT_STICKY
        }

        try {
            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                projectionData
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                    }
                },
                Handler(mainLooper)
            )

        } catch (error: Exception) {
            stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun stopCapture() {
        val activeProjection = projection
        projection = null

        activeProjection?.stop()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
