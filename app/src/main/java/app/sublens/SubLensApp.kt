package app.sublens

import android.app.Application
import android.content.Context

/** Only here so the app context (used by the overlay and the translators) speaks the chosen app language. */
class SubLensApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }
}
