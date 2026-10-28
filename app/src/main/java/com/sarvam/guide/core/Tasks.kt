package com.sarvam.guide.core

object Tasks {
    const val WHATSAPP_PHOTO = "Send a photo on WhatsApp to the user's family member."
    const val WHATSAPP_VIDEO_CALL = "Make a video call to the user's family member on WhatsApp."
    const val WHATSAPP_VOICE_CALL = "Make a normal voice call to the user's family member on WhatsApp."
    const val YOUTUBE_PLAY = "Open YouTube and play a video for the user."

    /** The user said a call but not which kind; we must ask normal or video. */
    const val CALL_NEEDS_KIND = "call_needs_kind"

    private fun has(t: String, vararg words: String) = words.any { t.contains(it) }

    private val VIDEO_PHRASES = arrayOf(
        "video call", "video kalu", "video kal", "video call chey",
        "వీడియో కాల్", "వీడియోకాల్", "వీడియో కాల్ చేయి", "వీడియో కాలు",
        "वीडियो कॉल", "वीडियो काल", "वीडियो कॉल करना", "वीडियो कॉल करो",
    )

    private val VOICE_PHRASES = arrayOf(
        "voice call", "phone call", "normal call", "audio call", "phone chey",
        "voice call chey", "normal call chey", "phone karna", "phone karo",
        "వాయిస్ కాల్", "ఫోన్ కాల్", "సాధారణ కాల్", "నార్మల్ కాల్", "ఫోన్ చేయి",
        "वॉइस कॉल", "फोन कॉल", "सामान्य कॉल", "नॉर्मल कॉल", "फोन करना", "फोन करो",
    )

    private val CALL_PHRASES = arrayOf(
        "call", "కాల్", "కాల్ చేయి", "కేల్", "kalu", "kal chey", "కాలు",
        "कॉल", "कॉल करना", "कॉल करो", "call karna", "call karo",
        "phone", "ఫోన్", "फोन",
    )

    /** True if the transcript asks for a video call specifically. */
    fun isVideoCall(transcript: String): Boolean {
        val t = transcript.lowercase()
        return has(t, *VIDEO_PHRASES)
    }

    /** Resolves the clarification answer: video if said, otherwise normal call. */
    fun matchCallKind(transcript: String): String {
        val t = transcript.lowercase()
        val video = has(t, *VIDEO_PHRASES) || has(t, "video", "వీడియో", "वीडियो")
        return if (video) WHATSAPP_VIDEO_CALL else WHATSAPP_VOICE_CALL
    }

    /** Matches Telugu, Hindi, English and romanized transcripts. */
    fun match(transcript: String): String {
        val t = transcript.lowercase()
        val wantsVideoCall = has(t, *VIDEO_PHRASES)
        val wantsVoiceCall = has(t, *VOICE_PHRASES)
        val wantsCall = wantsVideoCall || wantsVoiceCall || has(t, *CALL_PHRASES)
        val photo =
            has(t, "ఫోటో", "photo", "picture", "పోటో", "ఫొటో",
                "फोटो", "तस्वीर", "फ़ोटो", "foto", "tasveer")
        val send =
            has(t, "send", "పంపు", "పంపాలని", "పంపించ", "పంపాలా", "pampu", "pampali", "pampalanu", "pampinchu",
                "भेज", "bhejo", "bhejna", "bhej", "send karna", "send karo")
        val play =
            has(t, "play", "ప్లే", "video", "వీడియో", "song", "పాట", "movie", "సినిమా", "పాడు",
                "pla", "paata", "pata", "cinema", "choodu", "chudali", "చూడాలి", "చూద్దాం", "వినాలి",
                "गाना", "वीडियो", "देखना", "चलाना", "यूट्यूब", "youtube", "gaana", "gana",
                "dekhna", "chalana", "watch")

        // If all three categories match, this is our own question echoed back
        // ("send a photo, make a call, or watch a video?") - not a request.
        if (photo && wantsCall && play) return ""
        if (wantsVideoCall) return WHATSAPP_VIDEO_CALL
        if (wantsVoiceCall) return WHATSAPP_VOICE_CALL
        if (wantsCall) return CALL_NEEDS_KIND
        if (photo || send) return WHATSAPP_PHOTO
        if (play) return YOUTUBE_PLAY
        return ""
    }
}
