package app.sublens

import android.app.Application
import android.content.Context
import android.content.res.Resources

/** Only here so the app context (used by the overlay and the translators) speaks the chosen app language. */
class SubLensApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    private val localized = AppLanguage.Cache()

    // The app context outlives a language change (Android 12 and older), so follow it here.
    override fun getResources(): Resources = baseContext?.let { localized.get(it, super.getResources()) } ?: super.getResources()
}
