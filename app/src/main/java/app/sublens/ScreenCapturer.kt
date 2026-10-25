package app.sublens

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer

/**
 * Reads the screen via screen capture (MediaProjection).
 * Nothing is stored: each frame only lives briefly in memory,
 * only the lens area is copied from it, the rest is discarded immediately.
 *
 * All access to the reader/display runs on its own [handler] thread.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("SubLensCapture").apply { start() }
    val handler = Handler(thread.looper)

    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var outBuffer: ByteBuffer? = null

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            // e.g. via the "Stop sharing" chip in the status bar
            onStopped()
        }
    }

    /** Must be called exactly once (from Android 14, each capture permission can only be used once). */
    fun start(w: Int, h: Int, dpi: Int) {
        projection.registerCallback(callback, handler)
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        width = w
        height = h
        virtualDisplay = projection.createVirtualDisplay(
            "SubLens",
            w,
            h,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler,
        )
    }

    /** After rotating: keep using the same capture, only adjust the size. */
    fun resize(w: Int, h: Int, dpi: Int) {
        handler.post {
            if (w == width && h == height) return@post
            val vd = virtualDisplay ?: return@post
            val old = reader
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            vd.resize(w, h, dpi)
            vd.surface = r.surface
            reader = r
            width = w
            height = h
            try {
                old?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Gets the newest frame and crops the area [crop].
     * Returns null if nothing changed since last time.
     * Only call on the [handler] thread.
     */
    fun grab(crop: Rect): Bitmap? {
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null

        try {
            if (image.width != width || image.height != height) return null
            val c = Rect(crop)
            if (!c.intersect(0, 0, image.width, image.height)) return null
            val w = c.width()
            val h = c.height()
            if (w < 32 || h < 16) return null

            val plane = image.planes[0]
            if (plane.pixelStride != 4) return null
            val rowStride = plane.rowStride
            val src = plane.buffer.duplicate()

            val needed = w * h * 4
            val out = outBuffer?.takeIf { it.capacity() >= needed }
                ?: ByteBuffer.allocateDirect(needed).also { outBuffer = it }
            out.clear()
            for (y in 0 until h) {
                val start = (c.top + y) * rowStride + c.left * 4
                src.limit(start + w * 4)
                src.position(start)
                out.put(src)
            }
            out.flip()

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(out)
            return bitmap
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    /**
     * Small fingerprint of the crop (brightness on a coarse grid) to notice
     * whether something moves under the lens. Points in [mask] (our own translations) don't count.
     * Returns null if no new frame arrived since last time (= nothing changed).
     */
    fun signature(crop: Rect, mask: List<Rect>): IntArray? {
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return null
        try {
            if (image.width != width || image.height != height) return null
            val c = Rect(crop)
            if (!c.intersect(0, 0, image.width, image.height)) return null
            val plane = image.planes[0]
            if (plane.pixelStride != 4) return null
            val rowStride = plane.rowStride
            val buf = plane.buffer
            val out = IntArray(SIG_X * SIG_Y)
            val margin = 4
            for (gy in 0 until SIG_Y) {
                val y = (gy + 0.5f) * c.height() / SIG_Y
                for (gx in 0 until SIG_X) {
                    val x = (gx + 0.5f) * c.width() / SIG_X
                    val xi = x.toInt()
                    val yi = y.toInt()
                    val masked = mask.any { m ->
                        xi >= m.left - margin && xi < m.right + margin && yi >= m.top - margin && yi < m.bottom + margin
                    }
                    if (masked) {
                        out[gy * SIG_X + gx] = -1
                        continue
                    }
                    val idx = (c.top + yi) * rowStride + (c.left + xi) * 4
                    val rr = buf.get(idx).toInt() and 0xFF
                    val gg = buf.get(idx + 1).toInt() and 0xFF
                    val bb = buf.get(idx + 2).toInt() and 0xFF
                    out[gy * SIG_X + gx] = (rr * 3 + gg * 6 + bb) / 10
                }
            }
            return out
        } catch (_: Exception) {
            return null
        } finally {
            image.close()
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        handler.post {
            try {
                virtualDisplay?.release()
            } catch (_: Exception) {
            }
            virtualDisplay = null
            try {
                reader?.close()
            } catch (_: Exception) {
            }
            reader = null
            try {
                projection.unregisterCallback(callback)
            } catch (_: Exception) {
            }
        }
        thread.quitSafely()
    }

    companion object {
        const val SIG_X = 48
        const val SIG_Y = 24

        /**
         * Area (in crop coordinates [w]×[h]) where something changed, extended by one grid cell.
         * null = nothing changed.
         */
        const val CELL_THRESHOLD = 22

        /** [settleRef]: fingerprint of the previous tick. If the changed area is still moving, returns null (wait). */
        fun changedRegion(
            a: IntArray,
            b: IntArray,
            w: Int,
            h: Int,
            ignore: BooleanArray? = null,
            settleRef: IntArray? = null,
        ): Rect? {
            if (a.size != b.size || a.size != SIG_X * SIG_Y) return Rect(0, 0, w, h)
            var count = 0
            var unsettled = 0
            var x0 = SIG_X
            var y0 = SIG_Y
            var x1 = -1
            var y1 = -1
            for (gy in 0 until SIG_Y) for (gx in 0 until SIG_X) {
                val i = gy * SIG_X + gx
                if (a[i] < 0 || b[i] < 0 || kotlin.math.abs(a[i] - b[i]) <= CELL_THRESHOLD) continue
                if (ignore != null && i < ignore.size && ignore[i]) continue
                count++
                if (settleRef != null && settleRef.size == b.size && settleRef[i] >= 0 &&
                    kotlin.math.abs(settleRef[i] - b[i]) > CELL_THRESHOLD
                ) {
                    unsettled++
                }
                if (gx < x0) x0 = gx
                if (gy < y0) y0 = gy
                if (gx > x1) x1 = gx
                if (gy > y1) y1 = gy
            }
            if (count < 3) return null
            if (unsettled * 10 > count) return null
            val cw = w.toFloat() / SIG_X
            val ch = h.toFloat() / SIG_Y
            return Rect(
                ((x0 - 1) * cw).toInt().coerceAtLeast(0),
                ((y0 - 1) * ch).toInt().coerceAtLeast(0),
                ((x1 + 2) * cw).toInt().coerceAtMost(w),
                ((y1 + 2) * ch).toInt().coerceAtMost(h),
            )
        }

        /** Has something visibly changed outside our own translations? */
        fun changed(a: IntArray, b: IntArray): Boolean {
            if (a.size != b.size) return true
            var count = 0
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x < 0 || y < 0) continue
                if (kotlin.math.abs(x - y) > 22) {
                    count++
                    if (count >= 3) return true
                }
            }
            return false
        }
    }
}
