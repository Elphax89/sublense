package app.sublens

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sucht auf GitHub nach einer neueren Version und installiert sie über den
 * Paket-Installer von Android. Android fragt dabei immer selbst nach einer Bestätigung.
 */
object Updater {

    private const val TAG = "SubLens"
    private const val LATEST_URL = "https://api.github.com/repos/Elphax89/sublense/releases/latest"
    private const val ASSET_NAME = "SubLens.apk"
    private const val PREFS = "updater"
    private const val K_LAST_CHECK = "last_check"
    private const val K_FOUND_CODE = "found_code"
    private const val K_FOUND_NAME = "found_name"
    private const val K_FOUND_URL = "found_url"
    private const val AUTO_CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    class Release(val versionCode: Long, val versionName: String, val apkUrl: String)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var downloading = false
        private set

    fun installedVersionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedVersionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** Zuletzt gefundenes Update, falls noch neuer als die installierte Version. */
    fun knownUpdate(ctx: Context): Release? {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = sp.getLong(K_FOUND_CODE, 0L)
        val url = sp.getString(K_FOUND_URL, null) ?: return null
        if (code <= installedVersionCode(ctx)) return null
        return Release(code, sp.getString(K_FOUND_NAME, "") ?: "", url)
    }

    /**
     * Fragt GitHub nach der neuesten Version. [force] = auch wenn kürzlich schon gefragt wurde.
     * [onResult] läuft auf dem Main-Thread: Release = Update da, null = aktuell, Exception = Fehler.
     */
    fun check(ctx: Context, force: Boolean, onResult: (Release?, Exception?) -> Unit) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong(K_LAST_CHECK, 0L) < AUTO_CHECK_EVERY_MS) {
            onResult(knownUpdate(app), null)
            return
        }
        Thread {
            try {
                val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SubLens-App")
                }
                val body = try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }
                val json = JSONObject(body)
                // Releases heißen "build-<Nummer>", die Nummer ist der versionCode.
                val code = json.optString("tag_name").substringAfterLast('-').toLongOrNull() ?: 0L
                val name = json.optString("name").substringAfterLast(' ').ifBlank { code.toString() }
                var apkUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == ASSET_NAME) apkUrl = a.optString("browser_download_url")
                    }
                }
                sp.edit().putLong(K_LAST_CHECK, now).apply()
                val release = if (apkUrl != null && code > installedVersionCode(app)) {
                    sp.edit().putLong(K_FOUND_CODE, code).putString(K_FOUND_NAME, name).putString(K_FOUND_URL, apkUrl).apply()
                    Release(code, name, apkUrl)
                } else {
                    null
                }
                main.post { onResult(release, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Update-Prüfung fehlgeschlagen", e)
                main.post { onResult(null, e) }
            }
        }.start()
    }

    /**
     * Lädt die APK direkt in eine Installations-Sitzung und übergibt sie an Android.
     * [onProgress] (0–100) und [onDone] laufen auf dem Main-Thread.
     */
    fun downloadAndInstall(ctx: Context, release: Release, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        if (downloading) return
        downloading = true
        val app = ctx.applicationContext
        Thread {
            var sessionId = -1
            val installer = app.packageManager.packageInstaller
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "SubLens-App")
                    }
                    try {
                        if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                        val total = conn.contentLengthLong
                        conn.inputStream.use { input ->
                            session.openWrite(ASSET_NAME, 0, if (total > 0) total else -1).use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                var lastPct = -1
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            main.post { onProgress(pct) }
                                        }
                                    }
                                }
                                session.fsync(out)
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                    val callback = PendingIntent.getBroadcast(
                        app,
                        sessionId,
                        Intent(app, InstallReceiver::class.java),
                        flags,
                    )
                    session.commit(callback.intentSender)
                }
                main.post {
                    downloading = false
                    onDone(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update fehlgeschlagen", e)
                if (sessionId >= 0) {
                    try {
                        installer.abandonSession(sessionId)
                    } catch (_: Exception) {
                    }
                }
                main.post {
                    downloading = false
                    onDone(false)
                }
            }
        }.start()
    }

    /** Antwort des Paket-Installers: Bestätigungsdialog anzeigen oder Fehler melden. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(confirm)
                        } catch (e: Exception) {
                            Log.w(TAG, "Installationsdialog nicht startbar", e)
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit // App startet neu
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // abgebrochen
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "Installation fehlgeschlagen: $msg")
                    Toast.makeText(context, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
