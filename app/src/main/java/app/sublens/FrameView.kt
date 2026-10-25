package app.sublens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Der sichtbare Rahmen: dünne Linie + kräftige Ecken. Innen komplett durchsichtig,
 * damit vom Video nichts verdeckt wird.
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

    /** Dunkle Kontur unter den Ecken, damit sie auch auf hellem Video sichtbar bleiben. */
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

    /** Farbe des Rahmens (Einstellung „Farbe der Linse“). */
    fun setColor(color: Int) {
        accent = color
        linePaint.color = color
        linePaint.alpha = 130
        bracketPaint.color = color
        invalidate()
    }

    /** Leuchtet stärker, sobald im Rahmen Text erkannt wird. */
    var active: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Dieser Rand wird bei der Texterkennung ausgespart, damit der Rahmen selbst nicht stört. */
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
        // oben links
        path.moveTo(l, t + len)
        path.lineTo(l, t + rad)
        path.arcTo(l, t, l + 2 * rad, t + 2 * rad, 180f, 90f, false)
        path.lineTo(l + len, t)
        // oben rechts
        path.moveTo(rt - len, t)
        path.lineTo(rt - rad, t)
        path.arcTo(rt - 2 * rad, t, rt, t + 2 * rad, 270f, 90f, false)
        path.lineTo(rt, t + len)
        // unten rechts
        path.moveTo(rt, b - len)
        path.lineTo(rt, b - rad)
        path.arcTo(rt - 2 * rad, b - 2 * rad, rt, b, 0f, 90f, false)
        path.lineTo(rt - len, b)
        // unten links
        path.moveTo(l + len, b)
        path.lineTo(l + rad, b)
        path.arcTo(l, b - 2 * rad, l + 2 * rad, b, 90f, 90f, false)
        path.lineTo(l, b - len)
    }
}
