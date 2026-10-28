package com.sarvam.guide.net

import android.util.Base64
import com.sarvam.guide.BuildConfig
import com.sarvam.guide.model.GuideStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GeminiVision(private val apiKey: String = BuildConfig.GEMINI_API_KEY) {

    private val schema = JSONObject().apply {
        put("type", "OBJECT")
        put("properties", JSONObject().apply {
            put("step", JSONObject().put("type", "INTEGER"))
            put("target", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().put("type", "NUMBER"))
                    put("y", JSONObject().put("type", "NUMBER"))
                })
                put("required", JSONArray().put("x").put("y"))
            })
            put("what", JSONObject().put("type", "STRING"))
            put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("tap").put("scroll").put("wait")))
            put("done", JSONObject().put("type", "BOOLEAN"))
            put("risk", JSONObject().put("type", "STRING").put("enum", JSONArray().put("none").put("permission").put("payment")))
            put("screen", JSONObject().apply {
                put("type", "STRING")
                put("enum", JSONArray()
                    .put("home_screen").put("whatsapp_chatlist").put("whatsapp_chat")
                    .put("whatsapp_attach").put("whatsapp_gallery").put("whatsapp_send_preview")
                    .put("whatsapp_videocall").put("whatsapp_voicecall")
                    .put("youtube_home").put("youtube_video").put("other"))
            })
            put("on_task", JSONObject().put("type", "BOOLEAN"))
            put("recovery", JSONObject().put("type", "STRING"))
        })
        put("required", JSONArray()
            .put("step").put("target").put("what").put("action").put("done")
            .put("risk").put("screen").put("on_task").put("recovery"))
    }

    suspend fun ground(image: ByteArray, width: Int, height: Int, task: String, mime: String = "image/png"): GuideStep =
        withContext(Dispatchers.IO) {
            val prompt = """
            You are the "eyes" of a voice-guide helping an elderly grandparent who cannot
            read or navigate smartphones.
            Task the person wants to complete: "$task".
            Look at the CURRENT screen only and return the ONE single next tap the person
            should make to move toward the task.
            Return JSON exactly as:
            {
              "step": 1,
              "target": {"x": 0-1, "y": 0-1}  <- center fraction of image width/height,
              "what": "short plain-English name of the element; ALSO mention its COLOUR
                       and SHAPE (e.g. green round icon with a white phone inside) because
                       the listener cannot read",
              "action": "tap" | "scroll" | "wait",
              "done": false,
              "risk": "none" | "permission" | "payment",
              "screen": one of home_screen, whatsapp_chatlist, whatsapp_chat,
                        whatsapp_attach, whatsapp_gallery, whatsapp_send_preview,
                        whatsapp_videocall, whatsapp_voicecall, youtube_home,
                        youtube_video, other,
              "on_task": true ONLY IF this screen is a real step of the task path,
              "recovery": "" if on_task; else ONE short imperative sentence telling how
                          to get back to the right app, describing icons by colour and
                          shape,
            }
            Rules:
            - System permission dialog visible -> risk=permission, do NOT instruct to confirm it.
            - Money/UPI screen visible -> risk=payment, do NOT guide forward.
            - Task already complete -> done=true (for calls, when the call screen
              shows ringing or connected).
            - Wrong screen (any screen unrelated to THIS task's path) -> on_task=false,
              step back to last correct step, fill recovery.
            Return ONLY the JSON object.
        """.trimIndent()

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mime)
                                put("data", Base64.encodeToString(image, Base64.NO_WRAP))
                            })
                        })
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            })
        }

        val req = Http.geminiGenerate(BuildConfig.VISION_MODEL, apiKey, body.toString())
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Vision HTTP ${resp.code}: $text")
            val candidates = JSONObject(text).getJSONArray("candidates")
            val parts = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
            val stepText = parts.getJSONObject(0).optString("text")
            return@withContext parse(stepText, width, height)
        }
    }

    private fun parse(text: String, width: Int, height: Int): GuideStep {
        val o = JSONObject(text)
        val t = o.getJSONObject("target")
        var x = t.optDouble("x", 0.5)
        var y = t.optDouble("y", 0.5)
        if (x > 1.0 || y > 1.0) {
            x /= width
            y /= height
        }
        return GuideStep(
            step = o.optInt("step", 1),
            targetX = x.coerceIn(0.0, 1.0),
            targetY = y.coerceIn(0.0, 1.0),
            what = o.optString("what", ""),
            action = o.optString("action", "tap"),
            done = o.optBoolean("done", false),
            risk = o.optString("risk", "none"),
            screen = o.optString("screen", "other"),
            onTask = o.optBoolean("on_task", true),
            recovery = o.optString("recovery", ""),
        )
    }
}