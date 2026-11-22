
package com.mcqrootai

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FloatingResultService : Service() {

    companion object {
        const val ACTION_UPDATE_RESULT =
            "com.mcqrootai.ACTION_UPDATE_RESULT"

        const val EXTRA_RESULT = "result"

        private const val MODEL_NAME = "gemini-3-flash-preview"
        private const val PREF_NAME = "mcq_settings"
        private const val PREF_API_KEY = "gemini_api_key"
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var floatingView: LinearLayout? = null
    private var resultText: TextView? = null
    private var captureButton: Button? = null

    private var overlayVisible = false
    private var isAnalyzing = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_TEXT_DETECTED) {
                return
            }

            val detectedText = intent.getStringExtra(
                ScreenCaptureService.EXTRA_TEXT
            ).orEmpty()

            showOverlay()

            if (detectedText.isBlank()) {
                updateResult("Tidak ada teks soal yang terbaca. Coba lagi.")
                return
            }

            updateResult("Soal berhasil dibaca.\nGemini sedang menganalisis...")
            analyzeWithGemini(detectedText)
        }
    }

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(
            ScreenCaptureService.ACTION_TEXT_DETECTED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this,
                ocrReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(ocrReceiver, filter)
        }

        createFloatingWindow()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (floatingView == null) {
            createFloatingWindow()
        }

        val updatedResult = intent?.getStringExtra(EXTRA_RESULT)

        if (!updatedResult.isNullOrBlank()) {
            showOverlay()
            updateResult(updatedResult)
        }

        return START_NOT_STICKY
    }

    private fun createFloatingWindow() {
        if (floatingView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(0xFF202124.toInt())
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "MCQ ROOT AI"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(4, 8, 8, 8)
        }

        val closeButton = Button(this).apply {
            text = "TUTUP"
            setOnClickListener {
                stopSelf()
            }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            closeButton,
            LinearLayout.LayoutParams(-2, -2)
        )

        captureButton = Button(this).apply {
            text = "BERI JAWABAN"
            textSize = 14f
            setOnClickListener {
                captureAndAnalyze()
            }
        }

        val scrollView = ScrollView(this)

        resultText = TextView(this).apply {
            text = "Tekan BERI JAWABAN untuk membaca soal di layar."
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 12, 8, 12)
        }

        scrollView.addView(resultText)

        container.addView(header)
        container.addView(
            captureButton,
            LinearLayout.LayoutParams(-1, -2)
        )
        container.addView(
            scrollView,
            LinearLayout.LayoutParams(-1, 280)
        )

        floatingView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 100
        }

        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(
                view: View,
                event: MotionEvent
            ): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x =
                            initialX + (event.rawX - initialTouchX).toInt()

                        params.y =
                            initialY + (event.rawY - initialTouchY).toInt()

                        if (overlayVisible) {
                            windowManager.updateViewLayout(
                                container,
                                params
                            )
                        }
                        return true
                    }
                }
                return false
            }
        })

        showOverlay()
    }

    private fun showOverlay() {
        val view = floatingView ?: return
        if (overlayVisible) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100
            }

            windowManager.addView(view, params)
            overlayVisible = true
        } catch (e: Exception) {
            updateResult("Gagal menampilkan floating window: ${e.message}")
        }
    }

    private fun hideOverlay() {
        val view = floatingView ?: return
        if (!overlayVisible) return

        try {
            windowManager.removeView(view)
            overlayVisible = false
        } catch (_: Exception) {
            overlayVisible = false
        }
    }

    private fun captureAndAnalyze() {
        if (isAnalyzing) {
            updateResult("Tunggu, soal sebelumnya masih dianalisis.")
            return
        }

        updateResult("Menyiapkan pengambilan layar...")

        // Hilangkan overlay agar tidak ikut terbaca OCR.
        hideOverlay()

        mainHandler.postDelayed({
            try {
                val captureIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_CAPTURE
                }

                startService(captureIntent)

            } catch (e: Exception) {
                showOverlay()
                updateResult(
                    "Gagal mengambil layar: ${e.message}"
                )
            }
        }, 500)
    }

    private fun analyzeWithGemini(question: String) {
        if (isAnalyzing) return

        val apiKey = getSharedPreferences(
            PREF_NAME,
            Context.MODE_PRIVATE
        ).getString(PREF_API_KEY, "").orEmpty()

        if (apiKey.isBlank()) {
            updateResult(
                "API key Gemini belum diatur. " +
                "Buka aplikasi utama dan masukkan API key."
            )
            return
        }

        isAnalyzing = true
        captureButton?.isEnabled = false

        executor.execute {
            try {
                val prompt = """
                    Jawab soal pilihan ganda berikut dalam bahasa Indonesia.

                    Berikan:
                    1. Jawaban yang paling tepat.
                    2. Huruf pilihan jawaban jika tersedia.
                    3. Penjelasan langkah demi langkah.
                    4. Alasan singkat mengapa pilihan tersebut benar.

                    Jika teks soal tidak lengkap atau tidak terbaca,
                    jelaskan bagian yang kurang dan jangan menebak.

                    SOAL:
                    $question
                """.trimIndent()

                val parts = JSONArray().put(
                    JSONObject().put("text", prompt)
                )

                val contents = JSONArray().put(
                    JSONObject().put("parts", parts)
                )

                val requestJson = JSONObject()
                    .put("contents", contents)

                val body = requestJson.toString()
                    .toRequestBody(
                        "application/json; charset=utf-8".toMediaType()
                    )

                val url =
                    "https://generativelanguage.googleapis.com/" +
                    "v1beta/models/$MODEL_NAME:generateContent"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw Exception(
                            "Gemini API error ${response.code}: $responseBody"
                        )
                    }

                    val json = JSONObject(responseBody)

                    val answer = json
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()

                    if (answer.isBlank()) {
                        throw Exception(
                            "Gemini tidak memberikan jawaban."
                        )
                    }

                    mainHandler.post {
                        updateResult(answer)
                        isAnalyzing = false
                        captureButton?.isEnabled = true
                    }
                }

            } catch (e: Exception) {
                mainHandler.post {
                    updateResult(
                        "Gagal menganalisis soal:\n${e.message}"
                    )
                    isAnalyzing = false
                    captureButton?.isEnabled = true
                }
            }
        }
    }

    private fun updateResult(text: String) {
        mainHandler.post {
            resultText?.text = text
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(ocrReceiver)
        } catch (_: Exception) {
        }

        try {
            floatingView?.let {
                if (overlayVisible) {
                    windowManager.removeView(it)
                }
            }
        } catch (_: Exception) {
        }

        overlayVisible = false
        floatingView = null

        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
