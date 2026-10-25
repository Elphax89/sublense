package app.sublens

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log

/**
 * Google ML Kit translation only knows simplified Chinese.
 * SubLens shows Chinese in traditional characters: results are converted, and traditional
 * text is turned into simplified characters before translating (translates more accurately).
 * Needs Android 10 or newer, otherwise the text stays unchanged.
 */
object ChineseScript {
    private const val ZH = "zh"

    private val toTraditional: Transliterator? by lazy { create("Hans-Hant") }
    private val toSimplified: Transliterator? by lazy { create("Hant-Hans") }

    private fun create(id: String): Transliterator? =
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Transliterator.getInstance(id)
            } catch (e: Exception) {
                Log.w("SubLens", "Script conversion $id not available", e)
                null
            }
        } else {
            null
        }

    /** Before translating: source text, if Chinese, to simplified characters. */
    fun forInput(text: String, sourceLang: String): String =
        if (sourceLang == ZH) convert(toSimplified, text) else text

    /** After translating: result, if Chinese, to traditional characters. */
    fun forOutput(text: String, targetLang: String): String =
        if (targetLang == ZH) convert(toTraditional, text) else text

    @Synchronized
    private fun convert(t: Transliterator?, text: String): String =
        try {
            t?.transliterate(text) ?: text
        } catch (_: Exception) {
            text
        }
}
