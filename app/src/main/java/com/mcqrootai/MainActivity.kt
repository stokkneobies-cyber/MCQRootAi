
package com.mcqrootai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : AppCompatActivity() {

    private lateinit var question: EditText
    private lateinit var result: TextView

    private val imagePicker = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        question = findViewById(R.id.question)
        result = findViewById(R.id.result)

        findViewById<Button>(R.id.pickImage).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, imagePicker)
        }

        findViewById<Button>(R.id.analyze).setOnClickListener {
            analyze()
        }

        findViewById<Button>(R.id.floating).setOnClickListener {
            showFloatingResult()
        }
    }

    @Deprecated("Legacy result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == imagePicker &&
            resultCode == Activity.RESULT_OK
        ) {
            val uri = data?.data ?: return

            try {
                val image = InputImage.fromFilePath(this, uri)

                TextRecognition
                    .getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener { visionText ->
                        question.setText(visionText.text)

                        Toast.makeText(
                            this,
                            "OCR berhasil membaca teks.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(
                            this,
                            "OCR gagal: ${it.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }

            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Gagal membuka gambar: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun analyze() {
        val promptText = question.text.toString().trim()

        if (promptText.isEmpty()) {
            question.error = "Masukkan soal terlebih dahulu"
            return
        }

        result.text =
            "Teks soal berhasil disiapkan.\n\n" +
            "Mesin AI lokal belum dihubungkan.\n" +
            "Tahap berikutnya adalah memasang model AI lokal."
    }

    private fun showFloatingResult() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
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

        val text = result.text.toString()

        startService(
            Intent(this, FloatingResultService::class.java)
                .putExtra("result", text)
        )
    }
}
