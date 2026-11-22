
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
    private var lastAnalyzedText = ""

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

            if (detectedText.isEmpty()) return

            val hasOptions = Regex(
                "(?im)^\\s*(A|B|C|D)[.)\\:]\\s+"
            ).findAll(detectedText).count() >= 2

            runOnUiThread {
                question.setText(detectedText)

                if (!hasOptions) {
                    result.text =
                        "Teks terdeteksi. Menunggu soal dan pilihan jawaban."
                    return@runOnUiThread
                }

                if (detectedText == lastAnalyzedText) return@runOnUiThread

                val key = preferences.getString(
                    "gemini_api_key", ""
                )

                if (key.isNullOrBlank()) {
                    result.text =
                        "Soal terdeteksi. Atur Gemini API key terlebih dahulu."
                    return@runOnUiThread
                }

                lastAnalyzedText = detectedText
                analyzeText(detectedText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        question = findViewById(R.id.question)
        result = findViewById(R.id.result)
        engineStatus = findViewById(R.id.engineStatus)

        engineStatus.text = "AI: GEMINI 3 FLASH"

        val screenFilter = IntentFilter(
            ScreenCaptureService.ACTION_TEXT_DETECTED
        )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                screenTextReceiver,
                screenFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenTextReceiver, screenFilter)
        }

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

        findViewById<Button>(R.id.floating).setOnClickListener {
            showFloatingResult()
        }

        findViewById<Button>(R.id.startCapture).apply {
            text = "MULAI SCREEN ASSISTANT"
            setOnClickListener {
                if (captureActive) {
                    stopScreenCapture()
                } else {
                    requestScreenCapture()
                }
            }
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

                Toast.makeText(
                    this,
                    "API key tersimpan di perangkat.",
                    Toast.LENGTH_SHORT
                ).show()

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

        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, screenCaptureRequest)
    }

    private fun stopScreenCapture() {
        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        startService(stopIntent)

        captureActive = false
        lastAnalyzedText = ""

        findViewById<Button>(R.id.startCapture).text =
            "MULAI SCREEN ASSISTANT"

        engineStatus.text = "SCREEN ASSISTANT NONAKTIF"
        result.text = "Screen Assistant telah dihentikan."
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
                    lastAnalyzedText = ""

                    findViewById<Button>(R.id.startCapture).text =
                        "NONAKTIFKAN SCREEN ASSISTANT"

                    engineStatus.text = "SCREEN ASSISTANT AKTIF"

                    Toast.makeText(
                        this,
                        "Screen Assistant aktif.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "Izin Screen Capture dibatalkan.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun processImage(uri: Uri) {
        try {
            result.text = "OCR sedang membaca gambar..."

            val image = InputImage.fromFilePath(this, uri)

            TextRecognition
                .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(image)
                .addOnSuccessListener { visionText ->
                    val extractedText = visionText.text.trim()

                    if (extractedText.isEmpty()) {
                        result.text = "Tidak ada teks yang terbaca."
                        return@addOnSuccessListener
                    }

                    question.setText(extractedText)

                    val key = preferences.getString(
                        "gemini_api_key", ""
                    )

                    if (key.isNullOrBlank()) {
                        result.text =
                            "OCR berhasil. Masukkan Gemini API key untuk analisis."
                        showApiKeyDialog(extractedText)
                    } else {
                        analyzeText(extractedText)
                    }
                }
                .addOnFailureListener { error ->
                    result.text = "OCR gagal: ${error.localizedMessage}"
                }

        } catch (e: Exception) {
            result.text =
                "Gagal membuka gambar: ${e.localizedMessage}"
        }
    }

    private fun analyze() {
        val promptText = question.text.toString().trim()

        if (promptText.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        val key = preferences.getString("gemini_api_key", "")

        if (key.isNullOrBlank()) {
            showApiKeyDialog(promptText)
            return
        }

        analyzeText(promptText)
    }

    private fun analyzeText(text: String) {
        val apiKey = preferences.getString(
            "gemini_api_key", ""
        )?.trim().orEmpty()

        if (apiKey.isEmpty()) {
            showApiKeyDialog(text)
            return
        }

        runOnUiThread {
            result.text = "Gemini 3 Flash sedang menganalisis..."
        }

        ioExecutor.execute {
            val outcome = runCatching {
                val prompt = """
                    Kamu adalah MCQ Root AI, asisten belajar
                    yang membantu memahami soal pilihan ganda.

                    Analisis soal latihan berikut:

                    $text

                    Berikan jawaban dalam bahasa Indonesia:
                    1. Pilihan jawaban yang paling tepat.
                    2. Alasan jawaban tersebut benar.
                    3. Penjelasan singkat mengapa pilihan lain kurang tepat.

                    Jika informasi soal tidak cukup, katakan dengan jujur.
                """.trimIndent()

                val requestJson = JSONObject()
                    .put(
                        "contents",
                        org.json.JSONArray().put(
                            JSONObject().put(
                                "parts",
                                org.json.JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        )
                    )

                val body = requestJson.toString()
                    .toRequestBody(
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
                        val errorMessage = runCatching {
                            JSONObject(responseBody)
                                .getJSONObject("error")
                                .getString("message")
                        }.getOrDefault(responseBody)

                        error("HTTP ${response.code}: $errorMessage")
                    }

                    val json = JSONObject(responseBody)
                    val candidates = json.optJSONArray("candidates")
                        ?: error("Gemini tidak memberikan kandidat jawaban.")

                    if (candidates.length() == 0) {
                        error("Gemini tidak menghasilkan jawaban.")
                    }

                    val parts = candidates
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")

                    val answer = buildString {
                        for (i in 0 until parts.length()) {
                            append(
                                parts.getJSONObject(i)
                                    .optString("text", "")
                            )
                        }
                    }.trim()

                    if (answer.isEmpty()) {
                        error("Jawaban Gemini kosong.")
                    }

                    answer
                }
            }

            runOnUiThread {
                outcome.fold(
                    onSuccess = { answer ->
                        result.text = answer
                        engineStatus.text = "GEMINI 3 FLASH: TERHUBUNG"

                        if (Settings.canDrawOverlays(this)) {
                            startService(
                                Intent(
                                    this,
                                    FloatingResultService::class.java
                                ).putExtra("result", answer)
                            )
                        }
                    },
                    onFailure = { error ->
                        result.text =
                            "Analisis gagal: ${error.localizedMessage}"
                        engineStatus.text = "GEMINI 3 FLASH: ERROR"
                    }
                )
            }
        }
    }

    private fun showFloatingResult() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            startActivity(intent)

            Toast.makeText(
                this,
                "Izinkan tampil di atas aplikasi lain.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        startService(
            Intent(this, FloatingResultService::class.java)
                .putExtra("result", result.text.toString())
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenTextReceiver)
        } catch (_: Exception) {
        }

        ioExecutor.shutdown()
        super.onDestroy()
    }
}
