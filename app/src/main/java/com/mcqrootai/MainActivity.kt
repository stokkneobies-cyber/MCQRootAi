
package com.mcqrootai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.media.projection.MediaProjectionManager
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var question: EditText
    private lateinit var result: TextView
    private lateinit var engineStatus: TextView
    private lateinit var localAI: LocalAIEngine

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val imagePicker = 4102
    private val modelPicker = 4103
    private val screenCaptureRequest = 4104

    private var modelReady = false

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

        localAI = LocalAIEngine(this)

        checkRootAccess()

        findViewById<Button>(R.id.loadModel).setOnClickListener {
            selectModel()
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

        findViewById<Button>(R.id.startCapture).setOnClickListener {
            requestScreenCapture()
        }
    }

    private fun checkRootAccess() {
        engineStatus.text = "KERNELSU: MEMERIKSA..."

        ioExecutor.execute {
            val hasRoot = try {
                val process = ProcessBuilder(
                    "su", "-c", "id"
                ).redirectErrorStream(true).start()

                val output = process.inputStream
                    .bufferedReader().use { it.readText() }

                process.waitFor() == 0 && output.contains("uid=0")
            } catch (e: Exception) {
                false
            }

            runOnUiThread {
                engineStatus.text = if (hasRoot) {
                    "KERNELSU: ROOT TERSEDIA | AI: BELUM DIMUAT"
                } else {
                    "KERNELSU: ROOT TIDAK TERSEDIA | AI: BELUM DIMUAT"
                }
            }
        }
    }

    private fun selectModel() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, modelPicker)
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
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, screenCaptureRequest)
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

            modelPicker -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.data?.let { copyAndLoadModel(it) }
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

                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    Toast.makeText(
                        this,
                        "Izin Screen Capture diberikan.",
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

                    if (!modelReady) {
                        result.text =
                            "OCR berhasil. Muat model Gemma untuk analisis."
                        return@addOnSuccessListener
                    }

                    analyzeText(extractedText)
                }
                .addOnFailureListener { error ->
                    result.text = "OCR gagal: ${error.localizedMessage}"
                }

        } catch (e: Exception) {
            result.text = "Gagal membuka gambar: ${e.localizedMessage}"
        }
    }

    private fun copyAndLoadModel(uri: Uri) {
        engineStatus.text = "AI: MENYALIN FILE MODEL..."

        ioExecutor.execute {
            val modelFile = File(filesDir, "gemma3-1b.litertlm")

            val copyResult = runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) {
                        "Tidak dapat membaca file model."
                    }

                    modelFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                require(modelFile.length() > 0) {
                    "File model kosong."
                }

                modelFile
            }

            runOnUiThread {
                copyResult.fold(
                    onSuccess = { file ->
                        engineStatus.text = "AI: MEMUAT MODEL..."

                        localAI.loadModel(file) { loadResult ->
                            loadResult.fold(
                                onSuccess = {
                                    modelReady = true
                                    engineStatus.text =
                                        "AI LOKAL: GEMMA 3 1B SIAP"

                                    Toast.makeText(
                                        this,
                                        "Model berhasil dimuat.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                onFailure = { error ->
                                    modelReady = false
                                    engineStatus.text =
                                        "AI: GAGAL MEMUAT MODEL"
                                    result.text =
                                        "Gagal memuat model: ${error.localizedMessage}"
                                }
                            )
                        }
                    },
                    onFailure = { error ->
                        engineStatus.text = "AI: GAGAL MENYALIN MODEL"
                        result.text =
                            "Gagal menyalin file: ${error.localizedMessage}"
                    }
                )
            }
        }
    }

    private fun analyze() {
        val promptText = question.text.toString().trim()

        if (promptText.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        if (!modelReady) {
            Toast.makeText(
                this,
                "Muat model AI terlebih dahulu.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        analyzeText(promptText)
    }

    private fun analyzeText(text: String) {
        result.text = "Gemma sedang menganalisis..."

        localAI.analyze(text) { response ->
            response.fold(
                onSuccess = { answer ->
                    result.text = answer
                },
                onFailure = { error ->
                    result.text =
                        "Analisis gagal: ${error.localizedMessage}"
                }
            )
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
        localAI.close()
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
