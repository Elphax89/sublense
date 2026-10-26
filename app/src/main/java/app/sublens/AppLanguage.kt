package app.sublens

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The language of SubLens itself (independent of the phone's language).
 * Android 13+: the system's per-app language (also shown in Settings → Apps → SubLens → Language).
 * Older phones: stored in the settings and applied to every activity, the service and the app context.
 */
object AppLanguage {

    class Option(val tag: String, val flag: String, val nativeName: String)

    /** "" = follow the phone. One entry per translated resource folder. */
    val options = listOf(
        Option("", "📱", ""),
        Option("en", "🇬🇧", "English"),
        Option("de", "🇩🇪", "Deutsch"),
        Option("es", "🇪🇸", "Español"),
        Option("fr", "🇫🇷", "Français"),
        Option("it", "🇮🇹", "Italiano"),
        Option("pt", "🇧🇷", "Português"),
        Option("pl", "🇵🇱", "Polski"),
        Option("tr", "🇹🇷", "Türkçe"),
        Option("ru", "🇷🇺", "Русский"),
        Option("zh-Hans", "🇨🇳", "简体中文"),
        Option("zh-Hant", "🇭🇰", "繁體中文"),
        Option("ja", "🇯🇵", "日本語"),
        Option("ko", "🇰🇷", "한국어"),
    )

    private const val KEY = "app_language"

    /** The chosen language tag, "" when SubLens follows the phone. */
    fun chosen(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (list == null || list.isEmpty) return ""
            return match(list[0])?.tag ?: list[0].toLanguageTag()
        }
        return context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    }

    /** The option that is actually shown right now (for "follow the phone": the phone's language, else English). */
    fun current(context: Context): Option {
        val tag = chosen(context)
        if (tag.isNotEmpty()) options.firstOrNull { it.tag == tag }?.let { return it }
        return match(context.resources.configuration.locales[0]) ?: options[1]
    }

    /** Short code for the header chip, e.g. "DE", "简", "繁". */
    fun shortCode(o: Option): String = when (o.tag) {
        "zh-Hans" -> "简"
        "zh-Hant" -> "繁"
        else -> o.tag.uppercase(Locale.ROOT)
    }

    private fun match(locale: Locale): Option? {
        if (locale.language == "zh") {
            val traditional = locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
            return options.first { it.tag == if (traditional) "zh-Hant" else "zh-Hans" }
        }
        return options.firstOrNull { it.tag == locale.language }
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the open screens with the new language.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return
        }
        activity.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        if (tag.isEmpty()) Locale.setDefault(android.content.res.Resources.getSystem().configuration.locales[0])
        activity.recreate()
    }

    /** For attachBaseContext (Android 12 and older). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
