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

    /** Simplified Chinese. Plain "zh" stands for traditional Chinese (see ChineseScript). */
    const val ZH_HANS = "zh-Hans"

    /** The ML Kit language for one of our tags (both Chinese variants use the "zh" pack). */
    fun mlkit(tag: String): String = if (tag == ZH_HANS) "zh" else tag

    /** Both tags use the same translation pack (e.g. traditional and simplified Chinese). */
    fun samePack(a: String, b: String): Boolean = mlkit(a) == mlkit(b)

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
        (TranslateLanguage.getAllLanguages() + ZH_HANS).sortedBy { displayName(it).lowercase(Locale.getDefault()) }

    /** Source languages: Auto + everything text recognition can read. */
    fun sources(): List<String> = listOf(AUTO) + targets().filter { it !in NO_OCR }

    fun displayName(tag: String): String {
        if (tag == AUTO) return autoLabel
        // Chinese is shown in traditional characters (see ChineseScript).
        if (tag == "zh" || tag == ZH_HANS) {
            val n = Locale.forLanguageTag(if (tag == ZH_HANS) "zh-Hans" else "zh-Hant").getDisplayName(Locale.getDefault())
            if (n.isNotBlank()) return n.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        if (name.isBlank() || name.equals(tag, ignoreCase = true)) return tag.uppercase(Locale.ROOT)
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    /** A flag for the language pickers (languages aren't countries – the most common one, or 🌐). */
    fun flag(tag: String): String = FLAGS[tag] ?: "🌐"

    private val FLAGS = mapOf(
        AUTO to "🌐", "af" to "🇿🇦", "ar" to "🇸🇦", "be" to "🇧🇾", "bg" to "🇧🇬", "bn" to "🇧🇩", "ca" to "🇦🇩",
        "cs" to "🇨🇿", "cy" to "🏴󠁧󠁢󠁷󠁬󠁳󠁿", "da" to "🇩🇰", "de" to "🇩🇪", "el" to "🇬🇷", "en" to "🇬🇧", "es" to "🇪🇸",
        "et" to "🇪🇪", "fa" to "🇮🇷", "fi" to "🇫🇮", "fr" to "🇫🇷", "ga" to "🇮🇪", "gl" to "🇪🇸", "gu" to "🇮🇳",
        "he" to "🇮🇱", "hi" to "🇮🇳", "hr" to "🇭🇷", "ht" to "🇭🇹", "hu" to "🇭🇺", "id" to "🇮🇩", "is" to "🇮🇸",
        "it" to "🇮🇹", "ja" to "🇯🇵", "ka" to "🇬🇪", "kn" to "🇮🇳", "ko" to "🇰🇷", "lt" to "🇱🇹", "lv" to "🇱🇻",
        "mk" to "🇲🇰", "mr" to "🇮🇳", "ms" to "🇲🇾", "mt" to "🇲🇹", "nl" to "🇳🇱", "no" to "🇳🇴", "pl" to "🇵🇱",
        "pt" to "🇧🇷", "ro" to "🇷🇴", "ru" to "🇷🇺", "sk" to "🇸🇰", "sl" to "🇸🇮", "sq" to "🇦🇱", "sv" to "🇸🇪",
        "sw" to "🇰🇪", "ta" to "🇱🇰", "te" to "🇮🇳", "th" to "🇹🇭", "tl" to "🇵🇭", "tr" to "🇹🇷", "uk" to "🇺🇦",
        "ur" to "🇵🇰", "vi" to "🇻🇳", "zh" to "🇭🇰", ZH_HANS to "🇨🇳",
    )

    fun shortCode(tag: String): String = when (tag) {
        AUTO -> "AUTO"
        "zh" -> "ZH繁"
        ZH_HANS -> "ZH简"
        else -> tag.uppercase(Locale.ROOT)
    }

    /** Name of a downloaded translation pack ("zh" covers both Chinese variants). */
    fun packName(mlkitTag: String): String =
        if (mlkitTag == "zh") {
            Locale.forLanguageTag("zh").getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
        } else {
            displayName(mlkitTag)
        }

    /** Which text recognizer fits a fixed source language (null = Auto). */
    fun scriptFor(tag: String): Script? = when (tag) {
        AUTO -> null
        "zh", ZH_HANS -> Script.CHINESE
        "ja" -> Script.JAPANESE
        "ko" -> Script.KOREAN
        "hi", "mr" -> Script.DEVANAGARI
        else -> Script.LATIN
    }

    /** Phone language if translatable, otherwise English. */
    fun defaultTarget(): String {
        val locale = Locale.getDefault()
        if (locale.language == "zh") {
            val simplified = locale.script == "Hans" ||
                (locale.script.isEmpty() && locale.country in setOf("CN", "SG", "MY"))
            return if (simplified) ZH_HANS else "zh"
        }
        return TranslateLanguage.fromLanguageTag(locale.language) ?: TranslateLanguage.ENGLISH
    }

    /** Map a language-identification result to a translatable language (null = unknown). */
    fun normalizeDetected(tag: String?): String? {
        if (tag == null || tag == "und" || tag.contains("-Latn", ignoreCase = true)) return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))
    }

    /** Like [normalizeDetected], but tells simplified from traditional Chinese by looking at [text]. */
    fun detectedFor(tag: String?, text: String): String? {
        val lang = normalizeDetected(tag) ?: return null
        return if (lang == "zh") ChineseScript.variantOf(text) ?: lang else lang
    }
}
