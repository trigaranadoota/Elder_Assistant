package com.sarvam.guide.wake

import android.content.Context
import com.sarvam.guide.net.Http
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

object ModelManager {
    private const val TAG = "ModelManager"
    private const val MAX_ATTEMPTS = 3
    private const val MODEL_ASSET = "vosk-model-small-te"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-te-0.42"

    fun ensureModel(context: Context): File {
        val root = File(context.filesDir, "vosk")
        root.mkdirs()
        val dir = File(root, MODEL_DIR_NAME)
        if (isValidModel(dir)) return dir

        // 1) bundled in assets -> copy tree
        val asset = context.assets.list(MODEL_ASSET)
        if (asset != null && asset.isNotEmpty()) {
            dir.deleteRecursively()
            copyAssetTree(context, MODEL_ASSET, dir)
            if (isValidModel(dir)) return dir
        }

        // 2) else download once (retry until we have a complete, valid model)
        dir.deleteRecursively()
        val zip = File(root, "model.zip")
        repeat(MAX_ATTEMPTS) {
            try {
                zip.delete()
                Http.downloadTo(Http.get(MODEL_URL), zip)
                try {
                    unzip(zip, root)
                } finally {
                    zip.delete()
                }
                if (isValidModel(dir)) return dir
                dir.deleteRecursively()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "model download attempt ${it + 1} failed", e)
                zip.delete()
                dir.deleteRecursively()
            }
            Thread.sleep(1000)
        }
        throw IllegalStateException("Vosk model could not be downloaded")
    }

    private fun isValidModel(dir: File): Boolean =
        dir.isDirectory && File(dir, "am/final.mdl").isFile && File(dir, "conf/mfcc.conf").isFile

    private fun copyAssetTree(context: Context, path: String, dst: File) {
        dst.mkdirs()
        val children = context.assets.list(path)
        if (children != null && children.isNotEmpty()) {
            for (c in children) copyAssetTree(context, "$path/$c", File(dst, c))
        } else {
            context.assets.open(path).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun unzip(zip: File, into: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.trim(' ', '/')
                if (entry.isDirectory) {
                    File(into, name).mkdirs()
                } else {
                    val out = File(into, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}