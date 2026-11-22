
package com.mcqrootai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.Executors

class LocalAIEngine(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun loadModel(
        modelFile: File,
        onComplete: (Result<Unit>) -> Unit
    ) {
        executor.execute {
            val outcome = runCatching {
                require(modelFile.exists()) {
                    "File model tidak ditemukan."
                }

                val newEngine = Engine(
                    EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = appContext.cacheDir.absolutePath
                    )
                )

                try {
                    newEngine.initialize()

                    val newConversation =
                        newEngine.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(
                                    """
                                    Kamu adalah asisten belajar.
                                    Bantu memahami soal pilihan ganda.
                                    Jelaskan alasan jawaban secara singkat.
                                    Gunakan bahasa Indonesia.
                                    Jika informasi soal kurang jelas,
                                    minta pengguna memeriksanya.
                                    """.trimIndent()
                                )
                            )
                        )

                    engine = newEngine
                    conversation = newConversation

                } catch (error: Throwable) {
                    runCatching { newEngine.close() }
                    throw error
                }
            }

            mainHandler.post {
                onComplete(outcome)
            }
        }
    }

    fun analyze(
        question: String,
        onComplete: (Result<String>) -> Unit
    ) {
        executor.execute {
            val outcome = runCatching {
                val activeConversation =
                    conversation
                        ?: error("Model AI belum dimuat.")

                val response = activeConversation.sendMessage(
                    """
                    Analisis soal latihan berikut:

                    $question

                    Berikan:
                    1. Pilihan jawaban yang paling tepat.
                    2. Penjelasan alasannya.
                    3. Mengapa pilihan lainnya kurang tepat.
                    """.trimIndent()
                )

                response.text
            }

            mainHandler.post {
                onComplete(outcome)
            }
        }
    }

    fun close() {
        executor.execute {
            runCatching { conversation?.close() }
            conversation = null

            runCatching { engine?.close() }
            engine = null
        }

        executor.shutdown()
    }
}

