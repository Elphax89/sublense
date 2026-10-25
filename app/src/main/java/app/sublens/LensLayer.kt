package app.sublens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws the translations right into the lens: one small window per text block,
 * exactly over the original, with its background and text colour.
 *
 * Each block is its own window so only the text itself is covered –
 * everywhere else in the lens, touches go through to the app underneath.
 * Tapping a block shows the original for a few seconds.
 */
class LensLayer(
    private val ctx: Context,
    private val wm: WindowManager,
) {
    private val density = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())

    private val boxes = ArrayList<BoxView>()
    private var used = 0

    private var captureHidden = false
    /** Only blocks touching this area (crop coordinates) are hidden for capturing. null = all. */
    private var captureRegion: Rect? = null
    private var peekHidden = false

    /** The original is being shown (true) or the translation again (false). */
    var onPeekChanged: ((Boolean) -> Unit)? = null

    val peeking: Boolean get() = peekHidden

    /** Called after every visible change of the blocks. */
    var onChanged: (() -> Unit)? = null

    /** Areas of the visible blocks in crop coordinates (for change detection). */
    var drawnRects: List<Rect> = emptyList()
        private set

    /** The same areas in window coordinates. */
    var windowRects: List<Rect> = emptyList()
        private set

    val hasContent: Boolean get() = used > 0

    /** true while blocks really cover the original (not hidden). */
    val showing: Boolean get() = used > 0 && !captureHidden && !peekHidden

    private val endPeek = Runnable { setPeek(false) }

    /**
     * Shows [items]. [originX]/[originY] is the window position of the top left corner
     * of the crop, [maxBottom] the bottom edge of the lens in window coordinates.
     */
    /** Returns true if new windows were added (they are then on top). */
    fun show(items: List<LensItem>, originX: Int, originY: Int, maxBottom: Int, cropWidth: Int): Boolean {
        val visible = items.filter { it.changed }
        val rects = ArrayList<Rect>(visible.size)
        val wins = ArrayList<Rect>(visible.size)
        var addedNew = false
        for ((i, item) in visible.withIndex()) {
            val box = if (i < boxes.size) boxes[i] else BoxView(ctx).also { boxes += it }
            val pad = (3f * density).roundToInt()
            var left = originX + item.rect.left - pad
            val top = originY + item.rect.top - pad
            var width = item.rect.width() + 2 * pad
            // Longer translation (e.g. subtitles): grow sideways first instead of shrinking the text.
            val wanted = box.naturalWidth(item)
            if (wanted > width) {
                val newWidth = min(wanted, cropWidth)
                val center = left + width / 2
                left = (center - newWidth / 2).coerceIn(originX, originX + cropWidth - newWidth)
                width = newWidth
            }
            val minHeight = item.rect.height() + 2 * pad
            val height = box.bind(item, width, minHeight, max(minHeight, maxBottom - top))
            val same = box.added && box.params.x == left && box.params.y == top &&
                box.params.width == width && box.params.height == height
            box.params.x = left
            box.params.y = top
            box.params.width = width
            box.params.height = height
            if (same) {
                // unchanged – don't touch the window (no flicker, no redraw)
            } else if (box.added) {
                try {
                    wm.updateViewLayout(box, box.params)
                } catch (_: Exception) {
                }
            } else {
                try {
                    wm.addView(box, box.params)
                    box.added = true
                    addedNew = true
                } catch (_: Exception) {
                }
            }
            rects += Rect(left - originX, top - originY, left - originX + width, top - originY + height)
            wins += Rect(left, top, left + width, top + height)
        }
        // Remove the remaining windows
        for (i in visible.size until boxes.size) removeBox(boxes[i])
        while (boxes.size > visible.size + SPARE) boxes.removeAt(boxes.size - 1)
        used = visible.size
        drawnRects = rects
        windowRects = wins
        applyVisibility()
        onChanged?.invoke()
        return addedNew
    }

    fun clear() {
        for (b in boxes) removeBox(b)
        boxes.clear()
        used = 0
        drawnRects = emptyList()
        windowRects = emptyList()
        onChanged?.invoke()
    }

    /** Hide briefly so screen capture sees the original. */
    fun setCaptureHidden(hidden: Boolean, region: Rect? = null) {
        if (captureHidden == hidden && captureRegion == region) return
        captureHidden = hidden
        captureRegion = if (hidden) region else null
        applyVisibility()
    }

    /** Show the original for a few seconds (tapping a block). */
    fun peekOriginal() {
        setPeek(true)
        main.postDelayed(endPeek, PEEK_MS)
    }

    /** Show the original until it is switched off again (Preview button). */
    fun setPeek(on: Boolean) {
        main.removeCallbacks(endPeek)
        if (peekHidden == on) return
        peekHidden = on
        applyVisibility()
        onChanged?.invoke()
        onPeekChanged?.invoke(on)
    }

    fun destroy() {
        main.removeCallbacks(endPeek)
        clear()
    }

    private fun applyVisibility() {
        val region = captureRegion
        for (i in 0 until min(used, boxes.size)) {
            val capHide = captureHidden && (region == null || drawnRects.getOrNull(i)?.let { Rect.intersects(it, region) } != false)
            boxes[i].visibility = if (capHide || peekHidden) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun removeBox(b: BoxView) {
        if (!b.added) return
        try {
            wm.removeView(b)
        } catch (_: Exception) {
        }
        b.added = false
    }

    /** One translated block. */
    @SuppressLint("ViewConstructor")
    private inner class BoxView(context: Context) : View(context) {
        var added = false
        val params = WindowManager.LayoutParams(
            0,
            0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var layout: StaticLayout? = null
        private val radius = 4f * density
        private val padX = 3f * density

        init {
            setOnClickListener { peekOriginal() }
        }

        private fun baseSize(item: LensItem): Float {
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SP, resources.displayMetrics)
            return max(minPx, item.lineHeightPx / 1.2f)
        }

        /** Width the translation would need at original size (as many lines as the original). */
        fun naturalWidth(item: LensItem): Int {
            textPaint.textSize = baseSize(item)
            val lines = max(1, (item.rect.height() + item.lineHeightPx / 2) / max(1, item.lineHeightPx))
            val oneLine = textPaint.measureText(item.translated)
            return (oneLine / lines * 1.08f + 2 * padX + 2).roundToInt()
        }

        /** Prepares the text and returns the required window height. */
        private var boundKey: String? = null
        private var boundHeight = 0

        fun bind(item: LensItem, width: Int, minHeight: Int, maxHeight: Int): Int {
            val key = "${item.translated}|${item.background}|${item.foreground}|${item.lineHeightPx}|$width|$minHeight|$maxHeight"
            if (key == boundKey && layout != null) return boundHeight
            bgPaint.color = item.background
            textPaint.color = item.foreground
            val avail = max(1, (width - 2 * padX).roundToInt())
            val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SP, resources.displayMetrics)
            // Font size as in the original (line height ≈ 1.2 × font size) …
            var size = baseSize(item)
            var l = makeLayout(item.translated, size, avail)
            // … and shrink until the translated text fits into the area of the original.
            while (l.height > minHeight - 2 && size > minPx) {
                size = max(minPx, size * 0.92f)
                l = makeLayout(item.translated, size, avail)
            }
            layout = l
            invalidate()
            boundKey = key
            boundHeight = (l.height + 4).coerceIn(minHeight, max(minHeight, maxHeight))
            return boundHeight
        }

        private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
            textPaint.textSize = size
            return StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
        }

        override fun onDraw(canvas: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            val l = layout ?: return
            canvas.save()
            canvas.translate(padX, max(0f, (height - l.height) / 2f))
            l.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private const val PEEK_MS = 3000L
        private const val MIN_TEXT_SP = 9f
        private const val SPARE = 4
    }
}
