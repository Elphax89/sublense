package app.sublens

import android.graphics.Rect

/** A translated text block, in coordinates of the lens crop. */
class LensItem(
    val rect: Rect,
    val original: String,
    val translated: String,
    val background: Int,
    val foreground: Int,
    val lineHeightPx: Int,
) {
    /** Only blocks whose translation differs from the original are covered. */
    val changed: Boolean
        get() = normalize(translated) != normalize(original)

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
