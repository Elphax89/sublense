package app.sublens

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Scripts that text recognition (ML Kit) can read. */
enum class Script(val key: String, val labelRes: Int) {
    LATIN("latin", R.string.script_latin),
    CHINESE("chinese", R.string.script_chinese),
    JAPANESE("japanese", R.string.script_japanese),
    KOREAN("korean", R.string.script_korean),
    DEVANAGARI("devanagari", R.string.script_devanagari);

    /** Chinese and Japanese don't separate words with spaces. */
    val joinsWithoutSpace: Boolean get() = this == CHINESE || this == JAPANESE

    companion object {
        fun fromKey(key: String?): Script = entries.firstOrNull { it.key == key } ?: LATIN
    }
}

object Languages {
    const val AUTO = "auto"

    /** Display name for "Detect automatically" in the phone's language – set from resources at startup. */
    @Volatile
    var autoLabel = "Auto"

    /** Languages whose script text recognition can't read – usable as target language only. */
    private val NO_OCR = setOf(
        "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "ka",
        "kn", "mk", "ru", "ta", "te", "th", "uk", "ur",
    )

    /** All languages that can be translated into, sorted by display name. */
    fun targets(): List<String> =
        TranslateLanguage.getAllLanguages().sortedBy { displayName(it).lowercase(Locale.getDefault()) }

    /** Source languages: Auto + everything text recognition can read. */
    fun sources(): List<String> = listOf(AUTO) + targets().filter { it !in NO_OCR }

    fun displayName(tag: String): String {
        if (tag == AUTO) return autoLabel
        // Chinese is shown in traditional characters (see ChineseScript).
        if (tag == "zh") {
            val n = Locale.forLanguageTag("zh-Hant").getDisplayName(Locale.getDefault())
            if (n.isNotBlank()) return n.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        if (name.isBlank() || name.equals(tag, ignoreCase = true)) return tag.uppercase(Locale.ROOT)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    fun shortCode(tag: String): String = if (tag == AUTO) "AUTO" else tag.uppercase(Locale.ROOT)

    /** Which text recognizer fits a fixed source language (null = Auto). */
    fun scriptFor(tag: String): Script? = when (tag) {
        AUTO -> null
        "zh" -> Script.CHINESE
        "ja" -> Script.JAPANESE
        "ko" -> Script.KOREAN
        "hi", "mr" -> Script.DEVANAGARI
        else -> Script.LATIN
    }

    /** Phone language if translatable, otherwise English. */
    fun defaultTarget(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

    /** Map a language-identification result to a translatable language (null = unknown). */
    fun normalizeDetected(tag: String?): String? {
        if (tag == null || tag == "und" || tag.contains("-Latn", ignoreCase = true)) return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }
}
