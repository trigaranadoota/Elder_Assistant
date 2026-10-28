package com.sarvam.guide.audio

import java.io.File
import java.io.FileOutputStream

object WavUtil {
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS = 16

    fun pcmToWav(pcm: ByteArray): ByteArray {
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeIntLE(header, 4, 36 + pcm.size)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1)
        writeShortLE(header, 22, CHANNELS.toShort())
        writeIntLE(header, 24, SAMPLE_RATE)
        writeIntLE(header, 28, SAMPLE_RATE * CHANNELS * BITS / 8)
        writeShortLE(header, 32, (CHANNELS * BITS / 8).toShort())
        writeShortLE(header, 34, BITS.toShort())
        writeAscii(header, 36, "data")
        writeIntLE(header, 40, pcm.size)
        return header + pcm
    }

    fun writeWav(file: File, pcm: ByteArray) {
        FileOutputStream(file).use { it.write(pcmToWav(pcm)) }
    }

    private fun writeAscii(b: ByteArray, off: Int, s: String) {
        s.toByteArray().copyInto(b, off)
    }

    private fun writeIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(b: ByteArray, off: Int, v: Short) {
        b[off] = (v.toInt() and 0xFF).toByte()
        b[off + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }
}