package com.sarvam.guide.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread

class ScreenCapturer(private val maxWidth: Int = 720) {

    @Volatile
    private var latest: Bitmap? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun start(
        projection: MediaProjection,
        displayWidth: Int,
        displayHeight: Int,
        densityDpi: Int,
        rotationDegrees: Int,
    ) {
        thread = HandlerThread("screen-capturer").also { it.start() }
        handler = Handler(thread!!.looper)

        val reader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader
        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val plane = img.planes[0]
                val bitmap = Bitmap.createBitmap(
                    plane.rowStride / 4,
                    img.height,
                    Bitmap.Config.ARGB_8888,
                )
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                img.close()
                val rotated = rotate(bitmap, rotationDegrees)
                latest = scaleDown(rotated)
            } catch (e: Exception) {
                android.util.Log.w("ScreenCapturer", "frame dropped", e)
            }
        }, handler)

        // Required on Android 10+: a callback must be registered before capture starts.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                runCatching { virtualDisplay?.release() }
                virtualDisplay = null
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "guide-capture",
            displayWidth,
            displayHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    fun capture(): Bitmap? = latest

    fun stop() {
        runCatching { virtualDisplay?.release() }
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        handler?.looper?.quitSafely()
        thread = null
        latest = null
    }

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        src.recycle()
        return out
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        if (src.width <= maxWidth) return src
        val h = (src.height.toFloat() * maxWidth / src.width).toInt()
        val out = Bitmap.createScaledBitmap(src, maxWidth, h, true)
        src.recycle()
        return out
    }
}