package app.sublens

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log

/**
 * Google ML Kit translation only knows simplified Chinese.
 * SubLens offers Chinese in traditional ("zh") and simplified ("zh-Hans") characters: results for "zh"
 * are converted to traditional, and traditional text is turned into simplified characters before
 * translating (translates more accurately). Needs Android 10 or newer, otherwise the text stays unchanged.
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

    /**
     * Which variant a Chinese text is written in: "zh-Hans" (simplified), "zh" (traditional),
     * or null if it can't be told (characters shared by both, or Android older than 10).
     */
    fun variantOf(text: String): String? {
        val trad = toTraditional ?: return null
        val simp = toSimplified ?: return null
        val asTraditional = convert(trad, text)
        val asSimplified = convert(simp, text)
        val simplifiedChars = text.indices.count { it < asTraditional.length && text[it] != asTraditional[it] }
        val traditionalChars = text.indices.count { it < asSimplified.length && text[it] != asSimplified[it] }
        return when {
            simplifiedChars > traditionalChars -> Languages.ZH_HANS
            traditionalChars > simplifiedChars -> ZH
            else -> null
        }
    }

    /** Text in the right characters for [targetLang] (for results that may come in either script). */
    fun inScriptOf(text: String, targetLang: String): String = when (targetLang) {
        ZH -> convert(toTraditional, text)
        Languages.ZH_HANS -> convert(toSimplified, text)
        else -> text
    }

    /** Traditional ↔ simplified without a translation model (both variants share one ML Kit pack). */
    fun convertBetween(text: String, from: String, to: String): String = forOutput(forInput(text, from), to)

    @Synchronized
    private fun convert(t: Transliterator?, text: String): String =
        try {
            t?.transliterate(text) ?: text
        } catch (_: Exception) {
            text
        }
}
