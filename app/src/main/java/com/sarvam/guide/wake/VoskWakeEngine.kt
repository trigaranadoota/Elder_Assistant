package com.sarvam.guide.wake

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Vosk-based Telugu wake-word engine.
 * Model: vosk-model-small-te copied under app files dir (bundled in assets
 * "vosk-model-small-te" or downloaded from alphacephei).
 * The small model is too weak for strict grammar matching, so we run free
 * dictation and match the wake phrases inside the transcribed text instead.
 */
class VoskWakeEngine(
    private val context: Context,
    private val listener: WakeListener,
) : WakeEngine {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var running = false
    private var silenced = false

    override fun start() {
        executor.execute {
            if (running) return@execute
            try {
                val modelDir = ModelManager.ensureModel(context)
                model = Model(modelDir.absolutePath)
                recognizer = Recognizer(model, 16000f)
                running = true
            } catch (e: Exception) {
                android.util.Log.w(TAG, "wake engine init failed", e)
            }
        }
    }

    private fun checkWake(text: String) {
        if (silenced || text.isBlank()) return
        val norm = text.replace(" ", "")
        for (p in WAKE_PHRASES) {
            if (norm.contains(p)) {
                android.util.Log.i(TAG, "WAKE MATCH '$p'")
                silenced = true
                listener.onWake()
                return
            }
        }
    }

    override fun onChunk(pcm: ByteArray) {
        if (!running || silenced || recognizer == null) return
        runCatching {
            executor.execute {
                try {
                    val r = recognizer ?: return@execute
                    if (r.acceptWaveForm(pcm, pcm.size)) {
                        val text = JSONObject(r.result).optString("text", "").trim()
                        android.util.Log.i(TAG, "result='$text'")
                        checkWake(text)
                    } else {
                        val partial = JSONObject(r.partialResult).optString("partial", "").trim()
                        if (partial.isNotEmpty()) android.util.Log.i(TAG, "partial='$partial'")
                        checkWake(partial)
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "wake chunk failed", e)
                }
            }
        }
    }

    override fun reset() {
        silenced = false
    }

    override fun stop() {
        running = false
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
    }

    override fun release() {
        stop()
        executor.shutdown()
    }

    companion object {
        private const val TAG = "VoskWakeEngine"
        val WAKE_PHRASES = listOf(
            "సహాయం",
            "నాకు సహాయం",
            "సాయం",
            "నాకు సహాయం కావాలి",
            "సహాయం కావాలి",
            "నాకు సాయం",
        )
    }
}