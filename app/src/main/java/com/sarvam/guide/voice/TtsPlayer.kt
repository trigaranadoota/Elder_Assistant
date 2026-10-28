package com.sarvam.guide.voice

import android.content.Context
import android.media.MediaPlayer
import java.io.File

class TtsPlayer(private val context: Context) {

    private var current: MediaPlayer? = null

    /** Plays a PCM16 WAV byte array. */
    fun play(wav: ByteArray, onComplete: (() -> Unit)? = null): MediaPlayer? {
        stop()
        val f = File(context.cacheDir, "guide_speak.wav")
        f.writeBytes(wav)
        val mp = MediaPlayer()
        mp.setDataSource(f.absolutePath)
        mp.prepare()
        mp.setOnCompletionListener { m ->
            onComplete?.invoke()
            onDone(m)
        }
        mp.start()
        current = mp
        return mp
    }

    fun playAsset(path: String, onComplete: (() -> Unit)? = null): MediaPlayer? {
        stop()
        val mp = MediaPlayer()
        context.assets.openFd(path).use { fd -> mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        mp.prepare()
        mp.setOnCompletionListener { m ->
            onComplete?.invoke()
            onDone(m)
        }
        mp.start()
        current = mp
        return mp
    }

    fun playSilentFallback() { /* no-op, kept for symmetry */ }

    private fun onDone(mp: MediaPlayer) {
        if (current === mp) current = null
        runCatching { mp.release() }
    }

    fun stop() {
        runCatching { current?.stop() }
        current?.release()
        current = null
    }
}