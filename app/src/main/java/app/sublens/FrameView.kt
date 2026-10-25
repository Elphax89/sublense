package app.sublens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * The visible frame: thin line + bold corners. Fully transparent inside,
 * so nothing of the video is covered.
 */
class FrameView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val radius = 16f * density
    private val bracketLength = 26f * density
    private var accent = context.getColor(R.color.accent)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = accent
        alpha = 130
    }

    /** Dark outline under the corners so they stay visible on bright video. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
        alpha = 90
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
    }

    private val rect = RectF()
    private val path = Path()

    /** Frame colour (setting "Lens colour"). */
    fun setColor(color: Int) {
        accent = color
        linePaint.color = color
        linePaint.alpha = 130
        bracketPaint.color = color
        invalidate()
    }

    /** Glows brighter as soon as text is recognised inside the frame. */
    var active: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** This margin is left out of text recognition so the frame itself doesn't interfere. */
    val insetPx: Int get() = (8f * density).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = shadowPaint.strokeWidth / 2f
        rect.set(half, half, width - half, height - half)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        canvas.drawRoundRect(rect, radius, radius, linePaint)

        buildBracketPath(rect)
        canvas.drawPath(path, shadowPaint)
        bracketPaint.alpha = if (active) 255 else 175
        canvas.drawPath(path, bracketPaint)
    }

    private fun buildBracketPath(r: RectF) {
        val l = r.left
        val t = r.top
        val rt = r.right
        val b = r.bottom
        val rad = radius
        val len = bracketLength.coerceAtMost(minOf(r.width(), r.height()) / 2f)
        path.reset()
        // top left
        path.moveTo(l, t + len)
        path.lineTo(l, t + rad)
        path.arcTo(l, t, l + 2 * rad, t + 2 * rad, 180f, 90f, false)
        path.lineTo(l + len, t)
        // top right
        path.moveTo(rt - len, t)
        path.lineTo(rt - rad, t)
        path.arcTo(rt - 2 * rad, t, rt, t + 2 * rad, 270f, 90f, false)
        path.lineTo(rt, t + len)
        // bottom right
        path.moveTo(rt, b - len)
        path.lineTo(rt, b - rad)
        path.arcTo(rt - 2 * rad, b - 2 * rad, rt, b, 0f, 90f, false)
        path.lineTo(rt - len, b)
        // bottom left
        path.moveTo(l + len, b)
        path.lineTo(l + rad, b)
        path.arcTo(l, b - 2 * rad, l + 2 * rad, b, 90f, 90f, false)
        path.lineTo(l, b - len)
    }
}
