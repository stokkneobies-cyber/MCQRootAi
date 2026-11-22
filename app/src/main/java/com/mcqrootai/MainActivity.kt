
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

class MainActivity : AppCompatActivity() {

    private lateinit var question: EditText
    private lateinit var result: TextView
    private lateinit var engineStatus: TextView

    private val imagePicker = 4102
    private val screenCaptureRequest = 4104

    private var captureActive = false

    private val preferences by lazy {
        getSharedPreferences("mcq_settings", Context.MODE_PRIVATE)
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
                preferences.getString("gemini_api_key", "")
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
                    .putString("gemini_api_key", key)
                    .apply()

                engineStatus.text = "GEMINI 3 FLASH: API KEY TERSIMPAN"

                Toast.makeText(
                    this,
                    "API key berhasil disimpan.",
                    Toast.LENGTH_SHORT
                ).show()
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
        if (
            preferences.getString("gemini_api_key", "")
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

        startService(
            Intent(this, FloatingResultService::class.java)
                .putExtra(
                    FloatingResultService.EXTRA_RESULT,
                    "MCQ Root AI siap digunakan.\n\n" +
                        "Aktifkan Screen Assistant, lalu tekan BERI JAWABAN."
                )
        )
    }

    private fun stopScreenCapture() {
        startService(
            Intent(this, ScreenCaptureService::class.java).apply {
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
                    data?.data?.let { processImage(it) }
                }
            }

            screenCaptureRequest -> {
                if (resultCode == Activity.RESULT_OK && data != null) {

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

                        engineStatus.text = "SCREEN ASSISTANT AKTIF"

                        openFloatingWindow()

                    } catch (e: Exception) {
                        captureActive = false
                        engineStatus.text = "SCREEN ASSISTANT ERROR"

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

            val image = InputImage.fromFilePath(this, uri)

            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )
                .process(image)
                .addOnSuccessListener { visionText ->
                    val extractedText = visionText.text.trim()

                    if (extractedText.isEmpty()) {
                        result.text = "Tidak ada teks yang terbaca."
                        return@addOnSuccessListener
                    }

                    question.setText(extractedText)

                    result.text =
                        "Teks gambar berhasil dibaca.\n\n" +
                        "Untuk analisis, gunakan fitur soal manual atau floating window."
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
        val text = question.text.toString().trim()

        if (text.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        Toast.makeText(
            this,
            "Analisis soal manual belum dihubungkan ke layanan floating.",
            Toast.LENGTH_LONG
        ).show()
    }
}
