package com.sarvam.guide.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface PcmSink {
    fun onChunk(pcm: ByteArray)
}

fun interface UtteranceListener {
    fun onUtterance(pcm: ByteArray)
}

class AudioGate(
    private val scope: CoroutineScope,
    private val utteranceListener: UtteranceListener,
) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var enabled = true
    private var chunkSink: PcmSink? = null

    fun setEnabled(on: Boolean) {
        enabled = on
    }

    fun setChunkSink(sink: PcmSink?) {
        chunkSink = sink
    }

    fun start() {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                WavUtil.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavUtil.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return@launch
            }
            record = rec
            rec.startRecording()
            val chunk = ByteArray(5120) // 160ms at 16kHz mono 16bit
            val speech = ArrayList<ByteArray>()
            val tail = ArrayDeque<ByteArray>()
            var speechBytes = 0
            var voiceChunks = 0
            var quietChunks = 0
            var inSpeech = false
            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    delay(5)
                    continue
                }
                if (!enabled) { // muted: drain and drop, keep the mic alive
                    tail.clear()
                    continue
                }
                chunkSink?.onChunk(chunk.copyOf(n))
                tail.addLast(chunk.copyOf(n))
                if (tail.size > PRE_ROLL_CHUNKS) tail.removeFirst()
                val loud = rms(chunk, n) > RMS_SPEECH
                if (loud) {
                    voiceChunks++
                    quietChunks = 0
                } else {
                    quietChunks++
                }
                if (!inSpeech && voiceChunks >= VOICE_START_CHUNKS) {
                    inSpeech = true
                    speech.clear()
                    speechBytes = 0
                    // Keep the chunks that triggered detection so the first
                    // syllable of the answer is never clipped.
                    for (c in tail.dropLast(1)) {
                        speech.add(c)
                        speechBytes += c.size
                    }
                }
                if (inSpeech) {
                    speech.add(chunk.copyOf(n))
                    speechBytes += n
                }
                if (inSpeech && (quietChunks >= SILENCE_END_CHUNKS || speechBytes > MAX_SPEECH_BYTES)) {
                    val full = ByteArray(speechBytes)
                    var off = 0
                    for (b in speech) {
                        b.copyInto(full, off)
                        off += b.size
                    }
                    inSpeech = false
                    voiceChunks = 0
                    quietChunks = 0
                    if (speechBytes >= MIN_SPEECH_BYTES) {
                        utteranceListener.onUtterance(full)
                    }
                }
            }
            runCatching { rec.stop() }
            rec.release()
            record = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { record?.release() }
        record = null
    }

    private fun rms(b: ByteArray, n: Int): Double {
        var sum = 0L
        var i = 0
        while (i + 1 < n) {
            val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
            sum += s.toLong() * s
            i += 2
        }
        if (i < 2) return 0.0
        return Math.sqrt(sum.toDouble() / (i / 2))
    }

    companion object {
        private const val RMS_SPEECH = 550.0
        private const val VOICE_START_CHUNKS = 3
        private const val SILENCE_END_CHUNKS = 8
        private const val MIN_SPEECH_BYTES = 3200
        private const val MAX_SPEECH_BYTES = 320000
        private const val PRE_ROLL_CHUNKS = 3
    }
}