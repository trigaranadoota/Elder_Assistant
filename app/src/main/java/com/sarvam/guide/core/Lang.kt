package com.sarvam.guide.core

import android.content.Context

/**
 * App-wide language selection (Telugu / Hindi / English).
 * Drives UI texts, canned audio folders and the STT language code.
 */
object Lang {
    const val TE = "te"
    const val HI = "hi"
    const val EN = "en"

    private const val PREFS = "guide"
    private const val KEY = "lang"

    @Volatile
    var code: String = TE
        private set

    fun init(context: Context) {
        code = context.getSharedPreferences(PREFS, 0).getString(KEY, TE) ?: TE
    }

    fun set(context: Context, newCode: String) {
        code = newCode
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, newCode).apply()
    }

    val sttCode: String
        get() = when (code) {
            HI -> "hi-IN"
            EN -> "en-IN"
            else -> "te-IN"
        }

    fun t(key: String): String = TEXTS[key]?.get(code) ?: TEXTS[key]?.get(EN) ?: key

    private val TEXTS: Map<String, Map<String, String>> = mapOf(
        "help" to mapOf(
            TE to "నాకు సహాయం చేయండి",
            HI to "मुझे मदद चाहिए",
            EN to "Help me",
        ),
        "hint" to mapOf(
            TE to "పెద్ద బటన్ నొక్కండి",
            HI to "बड़ा बटन दबाएँ",
            EN to "Press the big button",
        ),
        "idle" to mapOf(
            TE to "సిద్ధంగా ఉంది",
            HI to "तैयार है",
            EN to "Ready",
        ),
        "listening" to mapOf(
            TE to "వింటున్నాను… \"సహాయం\" అని చెప్పండి",
            HI to "सुन रहा हूँ… मदद के लिए बड़ा बटन दबाएँ",
            EN to "Listening… tap the big button for help",
        ),
        "start" to mapOf(
            TE to "ప్రారంభించండి",
            HI to "शुरू करें",
            EN to "Start",
        ),
        "stop" to mapOf(
            TE to "ఆపండి",
            HI to "रोकें",
            EN to "Stop",
        ),
        "needs_screen" to mapOf(
            TE to "స్క్రీన్ అనుమతి అవసరం",
            HI to "स्क्रीन अनुमति चाहिए",
            EN to "Screen permission needed",
        ),
        "perm_denied" to mapOf(
            TE to "మైక్రోఫోన్ అనుమతి అవసరం",
            HI to "माइक्रोफ़ोन अनुमति चाहिए",
            EN to "Microphone permission needed",
        ),
        "notify_title" to mapOf(
            TE to "Guide సిద్ధంగా ఉంది",
            HI to "Guide तैयार है",
            EN to "Guide is ready",
        ),
        "notify_text" to mapOf(
            TE to "\"సహాయం\" అని చెప్పండి లేదా పవర్ బటన్ 3 సార్లు నొక్కండి",
            HI to "पावर बटन 3 बार दबाएँ",
            EN to "Press the power button 3 times",
        ),
        "lang_button" to mapOf(
            TE to "భాష: తెలుగు",
            HI to "भाषा: हिंदी",
            EN to "Language: English",
        ),
        "lang_title" to mapOf(
            TE to "భాష ఎంచుకోండి",
            HI to "भाषा चुनें",
            EN to "Choose language",
        ),
        "med_button" to mapOf(
            TE to "మందుల రిమైండర్",
            HI to "दवा रिमाइंडर",
            EN to "Medicine reminder",
        ),
        "med_title" to mapOf(
            TE to "మందుల రిమైండర్",
            HI to "दवा रिमाइंडर",
            EN to "Medicine reminder",
        ),
        "med_name_hint" to mapOf(
            TE to "మందు పేరు",
            HI to "दवा का नाम",
            EN to "Medicine name",
        ),
        "med_add" to mapOf(
            TE to "జోడించు",
            HI to "जोड़ें",
            EN to "Add",
        ),
        "med_delete" to mapOf(
            TE to "తొలగించు",
            HI to "हटाएँ",
            EN to "Delete",
        ),
        "med_empty" to mapOf(
            TE to "ఇంకా మందులు లేవు",
            HI to "अभी कोई दवा नहीं",
            EN to "No medicines yet",
        ),
        "med_saved" to mapOf(
            TE to "రిమైండర్ సేవ్ అయింది",
            HI to "रिमाइंडर सेव हो गया",
            EN to "Reminder saved",
        ),
        "med_notify" to mapOf(
            TE to "మందు తీసుకునే సమయం",
            HI to "दवा लेने का समय",
            EN to "Time to take medicine",
        ),
    )
}
