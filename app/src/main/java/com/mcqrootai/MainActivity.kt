
package com.mcqrootai

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.media.projection.MediaProjectionManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var question: EditText
    private lateinit var result: TextView
    private lateinit var engineStatus: TextView

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val imagePicker = 4102
    private val screenCaptureRequest = 4104

    private var captureActive = false
    private var waitingForCapture = false
    private var receiverRegistered = false

    private val preferences by lazy {
        getSharedPreferences("mcq_settings", MODE_PRIVATE)
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val modelName = "gemini-3-flash-preview"

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
    }

    private val screenTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenCaptureService.ACTION_TEXT_DETECTED) {
                return
            }

            val detectedText = intent.getStringExtra(
                ScreenCaptureService.EXTRA_TEXT
            )?.trim().orEmpty()

            waitingForCapture = false

            if (detectedText.isEmpty()) {
                updateFloating("Tidak ada teks yang terdeteksi.")
                return
            }

            if (
                detectedText.startsWith("Gagal membaca frame") ||
                detectedText.startsWith("OCR gagal") ||
                detectedText.startsWith("Tidak ada teks") ||
                detectedText.startsWith("Screen capture gagal") ||
                detectedText.startsWith("Screen Assistant belum aktif") ||
                detectedText.startsWith("Sedang memproses")
            ) {
                updateFloating(detectedText)
                return
            }

            question.setText(detectedText)

            val key = preferences.getString("gemini_api_key", "")

            if (key.isNullOrBlank()) {
                updateFloating("API key Gemini belum diatur.")
                showApiKeyDialog(detectedText)
                return
            }

            updateFloating(
                "SOAL TERDETEKSI\n\n$detectedText\n\nGemini sedang menganalisis..."
            )

            analyzeText(detectedText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        question = findViewById(R.id.question)
        result = findViewById(R.id.result)
        engineStatus = findViewById(R.id.engineStatus)

        engineStatus.text = "AI: GEMINI 3 FLASH"

        val filter = IntentFilter(
            ScreenCaptureService.ACTION_TEXT_DETECTED
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                screenTextReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenTextReceiver, filter)
        }

        receiverRegistered = true

        findViewById<Button>(R.id.loadModel).apply {
            text = "ATUR GEMINI API KEY"
            setOnClickListener {
                showApiKeyDialog(null)
            }
        }

        findViewById<Button>(R.id.pickImage).setOnClickListener {
            selectImage()
        }

        findViewById<Button>(R.id.analyze).setOnClickListener {
            analyze()
        }

        findViewById<Button>(R.id.floating).apply {
            text = "BERI JAWABAN"
            setOnClickListener {
                requestOneCapture()
            }
        }

        findViewById<Button>(R.id.startCapture).apply {
            text = "AKTIFKAN SCREEN ASSISTANT"
            setOnClickListener {
                if (captureActive) {
                    stopScreenCapture()
                } else {
                    requestScreenCapture()
                }
            }
        }
    }

    private fun updateFloating(text: String) {
        runOnUiThread {
            result.text = text

            startService(
                Intent(this, FloatingResultService::class.java)
                    .putExtra(
                        FloatingResultService.EXTRA_RESULT,
                        text
                    )
            )
        }
    }

    private fun showApiKeyDialog(textToAnalyze: String?) {
        val keyInput = EditText(this).apply {
            hint = "Masukkan Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(preferences.getString("gemini_api_key", ""))
        }

        AlertDialog.Builder(this)
            .setTitle("Gemini 3 Flash API")
            .setMessage("Masukkan API key Gemini milikmu.")
            .setView(keyInput)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                val key = keyInput.text.toString().trim()

                if (key.isEmpty()) {
                    Toast.makeText(
                        this,
                        "API key belum diisi.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                preferences.edit()
                    .putString("gemini_api_key", key)
                    .apply()

                engineStatus.text = "GEMINI 3 FLASH: API KEY TERSIMPAN"

                if (!textToAnalyze.isNullOrBlank()) {
                    analyzeText(textToAnalyze)
                }
            }
            .show()
    }

    private fun selectImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, imagePicker)
    }

    private fun requestScreenCapture() {
        if (preferences.getString("gemini_api_key", "").isNullOrBlank()) {
            showApiKeyDialog(null)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            updateFloating("Izinkan tampil di atas aplikasi lain terlebih dahulu.")
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        startActivityForResult(
            projectionManager.createScreenCaptureIntent(),
            screenCaptureRequest
        )
    }

    private fun requestOneCapture() {
        if (!captureActive) {
            Toast.makeText(
                this,
                "Aktifkan Screen Assistant terlebih dahulu.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (waitingForCapture) {
            updateFloating("Masih memproses permintaan sebelumnya. Tunggu sebentar.")
            return
        }

        if (preferences.getString("gemini_api_key", "").isNullOrBlank()) {
            updateFloating("API key Gemini belum diatur.")
            showApiKeyDialog(null)
            return
        }

        waitingForCapture = true
        updateFloating("Mengambil layar dan membaca soal...")

        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE
            }
        )
    }

    private fun stopScreenCapture() {
        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
        )

        captureActive = false
        waitingForCapture = false

        findViewById<Button>(R.id.startCapture).text =
            "AKTIFKAN SCREEN ASSISTANT"

        engineStatus.text = "SCREEN ASSISTANT NONAKTIF"
        updateFloating("Screen Assistant telah dihentikan.")
    }

    @Deprecated("Legacy result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            imagePicker -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.data?.let { processImage(it) }
                }
            }

            screenCaptureRequest -> {
                if (resultCode == Activity.RESULT_OK && data != null) {

                    if (!Settings.canDrawOverlays(this)) {
                        updateFloating("Izin floating window belum diberikan.")
                        return
                    }

                    val serviceIntent = Intent(
                        this,
                        ScreenCaptureService::class.java
                    ).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("projectionData", data)
                    }

                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    captureActive = true

                    findViewById<Button>(R.id.startCapture).text =
                        "NONAKTIFKAN SCREEN ASSISTANT"

                    engineStatus.text = "SCREEN ASSISTANT AKTIF"

                    startService(
                        Intent(this, FloatingResultService::class.java)
                            .putExtra(
                                FloatingResultService.EXTRA_RESULT,
                                "Screen Assistant aktif.\n\nTekan BERI JAWABAN untuk menganalisis layar."
                            )
                    )

                } else {
                    captureActive = false
                    updateFloating("Izin Screen Capture dibatalkan.")
                }
            }
        }
    }

    private fun processImage(uri: Uri) {
        try {
            updateFloating("OCR sedang membaca gambar...")

            val image = InputImage.fromFilePath(this, uri)

            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )
                .process(image)
                .addOnSuccessListener { visionText ->
                    val extractedText = visionText.text.trim()

                    if (extractedText.isEmpty()) {
                        updateFloating("Tidak ada teks yang terbaca.")
                        return@addOnSuccessListener
                    }

                    question.setText(extractedText)
                    analyzeText(extractedText)
                }
                .addOnFailureListener { error ->
                    updateFloating(
                        "OCR gagal: ${error.localizedMessage}"
                    )
                }

        } catch (error: Exception) {
            updateFloating(
                "Gagal membuka gambar: ${error.localizedMessage}"
            )
        }
    }

    private fun analyze() {
        val promptText = question.text.toString().trim()

        if (promptText.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        analyzeText(promptText)
    }

    private fun analyzeText(text: String) {
        val apiKey = preferences.getString(
            "gemini_api_key", ""
        )?.trim().orEmpty()

        if (apiKey.isEmpty()) {
            updateFloating("API key Gemini belum diatur.")
            showApiKeyDialog(text)
            return
        }

        updateFloating(
            "SOAL TERDETEKSI\n\n$text\n\nGemini 3 Flash sedang menganalisis..."
        )

        engineStatus.text = "GEMINI 3 FLASH: MENGANALISIS"

        ioExecutor.execute {
            val outcome = runCatching {

                val prompt = """
                    Kamu adalah MCQ Root AI, asisten belajar.
                    Bantu pengguna memahami soal latihan berikut.

                    $text

                    Jawab dalam bahasa Indonesia dengan format:

                    JAWABAN:
                    Sebutkan pilihan yang paling tepat.

                    PENJELASAN:
                    Jelaskan alasan jawaban tersebut benar.

                    PEMBAHASAN:
                    Jelaskan secara singkat pilihan lainnya.

                    Jika soal tidak lengkap atau tidak terbaca,
                    jelaskan informasi apa yang masih diperlukan.
                """.trimIndent()

                val requestJson = JSONObject()
                    .put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        )
                    )

                val body = requestJson.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )

                val request = Request.Builder()
                    .url(
                        "https://generativelanguage.googleapis.com/" +
                            "v1beta/models/$modelName:generateContent"
                    )
                    .addHeader("x-goog-api-key", apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        val message = runCatching {
                            JSONObject(responseBody)
                                .getJSONObject("error")
                                .getString("message")
                        }.getOrDefault(responseBody)

                        error("HTTP ${response.code}: $message")
                    }

                    val candidates = JSONObject(responseBody)
                        .optJSONArray("candidates")
                        ?: error("Gemini tidak memberikan jawaban.")

                    if (candidates.length() == 0) {
                        error("Gemini tidak menghasilkan jawaban.")
                    }

                    val parts = candidates
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")

                    buildString {
                        for (i in 0 until parts.length()) {
                            append(
                                parts.getJSONObject(i)
                                    .optString("text", "")
                            )
                        }
                    }.trim().also {
                        if (it.isEmpty()) error("Jawaban Gemini kosong.")
                    }
                }
            }

            runOnUiThread {
                outcome.fold(
                    onSuccess = { answer ->
                        engineStatus.text = "GEMINI 3 FLASH: TERHUBUNG"
                        updateFloating(
                            "HASIL ANALISIS GEMINI 3 FLASH\n\n$answer"
                        )
                    },
                    onFailure = { error ->
                        engineStatus.text = "GEMINI 3 FLASH: ERROR"
                        updateFloating(
                            "ANALISIS GAGAL\n\n${error.localizedMessage}"
                        )
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(screenTextReceiver)
            } catch (_: Exception) {
            }
        }

        ioExecutor.shutdown()
        super.onDestroy()
    }
}
