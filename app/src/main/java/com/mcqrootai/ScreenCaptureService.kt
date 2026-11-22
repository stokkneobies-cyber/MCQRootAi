
package com.mcqrootai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
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

        const val ACTION_CAPTURE =
            "com.mcqrootai.ACTION_CAPTURE_ONCE"

        const val ACTION_TEXT_DETECTED =
            "com.mcqrootai.TEXT_DETECTED"

        const val EXTRA_TEXT = "detected_text"

        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private var captureRequested = false
    private var processing = false

    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    private val imageListener =
        ImageReader.OnImageAvailableListener { reader ->

            if (!captureRequested || processing) {
                val unusedImage = reader.acquireLatestImage()
                unusedImage?.close()
                return@OnImageAvailableListener
            }

            val image = reader.acquireLatestImage()
                ?: return@OnImageAvailableListener

            captureRequested = false
            processing = true

            processFrame(image)
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

        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE -> {
                if (projection != null) {
                    captureRequested = true
                }
                return START_NOT_STICKY
            }
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

            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                3
            )

            imageReader = reader

            reader.setOnImageAvailableListener(
                imageListener,
                handler
            )

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        finishCapture(false)
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
                reader.surface,
                null,
                handler
            )

        } catch (error: Exception) {
            stopCapture()
        }

        return START_NOT_STICKY
    }

    private fun processFrame(image: Image) {
        var bitmap: Bitmap? = null

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            val rowPadding =
                rowStride - pixelStride * image.width

            val paddedWidth =
                image.width + rowPadding / pixelStride

            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)

            bitmap = Bitmap.createBitmap(
                paddedBitmap,
                0,
                0,
                image.width,
                image.height
            )

            paddedBitmap.recycle()

        } catch (error: Exception) {
            sendText("Gagal membaca frame layar: ${error.message}")
            processing = false
        } finally {
            image.close()
        }

        val capturedBitmap = bitmap

        if (capturedBitmap == null) {
            return
        }

        try {
            recognizer.process(
                InputImage.fromBitmap(capturedBitmap, 0)
            )
                .addOnSuccessListener { visionText ->
                    val text = visionText.text.trim()

                    if (text.isNotEmpty()) {
                        sendText(text)
                    } else {
                        sendText("Tidak ada teks yang terbaca di layar.")
                    }
                }
                .addOnFailureListener { error ->
                    sendText("OCR gagal: ${error.localizedMessage}")
                }
                .addOnCompleteListener {
                    capturedBitmap.recycle()
                    processing = false
                }

        } catch (error: Exception) {
            capturedBitmap.recycle()
            processing = false
            sendText("OCR gagal: ${error.localizedMessage}")
        }
    }

    private fun sendText(text: String) {
        sendBroadcast(
            Intent(ACTION_TEXT_DETECTED).apply {
                setPackage(packageName)
                putExtra(EXTRA_TEXT, text)
            }
        )
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
            .setContentText("Screen Assistant aktif. Capture saat diminta.")
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
        finishCapture(true)
    }

    private fun finishCapture(stopProjection: Boolean) {
        captureRequested = false

        val activeProjection = projection
        projection = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        if (stopProjection) {
            activeProjection?.stop()
        }

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
        handler.removeCallbacksAndMessages(null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        projection?.stop()
        projection = null

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
