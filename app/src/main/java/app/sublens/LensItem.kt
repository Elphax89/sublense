package app.sublens

import android.graphics.Rect

/** Ein übersetzter Textblock, in Koordinaten des Rahmen-Ausschnitts. */
class LensItem(
    val rect: Rect,
    val original: String,
    val translated: String,
    val background: Int,
    val foreground: Int,
    val lineHeightPx: Int,
) {
    /** Nur Blöcke, deren Übersetzung sich vom Original unterscheidet, werden überdeckt. */
    val changed: Boolean
        get() = normalize(translated) != normalize(original)

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
