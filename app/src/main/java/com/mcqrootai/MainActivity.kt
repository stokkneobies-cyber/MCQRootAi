package com.mcqrootai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var apiKey: EditText
    private lateinit var question: EditText
    private lateinit var result: TextView
    private val prefs by lazy { getSharedPreferences("mcq_settings", MODE_PRIVATE) }
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS).build()
    private val imagePicker = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        apiKey = findViewById(R.id.apiKey)
        question = findViewById(R.id.question)
        result = findViewById(R.id.result)
        apiKey.setText(prefs.getString("api_key", ""))

        findViewById<Button>(R.id.pickImage).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(intent, imagePicker)
        }
        findViewById<Button>(R.id.analyze).setOnClickListener { analyze() }
        findViewById<Button>(R.id.floating).setOnClickListener { showFloatingResult() }
    }

    @Deprecated("Legacy result API retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == imagePicker && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            try {
                val image = InputImage.fromFilePath(this, uri)
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(image)
                    .addOnSuccessListener { visionText ->
                        question.setText(visionText.text)
                        Toast.makeText(this, "Teks screenshot berhasil dibaca. Periksa hasil OCR sebelum analisis.", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "OCR gagal: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membuka gambar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun analyze() {
        val key = apiKey.text.toString().trim()
        val promptText = question.text.toString().trim()
        if (key.isEmpty()) { apiKey.error = "Masukkan OpenAI API key"; return }
        if (promptText.isEmpty()) { question.error = "Masukkan soal atau pilih screenshot"; return }
        prefs.edit().putString("api_key", key).apply()
        result.text = "Sedang menganalisis..."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content",
                "Kamu asisten belajar. Analisis soal pilihan ganda berdasarkan teks yang diberikan. Berikan pilihan paling tepat, alasan singkat, dan jika informasi tidak cukup katakan demikian. Jangan menebak huruf jawaban bila opsi tidak terbaca."))
            .put(JSONObject().put("role", "user").put("content", promptText))
        val body = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("messages", messages)
            .put("temperature", 0.2)
            .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { result.text = "Koneksi gagal: ${e.localizedMessage}" }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    val output = try {
                        val json = JSONObject(raw)
                        if (!it.isSuccessful) "API error ${it.code}: ${json.optJSONObject("error")?.optString("message") ?: raw}"
                        else json.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content")
                    } catch (e: Exception) { "Gagal membaca respons: $raw" }
                    runOnUiThread { result.text = output }
                }
            }
        })
    }

    private fun showFloatingResult() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Izinkan 'Tampil di atas aplikasi lain', lalu tekan tombol ini lagi.", Toast.LENGTH_LONG).show()
            return
        }
        val text = result.text.toString()
        startService(Intent(this, FloatingResultService::class.java).putExtra("result", text))
    }
}
