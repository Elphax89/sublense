package app.sublens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Vordergrund-Dienst: hält die Bildschirmfreigabe, zeigt das Overlay und
 * schickt in festen Abständen den Rahmen-Ausschnitt an die Texterkennung.
 */
class OverlayService : Service(), OverlayController.Callbacks, TextPipeline.Listener {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var overlay: OverlayController? = null
    private var capturer: ScreenCapturer? = null
    private var pipeline: TextPipeline? = null
    private var replyTranslator: ReplyTranslator? = null
    private var lastDetectedSource: String? = null
    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    @Volatile
    private var cropRect: Rect? = null
    private var lastTextAt = 0L
    private var stopped = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPrefChanged(key) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> {
                // Pflicht ab Android 14: erst Vordergrund mit Typ "mediaProjection", dann Freigabe holen.
                try {
                    startInForeground()
                } catch (e: Exception) {
                    Log.e(TAG, "Vordergrund-Start fehlgeschlagen", e)
                    shutdown()
                    return START_NOT_STICKY
                }
                if (isRunning) return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = projectionData(intent)
                if (data == null || !Settings.canDrawOverlays(this)) {
                    shutdown()
                    return START_NOT_STICKY
                }
                try {
                    begin(code, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Start fehlgeschlagen", e)
                    Toast.makeText(this, R.string.toast_start_failed, Toast.LENGTH_LONG).show()
                    shutdown()
                }
            }
            else -> if (!isRunning) stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("Keine Bildschirmfreigabe")
        projection = proj

        val dm = getSystemService(DisplayManager::class.java)
        displayManager = dm
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: throw IllegalStateException("Kein Display")
        val size = realSize(display)

        overlay = OverlayController(uiContext(display), prefs, this).also { it.show(size.x, size.y) }
        pipeline = TextPipeline(this, prefs, this)
        replyTranslator = ReplyTranslator(prefs)
        overlay?.setReplyLanguages(prefs.targetLang, replyLanguage())
        val cap = ScreenCapturer(proj) { main.post { shutdown() } }
        capturer = cap
        cap.start(size.x, size.y, resources.displayMetrics.densityDpi)

        isRunning = true
        stopped = false
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        registerDisplayListener(dm)
        cap.handler.postDelayed(tick, FIRST_TICK_DELAY_MS)
    }

    /** Kontext für Overlay-Fenster (ab Android 11 ein eigener Fenster-Kontext). */
    private fun uiContext(display: Display): Context {
        val base: Context = if (Build.VERSION.SDK_INT >= 30) {
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            this
        }
        return ContextThemeWrapper(base, R.style.Theme_SubLens_Overlay)
    }

    @Suppress("DEPRECATION")
    private fun realSize(display: Display): Point = Point().also { display.getRealSize(it) }

    private fun registerDisplayListener(dm: DisplayManager) {
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onScreenChanged()
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, main)
    }

    /** Gedreht oder Auflösung geändert: Overlay neu anordnen, Aufnahme anpassen. */
    private fun onScreenChanged() {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val size = realSize(display)
        overlay?.setScreen(size.x, size.y)
        capturer?.resize(size.x, size.y, resources.displayMetrics.densityDpi)
    }

    /** Läuft auf dem Aufnahme-Thread. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val cap = capturer ?: return
            val pipe = pipeline ?: return
            val rect = cropRect
            if (rect != null && !pipe.busy) {
                val bitmap = cap.grab(rect)
                if (bitmap != null) {
                    pipe.busy = true
                    main.post {
                        val p = pipeline
                        if (p != null) p.process(bitmap) else bitmap.recycle()
                    }
                }
            }
            cap.handler.postDelayed(this, prefs.intervalMs.coerceIn(200L, 3000L))
        }
    }

    private fun onPrefChanged(key: String?) {
        overlay?.applyStyle()
        when (key) {
            Prefs.K_BAR_ABOVE -> overlay?.relayout()
            Prefs.K_COMPOSER -> overlay?.setComposerEnabled(prefs.composerEnabled)
            Prefs.K_SOURCE, Prefs.K_TARGET, Prefs.K_SCRIPT -> pipeline?.reset()
        }
        if (key == Prefs.K_SOURCE || key == Prefs.K_TARGET || key == Prefs.K_REPLY) {
            overlay?.setReplyLanguages(prefs.targetLang, replyLanguage())
        }
    }

    /** In diese Sprache wird die eigene Antwort übersetzt. */
    private fun replyLanguage(): String = when {
        prefs.replyLang != Languages.AUTO -> prefs.replyLang
        prefs.sourceLang != Languages.AUTO -> prefs.sourceLang
        else -> lastDetectedSource ?: TranslateLanguage.ENGLISH
    }

    private fun translateReply(text: String, onResult: (String) -> Unit) {
        val translator = replyTranslator ?: return
        val to = replyLanguage()
        translator.translate(
            text,
            prefs.targetLang,
            to,
            onSlow = { overlay?.composerStatus(getString(R.string.composer_downloading, Languages.displayName(to))) },
        ) { result ->
            if (result == null) overlay?.composerStatus(getString(R.string.composer_failed)) else onResult(result)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.notif_stop),
                    stopIntent,
                ).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        if (stopped) return
        stopped = true
        isRunning = false
        try {
            prefs.sp.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        displayListener?.let { displayManager?.unregisterDisplayListener(it) }
        displayListener = null
        capturer?.release()
        capturer = null
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        pipeline?.close()
        pipeline = null
        replyTranslator?.close()
        replyTranslator = null
        overlay?.destroy()
        overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- OverlayController.Callbacks

    override fun onCloseRequested() = shutdown()

    override fun onCropChanged(rect: Rect) {
        cropRect = Rect(rect)
    }

    override fun onOpenApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onReplyPreview(text: String) {
        translateReply(text) { result -> overlay?.composerPreview(text, result) }
    }

    override fun onReplyCopy(text: String) {
        translateReply(text) { result ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("SubLens", result))
            overlay?.composerCopied()
        }
    }

    // ---------------------------------------------------------------- TextPipeline.Listener

    override fun onTranslation(original: String, translated: String, sourceLang: String?) {
        overlay?.showTranslation(original, translated, sourceLang)
        if (prefs.sourceLang == Languages.AUTO && sourceLang != null && sourceLang != lastDetectedSource) {
            lastDetectedSource = sourceLang
            overlay?.setReplyLanguages(prefs.targetLang, replyLanguage())
        }
    }

    override fun onTextSeen() {
        lastTextAt = SystemClock.uptimeMillis()
        overlay?.setActive(true)
    }

    override fun onNoText() {
        overlay?.setActive(false)
        if (lastTextAt != 0L && SystemClock.uptimeMillis() - lastTextAt > IDLE_AFTER_MS) overlay?.showIdle()
    }

    override fun onStatus(message: String) {
        overlay?.showStatus(message)
    }

    companion object {
        private const val TAG = "SubLens"
        const val ACTION_START = "app.sublens.action.START"
        const val ACTION_STOP = "app.sublens.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFICATION_ID = 42
        private const val FIRST_TICK_DELAY_MS = 600L
        private const val IDLE_AFTER_MS = 2500L

        @Volatile
        var isRunning = false
            private set
    }
}
