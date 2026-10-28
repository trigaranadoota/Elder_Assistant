package com.sarvam.guide.wake

interface WakeEngine : com.sarvam.guide.audio.PcmSink {
    fun start()
    fun stop()
    fun release()
    /** Re-arm the engine after a wake fired, so it can wake again. */
    fun reset() {}
}

interface WakeListener {
    fun onWake()
}