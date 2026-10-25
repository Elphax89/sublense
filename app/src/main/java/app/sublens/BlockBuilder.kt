package app.sublens

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A recognised text block with its position (bitmap pixels), line height and colours. */
class TextBlock(
    val text: String,
    val rect: Rect,
    val lineHeight: Int,
    val background: Int,
    val foreground: Int,
)

/** Turns ML Kit text into blocks – shared by the screen lens and the camera. */
object BlockBuilder {

    fun options(script: Script): TextRecognizerOptionsInterface = when (script) {
        Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
        Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        Script.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
    }

    /**
     * Lines of a block are joined into one sentence; blocks (paragraphs, speakers,
     * chat messages, menu items) stay separate. Lines without letters (times, prices, symbols) are dropped.
     * [skipEdgeLines]: drop lines cut off by the top or bottom edge of [bitmap] (screen lens).
     */
    fun build(text: Text, script: Script, bitmap: Bitmap, skipEdgeLines: Boolean): List<TextBlock> {
        val separator = if (script.joinsWithoutSpace) "" else " "
        val blocks = ArrayList<TextBlock>()
        for (block in text.textBlocks) {
            // An ML Kit block can contain a name (coloured, different size), a quote and the message.
            // Only join lines when font size, colours and spacing match –
            // otherwise each part gets its own box with its own font size.
            var group = ArrayList<LineInfo>()
            for (line in block.lines) {
                if (line.text.count { ch -> ch.isLetter() } < 2) continue
                val box = line.boundingBox ?: continue
                if (box.isEmpty) continue
                // Line cut off by the lens edge: only half readable – skip it.
                if (skipEdgeLines && (box.top <= 1 || box.bottom >= bitmap.height - 2)) continue
                val (bg, fg) = sampleColors(bitmap, box)
                val info = LineInfo(line.text.trim(), Rect(box), bg, fg)
                if (group.isNotEmpty() && !fitsGroup(group, info)) {
                    addGroup(group, separator, blocks)
                    group = ArrayList()
                }
                group += info
            }
            addGroup(group, separator, blocks)
        }
        blocks.sortWith(compareBy({ it.rect.top }, { it.rect.left }))
        return blocks
    }

    private class LineInfo(val text: String, val rect: Rect, val bg: Int, val fg: Int)

    private fun fitsGroup(group: List<LineInfo>, line: LineInfo): Boolean {
        val avg = group.sumOf { it.rect.height() } / group.size.toFloat()
        val h = line.rect.height().toFloat()
        if (max(avg, h) / max(1f, min(avg, h)) > 1.3f) return false
        if (line.rect.top - group.last().rect.bottom > avg * 0.9f) return false
        if (colorDistance(line.fg, group.first().fg) > 150) return false
        if (colorDistance(line.bg, group.first().bg) > 90) return false
        return true
    }

    private fun addGroup(group: List<LineInfo>, separator: String, out: MutableList<TextBlock>) {
        if (group.isEmpty()) return
        val joined = group.joinToString(separator) { it.text }.trim()
        if (joined.length < 2) return
        val rect = Rect(group[0].rect)
        for (l in group) rect.union(l.rect)
        val lineHeight = group.sumOf { it.rect.height() } / group.size
        out += TextBlock(joined, rect, lineHeight, group[0].bg, group[0].fg)
    }

    // ---------------------------------------------------------------- Colours

    /**
     * Background = most common colour right around the text (median of the ring),
     * text = the pixels in the block that stand out most from the background.
     */
    fun sampleColors(bitmap: Bitmap, r: Rect): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val pad = 3
        val ring = ArrayList<Int>(96)
        val left = (r.left - pad).coerceIn(0, w - 1)
        val right = (r.right + pad).coerceIn(0, w - 1)
        val top = (r.top - pad).coerceIn(0, h - 1)
        val bottom = (r.bottom + pad).coerceIn(0, h - 1)
        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 8)
        var x = left
        while (x <= right) {
            ring += bitmap.getPixel(x, top)
            ring += bitmap.getPixel(x, bottom)
            x += stepX
        }
        var y = top
        while (y <= bottom) {
            ring += bitmap.getPixel(left, y)
            ring += bitmap.getPixel(right, y)
            y += stepY
        }
        val bg = medianColor(ring)

        val inner = ArrayList<Int>(400)
        val il = r.left.coerceIn(0, w - 1)
        val ir = r.right.coerceIn(0, w - 1)
        val iTop = r.top.coerceIn(0, h - 1)
        val ib = r.bottom.coerceIn(0, h - 1)
        val sx = max(1, (ir - il) / 40)
        val sy = max(1, (ib - iTop) / 10)
        y = iTop
        while (y <= ib) {
            x = il
            while (x <= ir) {
                inner += bitmap.getPixel(x, y)
                x += sx
            }
            y += sy
        }
        val sorted = inner.sortedByDescending { colorDistance(it, bg) }
        val take = max(1, sorted.size / 8)
        var rs = 0
        var gs = 0
        var bs = 0
        for (i in 0 until take) {
            val c = sorted[i]
            rs += Color.red(c)
            gs += Color.green(c)
            bs += Color.blue(c)
        }
        var fg = Color.rgb(rs / take, gs / take, bs / take)
        if (colorDistance(fg, bg) < 90) {
            fg = if (luminance(bg) > 0.55f) Color.BLACK else Color.WHITE
        }
        return Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg)) to fg
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK
        val r = colors.map { Color.red(it) }.sorted()[colors.size / 2]
        val g = colors.map { Color.green(it) }.sorted()[colors.size / 2]
        val b = colors.map { Color.blue(it) }.sorted()[colors.size / 2]
        return Color.rgb(r, g, b)
    }

    fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
}
