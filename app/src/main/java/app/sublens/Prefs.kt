package app.sublens

import android.content.Context
import android.content.SharedPreferences

/** All settings in one place. Changes take effect immediately, also in the running overlay. */
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

    /** true = translation right in the lens over the original text, false = in the bar. */
    var lensMode: Boolean
        get() = sp.getBoolean(K_LENS_MODE, true)
        set(value) = sp.edit().putBoolean(K_LENS_MODE, value).apply()

    var barAbove: Boolean
        get() = sp.getBoolean(K_BAR_ABOVE, false)
        set(value) = sp.edit().putBoolean(K_BAR_ABOVE, value).apply()

    /** Reply line above instead of below the lens. */
    var composerAbove: Boolean
        get() = sp.getBoolean(K_COMPOSER_ABOVE, false)
        set(value) = sp.edit().putBoolean(K_COMPOSER_ABOVE, value).apply()

    /** Colour of the frame, handles and move button. */
    var lensColor: Int
        get() = sp.getInt(K_LENS_COLOR, LENS_COLORS[0])
        set(value) = sp.edit().putInt(K_LENS_COLOR, value).apply()

    /**
     * Swaps From and To. [detected] is used as the source language when "From" is automatic.
     * false if that isn't possible (no language detected or the new source language can't be read).
     */
    fun swapLanguages(detected: String?): Boolean {
        val src = if (sourceLang == Languages.AUTO) detected ?: return false else sourceLang
        val tgt = targetLang
        if (src == tgt || tgt !in Languages.sources() || src !in Languages.targets()) return false
        sourceLang = tgt
        targetLang = src
        return true
    }

    /** Video mode: motion (video) is re-read continuously. Off = chat mode, animations are ignored. */
    var videoMode: Boolean
        get() = sp.getBoolean(K_VIDEO_MODE, false)
        set(value) = sp.edit().putBoolean(K_VIDEO_MODE, value).apply()

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

    /** Reply line next to the overlay (own language → chat's language, to the clipboard). */
    var composerEnabled: Boolean
        get() = sp.getBoolean(K_COMPOSER, true)
        set(value) = sp.edit().putBoolean(K_COMPOSER, value).apply()

    /** Reply language. AUTO = the language detected in the frame. */
    var replyLang: String
        get() = sp.getString(K_REPLY, Languages.AUTO) ?: Languages.AUTO
        set(value) = sp.edit().putString(K_REPLY, value).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean(K_ASKED_NOTIF, false)
        set(value) = sp.edit().putBoolean(K_ASKED_NOTIF, value).apply()

    // ---------------------------------------------------------------- Online translation

    /** Translate with an online AI service instead of the on-device packs (needs a key). */
    var onlineEnabled: Boolean
        get() = sp.getBoolean(K_ONLINE, false)
        set(value) = sp.edit().putBoolean(K_ONLINE, value).apply()

    var onlineProvider: String
        get() = sp.getString(K_PROVIDER, "zai") ?: "zai"
        set(value) = sp.edit().putString(K_PROVIDER, value).apply()

    /** Model chosen for a provider (picked automatically when the key is tested). */
    fun onlineModel(provider: String): String? = sp.getString("online_model_$provider", null)
    fun setOnlineModel(provider: String, model: String?) =
        sp.edit().apply { if (model.isNullOrBlank()) remove("online_model_$provider") else putString("online_model_$provider", model) }.apply()

    /** Server address for providers where it can be changed (own server, regional endpoint). */
    fun onlineUrl(provider: String): String? = sp.getString("online_url_$provider", null)
    fun setOnlineUrl(provider: String, url: String?) =
        sp.edit().apply { if (url.isNullOrBlank()) remove("online_url_$provider") else putString("online_url_$provider", url.trim().trimEnd('/')) }.apply()

    /** Last successful test per provider (shown as ✓ in the catalog). */
    fun onlineVerified(provider: String): Boolean = sp.getBoolean("online_ok_$provider", false)
    fun setOnlineVerified(provider: String, ok: Boolean) = sp.edit().putBoolean("online_ok_$provider", ok).apply()

    /** Top of the frame as a fraction of the screen height, separately for portrait and landscape. */
    fun frameTopFraction(landscape: Boolean): Float =
        sp.getFloat(if (landscape) K_TOP_LAND else K_TOP_PORT, if (landscape) 0.70f else 0.14f)

    /** Height of the frame as a fraction of the screen height (at least 1/5). */
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
        const val MAX_HEIGHT_FRACTION = 0.95f

        const val K_SOURCE = "source_lang"
        const val K_TARGET = "target_lang"
        const val K_SCRIPT = "auto_script"
        const val K_BAR_ABOVE = "bar_above"
        const val K_LENS_MODE = "lens_mode"
        const val K_INTERVAL = "interval_ms"
        const val K_TEXT_SIZE = "text_size_sp"
        const val K_SHOW_ORIGINAL = "show_original"
        const val K_WIFI_ONLY = "wifi_only"
        const val K_ASKED_NOTIF = "asked_notifications"
        const val K_COMPOSER = "composer_enabled"
        const val K_COMPOSER_ABOVE = "composer_above"
        const val K_LENS_COLOR = "lens_color"
        const val K_VIDEO_MODE = "video_mode"
        const val K_ONLINE = "online_enabled"
        const val K_PROVIDER = "online_provider"

        /** Teal, blue, purple, pink, red, orange, yellow, green, white */
        val LENS_COLORS = intArrayOf(
            0xFF5EEAD4.toInt(), 0xFF60A5FA.toInt(), 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFF87171.toInt(),
            0xFFFB923C.toInt(), 0xFFFACC15.toInt(), 0xFF4ADE80.toInt(), 0xFFF8FAFC.toInt(),
        )
        const val K_REPLY = "reply_lang"
        private const val K_TOP_PORT = "frame_top_port"
        private const val K_TOP_LAND = "frame_top_land"
        private const val K_HEIGHT_PORT = "frame_height_port"
        private const val K_HEIGHT_LAND = "frame_height_land"
    }
}
