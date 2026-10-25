package app.sublens

import android.icu.text.Transliterator
import android.os.Build
import android.util.Log

/**
 * Die Übersetzung von Google ML Kit kennt nur vereinfachtes Chinesisch.
 * SubLens zeigt Chinesisch traditionell: Ergebnisse werden umgewandelt, und traditioneller
 * Text wird vor dem Übersetzen in vereinfachte Zeichen gebracht (übersetzt so genauer).
 * Braucht Android 10 oder neuer, sonst bleibt der Text unverändert.
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
                Log.w("SubLens", "Zeichen-Umwandlung $id nicht verfügbar", e)
                null
            }
        } else {
            null
        }

    /** Vor dem Übersetzen: Quelltext, falls Chinesisch, in vereinfachte Zeichen. */
    fun forInput(text: String, sourceLang: String): String =
        if (sourceLang == ZH) convert(toSimplified, text) else text

    /** Nach dem Übersetzen: Ergebnis, falls Chinesisch, in traditionelle Zeichen. */
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
