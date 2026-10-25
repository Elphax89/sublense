package app.sublens

import android.content.Context
import android.content.SharedPreferences

/** Alle Einstellungen an einem Ort. Änderungen wirken sofort, auch im laufenden Overlay. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var sourceLang: String
        get() = sp.getString(K_SOURCE, Languages.AUTO) ?: Languages.AUTO
        set(value) = sp.edit().putString(K_SOURCE, value).apply()

    var targetLang: String
        get() = sp.getString(K_TARGET, null) ?: Languages.defaultTarget()
        set(value) = sp.edit().putString(K_TARGET, value).apply()

    var autoScript: Script
        get() = Script.fromKey(sp.getString(K_SCRIPT, null))
        set(value) = sp.edit().putString(K_SCRIPT, value.key).apply()

    var barAbove: Boolean
        get() = sp.getBoolean(K_BAR_ABOVE, false)
        set(value) = sp.edit().putBoolean(K_BAR_ABOVE, value).apply()

    var intervalMs: Long
        get() = sp.getLong(K_INTERVAL, 500L)
        set(value) = sp.edit().putLong(K_INTERVAL, value).apply()

    var textSizeSp: Int
        get() = sp.getInt(K_TEXT_SIZE, 18)
        set(value) = sp.edit().putInt(K_TEXT_SIZE, value).apply()

    var showOriginal: Boolean
        get() = sp.getBoolean(K_SHOW_ORIGINAL, false)
        set(value) = sp.edit().putBoolean(K_SHOW_ORIGINAL, value).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean(K_WIFI_ONLY, false)
        set(value) = sp.edit().putBoolean(K_WIFI_ONLY, value).apply()

    /** Antwortzeile unter dem Overlay (eigene Sprache → Sprache des Chats, in die Zwischenablage). */
    var composerEnabled: Boolean
        get() = sp.getBoolean(K_COMPOSER, true)
        set(value) = sp.edit().putBoolean(K_COMPOSER, value).apply()

    /** Sprache der Antwort. AUTO = wie die erkannte Sprache im Rahmen. */
    var replyLang: String
        get() = sp.getString(K_REPLY, Languages.AUTO) ?: Languages.AUTO
        set(value) = sp.edit().putString(K_REPLY, value).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean(K_ASKED_NOTIF, false)
        set(value) = sp.edit().putBoolean(K_ASKED_NOTIF, value).apply()

    /** Oberkante des Rahmens als Anteil der Bildschirmhöhe, getrennt für Hoch- und Querformat. */
    fun frameTopFraction(landscape: Boolean): Float =
        sp.getFloat(if (landscape) K_TOP_LAND else K_TOP_PORT, if (landscape) 0.70f else 0.14f)

    /** Höhe des Rahmens als Anteil der Bildschirmhöhe (mindestens 1/5). */
    fun frameHeightFraction(landscape: Boolean): Float =
        sp.getFloat(if (landscape) K_HEIGHT_LAND else K_HEIGHT_PORT, MIN_HEIGHT_FRACTION)

    fun saveFrame(landscape: Boolean, topFraction: Float, heightFraction: Float) {
        sp.edit()
            .putFloat(if (landscape) K_TOP_LAND else K_TOP_PORT, topFraction)
            .putFloat(if (landscape) K_HEIGHT_LAND else K_HEIGHT_PORT, heightFraction)
            .apply()
    }

    companion object {
        const val NAME = "sublens"
        const val MIN_HEIGHT_FRACTION = 0.2f
        const val MAX_HEIGHT_FRACTION = 0.6f

        const val K_SOURCE = "source_lang"
        const val K_TARGET = "target_lang"
        const val K_SCRIPT = "auto_script"
        const val K_BAR_ABOVE = "bar_above"
        const val K_INTERVAL = "interval_ms"
        const val K_TEXT_SIZE = "text_size_sp"
        const val K_SHOW_ORIGINAL = "show_original"
        const val K_WIFI_ONLY = "wifi_only"
        const val K_ASKED_NOTIF = "asked_notifications"
        const val K_COMPOSER = "composer_enabled"
        const val K_REPLY = "reply_lang"
        private const val K_TOP_PORT = "frame_top_port"
        private const val K_TOP_LAND = "frame_top_land"
        private const val K_HEIGHT_PORT = "frame_height_port"
        private const val K_HEIGHT_LAND = "frame_height_land"
    }
}
