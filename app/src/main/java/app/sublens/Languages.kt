package app.sublens

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Schriftsysteme, die die Texterkennung (ML Kit) lesen kann. */
enum class Script(val key: String, val label: String) {
    LATIN("latin", "Lateinisch (EN, DE, FR, ES, IT …)"),
    CHINESE("chinese", "Chinesisch"),
    JAPANESE("japanese", "Japanisch"),
    KOREAN("korean", "Koreanisch"),
    DEVANAGARI("devanagari", "Devanagari (Hindi, Marathi)");

    /** Chinesisch und Japanisch trennen Wörter nicht mit Leerzeichen. */
    val joinsWithoutSpace: Boolean get() = this == CHINESE || this == JAPANESE

    companion object {
        fun fromKey(key: String?): Script = entries.firstOrNull { it.key == key } ?: LATIN
    }
}

object Languages {
    const val AUTO = "auto"

    /** Sprachen, deren Schrift die Texterkennung nicht lesen kann – nur als Zielsprache nutzbar. */
    private val NO_OCR = setOf(
        "ar", "be", "bg", "bn", "el", "fa", "gu", "he", "ka",
        "kn", "mk", "ru", "ta", "te", "th", "uk", "ur",
    )

    /** Alle Sprachen, in die übersetzt werden kann, alphabetisch nach Anzeigename. */
    fun targets(): List<String> =
        TranslateLanguage.getAllLanguages().sortedBy { displayName(it).lowercase(Locale.getDefault()) }

    /** Quellsprachen: Auto + alles, was die Texterkennung lesen kann. */
    fun sources(): List<String> = listOf(AUTO) + targets().filter { it !in NO_OCR }

    fun displayName(tag: String): String {
        if (tag == AUTO) return "Automatisch erkennen"
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        if (name.isBlank() || name.equals(tag, ignoreCase = true)) return tag.uppercase(Locale.ROOT)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    fun shortCode(tag: String): String = if (tag == AUTO) "AUTO" else tag.uppercase(Locale.ROOT)

    /** Welche Texterkennung zu einer fest gewählten Quellsprache passt (null = Auto). */
    fun scriptFor(tag: String): Script? = when (tag) {
        AUTO -> null
        "zh" -> Script.CHINESE
        "ja" -> Script.JAPANESE
        "ko" -> Script.KOREAN
        "hi", "mr" -> Script.DEVANAGARI
        else -> Script.LATIN
    }

    /** Gerätesprache, falls übersetzbar, sonst Deutsch. */
    fun defaultTarget(): String =
        TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.GERMAN

    /** Ergebnis der Spracherkennung in eine übersetzbare Sprache umwandeln (null = unbekannt). */
    fun normalizeDetected(tag: String?): String? {
        if (tag == null || tag == "und" || tag.contains("-Latn", ignoreCase = true)) return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }
}
