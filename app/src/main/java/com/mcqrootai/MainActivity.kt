
package com.mcqrootai

import android.app.Activity
import android.content.Context
import android.content.Intent
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

    companion object {
        private const val MODEL_NAME = "gemini-3-flash-preview"
        private const val PREF_NAME = "mcq_settings"
        private const val PREF_API_KEY = "gemini_api_key"
    }

    private lateinit var question: EditText
    private lateinit var result: TextView
    private lateinit var engineStatus: TextView

    private val imagePicker = 4102
    private val screenCaptureRequest = 4104

    private var captureActive = false
    private var isAnalyzing = false

    private val executor = Executors.newSingleThreadExecutor()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val preferences by lazy {
        getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val projectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        question = findViewById(R.id.question)
        result = findViewById(R.id.result)
        engineStatus = findViewById(R.id.engineStatus)

        engineStatus.text = "AI: GEMINI 3 FLASH"

        findViewById<Button>(R.id.loadModel).apply {
            text = "ATUR GEMINI API KEY"
            setOnClickListener {
                showApiKeyDialog()
            }
        }

        findViewById<Button>(R.id.pickImage).setOnClickListener {
            selectImage()
        }

        findViewById<Button>(R.id.analyze).setOnClickListener {
            analyzeManualText()
        }

        findViewById<Button>(R.id.floating).apply {
            text = "BUKA FLOATING WINDOW"
            setOnClickListener {
                openFloatingWindow()
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

    private fun showApiKeyDialog() {
        val keyInput = EditText(this).apply {
            hint = "Masukkan Gemini API key"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD

            setText(
                preferences.getString(PREF_API_KEY, "")
            )
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
                    .putString(PREF_API_KEY, key)
                    .apply()

                engineStatus.text =
                    "GEMINI 3 FLASH: API KEY TERSIMPAN"

                Toast.makeText(
                    this,
                    "API key berhasil disimpan.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun selectImage() {
        val intent = Intent(
            Intent.ACTION_OPEN_DOCUMENT
        ).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, imagePicker)
    }

    private fun requestScreenCapture() {
        if (
            preferences.getString(PREF_API_KEY, "")
                .isNullOrBlank()
        ) {
            showApiKeyDialog()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Izinkan floating window terlebih dahulu.",
                Toast.LENGTH_LONG
            ).show()

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

    private fun openFloatingWindow() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Izin tampil di atas aplikasi lain belum diberikan.",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        try {
            startService(
                Intent(
                    this,
                    FloatingResultService::class.java
                ).putExtra(
                    FloatingResultService.EXTRA_RESULT,
                    "MCQ Root AI siap digunakan.\n\n" +
                    "Aktifkan Screen Assistant, lalu tekan BERI JAWABAN."
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal membuka floating window: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopScreenCapture() {
        startService(
            Intent(
                this,
                ScreenCaptureService::class.java
            ).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
        )

        captureActive = false

        findViewById<Button>(R.id.startCapture).text =
            "AKTIFKAN SCREEN ASSISTANT"

        engineStatus.text = "SCREEN ASSISTANT NONAKTIF"

        Toast.makeText(
            this,
            "Screen Assistant dihentikan.",
            Toast.LENGTH_SHORT
        ).show()
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
                    data?.data?.let {
                        processImage(it)
                    }
                }
            }

            screenCaptureRequest -> {

                if (
                    resultCode == Activity.RESULT_OK &&
                    data != null
                ) {

                    if (!Settings.canDrawOverlays(this)) {
                        Toast.makeText(
                            this,
                            "Izin floating window belum diberikan.",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val serviceIntent = Intent(
                        this,
                        ScreenCaptureService::class.java
                    ).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("projectionData", data)
                    }

                    try {
                        if (Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }

                        captureActive = true

                        findViewById<Button>(R.id.startCapture).text =
                            "NONAKTIFKAN SCREEN ASSISTANT"

                        engineStatus.text =
                            "SCREEN ASSISTANT AKTIF"

                        openFloatingWindow()

                    } catch (e: Exception) {

                        captureActive = false

                        engineStatus.text =
                            "SCREEN ASSISTANT ERROR"

                        Toast.makeText(
                            this,
                            "Gagal mengaktifkan Screen Assistant: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } else {

                    captureActive = false

                    Toast.makeText(
                        this,
                        "Izin Screen Capture dibatalkan.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun processImage(uri: Uri) {
        try {
            result.text = "OCR sedang membaca gambar..."

            val image = InputImage.fromFilePath(
                this,
                uri
            )

            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )
                .process(image)
                .addOnSuccessListener { visionText ->

                    val extractedText =
                        visionText.text.trim()

                    if (extractedText.isEmpty()) {
                        result.text =
                            "Tidak ada teks yang terbaca."
                        return@addOnSuccessListener
                    }

                    question.setText(extractedText)

                    result.text =
                        "Teks gambar berhasil dibaca. " +
                        "Tekan ANALYZE untuk mendapatkan penjelasan."
                }
                .addOnFailureListener { error ->
                    result.text =
                        "OCR gagal: ${error.localizedMessage}"
                }

        } catch (error: Exception) {
            result.text =
                "Gagal membuka gambar: ${error.localizedMessage}"
        }
    }

    private fun analyzeManualText() {
        if (isAnalyzing) {
            Toast.makeText(
                this,
                "Analisis sedang berlangsung.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val text = question.text.toString().trim()

        if (text.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        val apiKey = preferences.getString(
            PREF_API_KEY,
            ""
        ).orEmpty()

        if (apiKey.isBlank()) {
            showApiKeyDialog()
            return
        }

        isAnalyzing = true
        result.text = "Gemini sedang menganalisis soal..."

        findViewById<Button>(R.id.analyze).isEnabled = false

        executor.execute {
            try {
                val prompt = """
                    Jawab soal berikut dalam bahasa Indonesia.

                    Berikan:
                    1. Jawaban paling tepat.
                    2. Huruf pilihan jika tersedia.
                    3. Penjelasan langkah demi langkah.
                    4. Alasan jawaban tersebut benar.

                    Jika informasi soal tidak lengkap,
                    jelaskan bagian yang kurang dan jangan menebak.

                    SOAL:
                    $text
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

                    val responseBody =
                        response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        throw Exception(
                            "Gemini API error ${response.code}: " +
                            responseBody.take(1000)
                        )
                    }

                    val json = JSONObject(responseBody)

                    val partsArray = json
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")

                    val answerBuilder = StringBuilder()

                    if (partsArray != null) {
                        for (i in 0 until partsArray.length()) {
                            val part = partsArray.optJSONObject(i)
                            val partText =
                                part?.optString("text").orEmpty()

                            if (partText.isNotBlank()) {
                                answerBuilder.append(partText)
                                answerBuilder.append("\n")
                            }
                        }
                    }

                    val answer =
                        answerBuilder.toString().trim()

                    if (answer.isBlank()) {
                        throw Exception(
                            "Gemini tidak memberikan jawaban."
                        )
                    }

                    runOnUiThread {
                        result.text = answer
                        isAnalyzing = false

                        findViewById<Button>(
                            R.id.analyze
                        ).isEnabled = true
                    }
                }

            } catch (error: Exception) {

                runOnUiThread {
                    result.text =
                        "Gagal menganalisis soal:\n${error.message}"

                    isAnalyzing = false

                    findViewById<Button>(
                        R.id.analyze
                    ).isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
