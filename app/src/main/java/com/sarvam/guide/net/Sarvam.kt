package com.sarvam.guide.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sarvam.guide.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Sar {
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    fun airplaneMode(context: Context): Boolean =
        android.provider.Settings.Global.getInt(
            context.contentResolver,
            android.provider.Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1

    fun detectOfflineCause(context: Context): String? {
        if (hasInternet(context)) return null
        return if (airplaneMode(context)) "airplane" else "no_internet"
    }
}

class SarvamStt(private val apiKey: String = BuildConfig.SARVAM_API_KEY) {
    suspend fun transcribe(wav: File, languageCode: String = BuildConfig.LANG_CODE): String =
        withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", wav.name, wav.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", BuildConfig.STT_MODEL)
            .addFormDataPart("mode", BuildConfig.STT_MODE)
            .addFormDataPart("language_code", languageCode)
            .build()
        val req = Http.client.newCall(
            okhttp3.Request.Builder()
                .url(Http.SARVAM_STT_PATH)
                .header("api-subscription-key", apiKey)
                .post(body)
                .build(),
        )
        req.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("STT HTTP ${resp.code}: $text")
            return@withContext JSONObject(text).getString("transcript")
        }
    }
}

class SarvamTts(private val apiKey: String = BuildConfig.SARVAM_API_KEY) {
    suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("inputs", JSONArray().put(text))
            .put("target_language_code", BuildConfig.LANG_CODE)
            .put("model", BuildConfig.TTS_MODEL)
            .put("speaker", BuildConfig.TTS_SPEAKER)
            .put("pace", 0.85)
            .put("temperature", 0.7)
            .put("speech_sample_rate", 24000)
            .toString()
        val req = Http.sarvamPost(Http.SARVAM_TTS_PATH, apiKey, body)
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("TTS HTTP ${resp.code}: $text")
            val audios = JSONObject(text).getJSONArray("audios")
            val sb = StringBuilder()
            for (i in 0 until audios.length()) {
                val a = audios.opt(i)
                when (a) {
                    is JSONObject -> sb.append(a.optString("audio_base64", ""))
                    is String -> sb.append(a)
                    else -> {}
                }
            }
            return@withContext android.util.Base64.decode(sb.toString(), android.util.Base64.DEFAULT)
        }
    }
}

class SarvamChat(private val apiKey: String = BuildConfig.SARVAM_API_KEY) {
    private val system = (
        "You convert a machine's short on-screen guidance into one warm, simple, "
            + "spoken instruction for an elderly grandparent who is not comfortable with "
            + "smartphones. Speak in Telugu. Rules: one short paragraph; say where on "
            + "the screen the thing is (e.g. 'bottom, left side'); if it is an app icon or "
            + "button, DESCRIBE HOW IT LOOKS - its colour and shape (e.g. 'green round "
            + "icon with a white phone inside') - never assume the person knows the app "
            + "name; say what to do ('put your finger and press'); never assume the "
            + "person can read; be patient and encouraging."
        )

    suspend fun recover(recovery: String): String =
        call(
            system + "\n\nThe elderly user went to the WRONG screen or wrong app by mistake. "
                + "Gently tell them it happens to everyone, no problem at all, and guide them "
                + "back one step at a time, describing icons by colour and shape. Now lead "
                + "the person: " + recovery
        )

    suspend fun instruct(what: String, x: Double, y: Double, action: String): String =
        call(
            system + "\n\nOn screen there is a \"$what\" at position x=$x, y=$y. "
                + "Let the person \"$action\" it."
        )

    suspend fun celebrate(): String =
        call("The elderly user just finished a step by themselves. Praise them warmly in Telugu in one short sentence.")

    private suspend fun call(user: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", BuildConfig.CHAT_MODEL)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            .put("temperature", 0.8)
            .toString()
        Http.client.newCall(Http.sarvamPost(Http.SARVAM_CHAT_PATH, apiKey, body)).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Chat HTTP ${resp.code}: $text")
            val choices = JSONObject(text).getJSONArray("choices")
            val msg = choices.getJSONObject(0).getJSONObject("message")
            return@withContext if (msg.has("content") && !msg.isNull("content")) {
                msg.getString("content")
            } else {
                msg.optString("reasoning_content", "")
            }
        }
    }
}