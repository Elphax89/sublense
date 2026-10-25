package app.sublens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translated text blocks over the camera preview or a still picture, in the colours of
 * the original. Tapping a block shows its original until tapped again.
 */
class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** How the picture is fitted into the view: FILL = cropped (camera preview), FIT = letterboxed (photo). */
    enum class Fit { FILL, FIT }

    private val density = resources.displayMetrics.density
    private var items: List<LensItem> = emptyList()
    private var qrRect: Rect? = null
    private var imageW = 0
    private var imageH = 0
    private var fit = Fit.FILL
    private val peeked = HashSet<Int>()

    /** Show the originals everywhere (Original button). */
    var showOriginal = false
        set(value) {
            field = value
            invalidate()
        }

    var accentColor: Int = context.getColor(R.color.accent)

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val qrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val box = RectF()

    private class Drawn(val bounds: RectF, val layout: StaticLayout, val index: Int)

    private var drawn: List<Drawn> = emptyList()
    private var dirty = true

    /**
     * Font size and box width chosen for a translation, in picture pixels. Live frames differ by a few
     * pixels each time; reusing the earlier choice keeps the line breaks from jumping between 1 and 2 lines.
     */
    private class Fitted(val rectW: Float, val rectH: Float, val size: Float, val boxW: Float)

    private val fitted = HashMap<String, Fitted>()

    fun show(newItems: List<LensItem>, qr: Rect?, width: Int, height: Int, mode: Fit) {
        if (newItems != items) peeked.clear()
        if (width != imageW || height != imageH || mode != fit) fitted.clear()
        items = newItems
        qrRect = qr
        imageW = width
        imageH = height
        fit = mode
        dirty = true
        invalidate()
    }

    fun clear() {
        items = emptyList()
        qrRect = null
        peeked.clear()
        drawn = emptyList()
        invalidate()
    }

    val hasText: Boolean get() = items.any { it.changed }

    /** All translations, top to bottom (for copying). */
    fun allTranslations(): String = items.joinToString("\n") { it.translated }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dirty = true
    }

    // ---------------------------------------------------------------- Geometry

    private fun scale(): Float {
        if (imageW <= 0 || imageH <= 0 || width <= 0 || height <= 0) return 1f
        val sx = width.toFloat() / imageW
        val sy = height.toFloat() / imageH
        return if (fit == Fit.FILL) max(sx, sy) else min(sx, sy)
    }

    private fun map(r: Rect, out: RectF) {
        val s = scale()
        val dx = (width - imageW * s) / 2f
        val dy = (height - imageH * s) / 2f
        out.set(dx + r.left * s, dy + r.top * s, dx + r.right * s, dy + r.bottom * s)
    }

    private fun layoutAll() {
        dirty = false
        val s = scale()
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SP, resources.displayMetrics)
        val pad = 3f * density
        val out = ArrayList<Drawn>()
        for ((i, item) in items.withIndex()) {
            if (!item.changed) continue
            val r = RectF()
            map(item.rect, r)
            if (r.right < 0 || r.left > width || r.bottom < 0 || r.top > height) continue
            r.inset(-pad, -pad)
            val prev = fitted[item.translated]
            val rw = item.rect.width().toFloat()
            val rh = item.rect.height().toFloat()
            val reuse = prev != null && abs(prev.rectW - rw) <= prev.rectW * 0.3f && abs(prev.rectH - rh) <= prev.rectH * 0.35f
            var size: Float
            if (reuse && prev != null) {
                size = max(minPx, prev.size * s)
                val w = min(prev.boxW * s, width - 2 * pad)
                r.right = (r.left + w).coerceAtMost(width - pad)
                r.left = r.right - w
            } else {
                size = max(minPx, item.lineHeightPx * s / 1.2f)
                textPaint.textSize = size
                // Longer translation: grow to the right first (up to 1.6 × and the view width), then shrink the text.
                val lines = max(1, ((item.rect.height() + item.lineHeightPx / 2) / max(1, item.lineHeightPx)))
                val natural = textPaint.measureText(item.translated) / lines * 1.08f + 2 * pad
                if (natural > r.width()) {
                    val w = min(natural, min(r.width() * 1.6f, width - 2 * pad))
                    r.left = r.left.coerceIn(pad, max(pad, width - pad - w))
                    r.right = r.left + w
                }
            }
            val avail = max(1, (r.width() - 2 * pad).toInt())
            var l = makeLayout(item.translated, size, avail)
            if (!reuse) {
                while (l.height > r.height() - 2 && size > minPx) {
                    size = max(minPx, size * 0.92f)
                    l = makeLayout(item.translated, size, avail)
                }
                fitted[item.translated] = Fitted(rw, rh, size / s, r.width() / s)
            }
            if (l.height > r.height()) r.bottom = r.top + l.height + 2 * pad
            out += Drawn(r, l, i)
        }
        drawn = out
    }

    private fun makeLayout(text: String, size: Float, width: Int): StaticLayout {
        val p = TextPaint(textPaint).apply { textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    // ---------------------------------------------------------------- Drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dirty) layoutAll()
        val radius = 4f * density
        if (!showOriginal) {
            for (d in drawn) {
                if (d.index in peeked) continue
                val item = items.getOrNull(d.index) ?: continue
                bgPaint.color = item.background
                bgPaint.alpha = 245
                canvas.drawRoundRect(d.bounds, radius, radius, bgPaint)
                d.layout.paint.color = item.foreground
                canvas.save()
                canvas.translate(d.bounds.left + 3f * density, d.bounds.top + max(0f, (d.bounds.height() - d.layout.height) / 2f))
                d.layout.draw(canvas)
                canvas.restore()
            }
        }
        qrRect?.let {
            map(it, box)
            box.inset(-6f * density, -6f * density)
            qrPaint.color = accentColor
            canvas.drawRoundRect(box, 10f * density, 10f * density, qrPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return event.actionMasked == MotionEvent.ACTION_DOWN && hitTest(event) != null
        val hit = hitTest(event) ?: return false
        if (!peeked.add(hit.index)) peeked.remove(hit.index)
        invalidate()
        return true
    }

    private fun hitTest(e: MotionEvent): Drawn? = drawn.lastOrNull { it.bounds.contains(e.x, e.y) }

    companion object {
        private const val MIN_TEXT_SP = 9f
    }
}
