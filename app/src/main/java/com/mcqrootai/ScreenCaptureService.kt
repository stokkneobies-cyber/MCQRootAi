
package com.mcqrootai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_STOP =
            "com.mcqrootai.ACTION_STOP_CAPTURE"

        const val ACTION_TEXT_DETECTED =
            "com.mcqrootai.TEXT_DETECTED"

        const val EXTRA_TEXT = "detected_text"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVAL = 3000L
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var processing = false
    private var lastText = ""

    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    private val captureTask = object : Runnable {
        override fun run() {
            captureFrame()
            handler.postDelayed(this, INTERVAL)
        }
    }

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

        if (projection != null) {
            return START_NOT_STICKY
        }

        val notification = createNotification()

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
            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                projectionData
            )

            val metrics = android.util.DisplayMetrics()

            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                    }
                },
                handler
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "MCQRootAI",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                handler
            )

            handler.postDelayed(captureTask, 1000L)

        } catch (error: Exception) {
            stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun captureFrame() {
        if (processing) return

        val image = try {
            imageReader?.acquireLatestImage()
        } catch (error: Exception) {
            null
        } ?: return

        processing = true

        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val rowPadding =
            rowStride - pixelStride * image.width

        val paddedWidth =
            image.width + rowPadding / pixelStride

        val paddedBitmap = android.graphics.Bitmap.createBitmap(
            paddedWidth,
            image.height,
            android.graphics.Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)
        image.close()

        val bitmap = android.graphics.Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            imageReader!!.width,
            imageReader!!.height
        )

        paddedBitmap.recycle()

        recognizer.process(
            InputImage.fromBitmap(bitmap, 0)
        )
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()

                if (text.isNotEmpty()) {
                    val normalized = text.replace(
                        Regex("\\s+"),
                        " "
                    )

                    if (normalized != lastText) {
                        lastText = normalized

                        sendBroadcast(
                            Intent(ACTION_TEXT_DETECTED).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_TEXT, text)
                            }
                        )
                    }
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
                processing = false
            }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            100,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MCQ Root AI")
            .setContentText("Screen Assistant aktif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "NONAKTIFKAN",
                stopPendingIntent
            )
            .build()
    }

    private fun stopCapture() {
        handler.removeCallbacks(captureTask)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MCQ Root AI Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(
                NotificationManager::class.java
            ).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureTask)

        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
