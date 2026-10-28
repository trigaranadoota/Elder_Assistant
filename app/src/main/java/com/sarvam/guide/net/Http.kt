package com.sarvam.guide.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val SARVAM_BASE = "https://api.sarvam.ai"
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

    const val SARVAM_STT_PATH = "$SARVAM_BASE/speech-to-text"
    const val SARVAM_TTS_PATH = "$SARVAM_BASE/text-to-speech"
    const val SARVAM_CHAT_PATH = "$SARVAM_BASE/chat/completions"

    fun sarvamPost(url: String, apiKey: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("api-subscription-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    fun geminiGenerate(model: String, apiKey: String, body: String): Request =
        Request.Builder()
            .url("$GEMINI_BASE/models/$model:generateContent?key=$apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

    fun get(url: String): Request = Request.Builder().url(url).get().build()

    fun downloadTo(request: Request, file: java.io.File) {
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Download ${resp.code} for $request")
            file.outputStream().use { os -> resp.body?.byteStream()?.copyTo(os) }
        }
    }
}
