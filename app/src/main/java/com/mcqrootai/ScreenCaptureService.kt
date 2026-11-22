
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
        private const val CAPTURE_TIMEOUT = 7000L
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

    private val captureTimeoutRunnable = Runnable {
        if (captureRequested) {
            captureRequested = false
            sendText(
                "Pengambilan layar timeout. Silakan coba lagi."
            )
        }
    }

    private val imageListener =
        ImageReader.OnImageAvailableListener { reader ->

            if (!captureRequested || processing) {
                reader.acquireLatestImage()?.close()
                return@OnImageAvailableListener
            }

            val image = reader.acquireLatestImage()
                ?: return@OnImageAvailableListener

            handler.removeCallbacks(captureTimeoutRunnable)

            captureRequested = false
            processing = true

            processFrame(image)
        }

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                finishCapture(false)
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

        when (intent?.action) {

            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE -> {

                if (projection == null) {
                    sendText(
                        "Screen Assistant belum aktif. " +
                        "Aktifkan terlebih dahulu."
                    )

                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                if (processing || captureRequested) {
                    sendText(
                        "Sedang memproses gambar. Tunggu sebentar."
                    )
                    return START_NOT_STICKY
                }

                captureRequested = true

                handler.removeCallbacks(
                    captureTimeoutRunnable
                )

                handler.postDelayed(
                    captureTimeoutRunnable,
                    CAPTURE_TIMEOUT
                )

                return START_NOT_STICKY
            }
        }

        if (projection != null) {
            return START_NOT_STICKY
        }

        val notification = createNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

        } catch (error: Exception) {
            sendText(
                "Gagal menjalankan foreground service: " +
                error.localizedMessage
            )
            stopSelf(startId)
            return START_NOT_STICKY
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
            sendText(
                "Izin screen capture tidak tersedia."
            )
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

            val metrics = android.util.DisplayMetrics()

            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)

            val reader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                3
            )

            imageReader = reader

            reader.setOnImageAvailableListener(
                imageListener,
                handler
            )

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            virtualDisplay = projection?.createVirtualDisplay(
                "MCQRootAI",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )

            if (virtualDisplay == null) {
                sendText(
                    "Gagal membuat virtual display."
                )
                stopCapture()
                return START_NOT_STICKY
            }

        } catch (error: Exception) {
            sendText(
                "Screen capture gagal: ${error.localizedMessage}"
            )
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

            sendText(
                "Gagal membaca frame: ${error.localizedMessage}"
            )

        } finally {
            image.close()
        }

        val capturedBitmap = bitmap

        if (capturedBitmap == null) {
            processing = false
            return
        }

        try {
            recognizer.process(
                InputImage.fromBitmap(capturedBitmap, 0)
            )
                .addOnSuccessListener { visionText ->

                    val text = visionText.text.trim()

                    sendText(
                        if (text.isNotEmpty()) {
                            text
                        } else {
                            "Tidak ada teks yang terbaca di layar."
                        }
                    )
                }
                .addOnFailureListener { error ->

                    sendText(
                        "OCR gagal: ${error.localizedMessage}"
                    )
                }
                .addOnCompleteListener {

                    if (!capturedBitmap.isRecycled) {
                        capturedBitmap.recycle()
                    }

                    processing = false
                }

        } catch (error: Exception) {

            if (!capturedBitmap.isRecycled) {
                capturedBitmap.recycle()
            }

            processing = false

            sendText(
                "OCR gagal: ${error.localizedMessage}"
            )
        }
    }

    private fun sendText(text: String) {
        val broadcast = Intent(
            ACTION_TEXT_DETECTED
        ).apply {
            setPackage(packageName)
            putExtra(EXTRA_TEXT, text)
        }

        sendBroadcast(broadcast)
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

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("MCQ Root AI")
            .setContentText(
                "Screen Assistant aktif. Capture saat diminta."
            )
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

        handler.removeCallbacks(captureTimeoutRunnable)

        captureRequested = false
        processing = false

        val activeProjection = projection
        projection = null

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(
                null,
                null
            )
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            activeProjection?.unregisterCallback(
                projectionCallback
            )
        } catch (_: Exception) {
        }

        if (stopProjection) {
            try {
                activeProjection?.stop()
            } catch (_: Exception) {
            }
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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(
                null,
                null
            )
            imageReader?.close()
        } catch (_: Exception) {
        }

        imageReader = null

        try {
            projection?.unregisterCallback(
                projectionCallback
            )
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        if (processing) {
            processing = false
        }

        try {
            recognizer.close()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
