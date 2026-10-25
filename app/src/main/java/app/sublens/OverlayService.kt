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
import android.view.Choreographer
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service: holds the screen capture, shows the overlay and
 * sends the lens area to text recognition at regular intervals.
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

    /** Our own visible translations in the lens (crop coordinates). Empty = the lens shows the original. */
    @Volatile
    private var lensMask: List<Rect> = emptyList()

    /** Is a capture with the lens hidden running right now? */
    @Volatile
    private var hiddenCapture = false

    @Volatile
    private var lensMode = true

    // capture thread only
    private var baseline: IntArray? = null
    /** Previous fingerprint (tick to tick) and "restlessness" per cell: high = an animation runs there. */
    private var prevSig: IntArray? = null
    private var heat: FloatArray? = null
    /** Until when a cell counts as "animated" (even if the animation pauses briefly). */
    private var animUntil: LongArray? = null
    /** Since the last capture the whole picture moved (scrolled, new message). */
    private var movedAll = false
    private var videoHintShown = false

    /** Full re-read requested (display no longer matches the picture). */
    @Volatile
    private var fullRequested = false
    private var lastStaleAt = 0L
    @Volatile
    private var lastHiddenAt = 0L
    private var lastTextAt = 0L
    private var stopped = false

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> onPrefChanged(key) }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Languages.autoLabel = getString(R.string.auto_detect)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown()
            ACTION_START -> {
                // Required from Android 14: go foreground with type "mediaProjection" first, then get the capture.
                try {
                    startInForeground()
                } catch (e: Exception) {
                    Log.e(TAG, "Starting foreground service failed", e)
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
                    Log.e(TAG, "Start failed", e)
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

        lensMode = prefs.lensMode
        isRunning = true
        stopped = false
        notifyState()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        registerDisplayListener(dm)
        cap.handler.postDelayed(tick, FIRST_TICK_DELAY_MS)
    }

    /** Context for overlay windows (a dedicated window context from Android 11). */
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

    /** Rotated or resolution changed: rearrange the overlay, adapt the capture. */
    private fun onScreenChanged() {
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val size = realSize(display)
        overlay?.setScreen(size.x, size.y)
        capturer?.resize(size.x, size.y, resources.displayMetrics.densityDpi)
    }

    /**
     * Runs on the capture thread.
     *
     * When translations sit in the lens, they cover the original. Then only a coarse
     * fingerprint of the picture is compared (without our own translations). When something changes –
     * new message, scrolled, new subtitle – the affected part of the lens is hidden for a frame or two
     * and captured right then. If everything stays still, nothing flickers.
     */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val cap = capturer ?: return
            val pipe = pipeline ?: return
            val rect = cropRect
            val interval = prefs.intervalMs.coerceIn(200L, 3000L)
            if (rect != null && !pipe.busy && !hiddenCapture) {
                val mask = lensMask
                if (lensMode && mask.isNotEmpty()) {
                    // No new frame since the last tick = the screen is still → the last fingerprint stays valid.
                    val sig = cap.signature(rect, mask) ?: prevSig
                    val now = SystemClock.uptimeMillis()
                    val gapOk = now - lastHiddenAt >= max(interval, MIN_HIDDEN_GAP_MS)
                    val previousTick = prevSig
                    if (sig != null) learnMotion(sig)
                    if (fullRequested && gapOk) {
                        fullRequested = false
                        baseline = null
                        movedAll = false
                        startHiddenCapture(cap, rect, null)
                    } else if (sig != null) {
                        val base = baseline
                        if (base == null || base.size != sig.size) {
                            baseline = sig
                        } else if (gapOk) {
                            // In chat mode, places that animate all the time (GIFs, stickers,
                            // animated emojis and profile pictures) don't count as a change.
                            // Also only read once the picture has settled (done scrolling,
                            // message fully shown) – animations never settle and trigger nothing.
                            val video = prefs.videoMode
                            val ignore = if (video) null else animatedCells()
                            val settleRef = if (video) null else previousTick
                            val changed = ScreenCapturer.changedRegion(base, sig, rect.width(), rect.height(), ignore, settleRef)
                            if (changed != null) {
                                baseline = null
                                val region = if (movedAll) null else partialRegion(changed, mask, rect)
                                movedAll = false
                                startHiddenCapture(cap, rect, region)
                            }
                        }
                        if (!prefs.videoMode) maybeHintVideo()
                    }
                } else {
                    val bitmap = cap.grab(rect)
                    if (bitmap != null) send(bitmap, rect)
                }
            }
            cap.handler.postDelayed(this, interval)
        }
    }

    private fun send(bitmap: android.graphics.Bitmap, rect: Rect) {
        pipeline?.busy = true
        main.post {
            val p = pipeline
            if (p != null) p.process(bitmap, rect) else bitmap.recycle()
        }
    }

    /** Hide the lens, wait until the screen is drawn without it, capture, show it again. */
    /**
     * Learns where the picture keeps changing from tick to tick (animations). If almost everything
     * moves at once (scrolling), that is not an animation but a real change.
     */
    private fun learnMotion(sig: IntArray) {
        val prev = prevSig
        prevSig = sig
        if (prev == null || prev.size != sig.size) {
            heat = FloatArray(sig.size)
            return
        }
        val h = heat ?: FloatArray(sig.size).also { heat = it }
        var valid = 0
        var moved = 0
        for (i in sig.indices) {
            if (prev[i] < 0 || sig[i] < 0) continue
            valid++
            if (abs(prev[i] - sig[i]) > ScreenCapturer.CELL_THRESHOLD) moved++
        }
        val global = valid > 0 && moved * 100 >= valid * GLOBAL_MOVE_PERCENT
        if (global) movedAll = true
        val until = animUntil?.takeIf { it.size == sig.size } ?: LongArray(sig.size).also { animUntil = it }
        val now = SystemClock.uptimeMillis()
        val cols = ScreenCapturer.SIG_X
        val rows = sig.size / cols
        for (i in sig.indices) {
            h[i] *= HEAT_DECAY
            if (!global && prev[i] >= 0 && sig[i] >= 0 && abs(prev[i] - sig[i]) > ScreenCapturer.CELL_THRESHOLD) h[i] += 1f
            if (h[i] >= HEAT_ANIMATED) {
                // Animation: ignore the cell and its neighbours for a while (GIF frames don't change every cell every time).
                val cx = i % cols
                val cy = i / cols
                for (dy in -ANIM_SPREAD..ANIM_SPREAD) for (dx in -ANIM_SPREAD..ANIM_SPREAD) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until cols && y in 0 until rows) until[y * cols + x] = now + ANIM_HOLD_MS
                }
            }
        }
    }

    private fun animatedCells(): BooleanArray? {
        val until = animUntil ?: return null
        val now = SystemClock.uptimeMillis()
        return BooleanArray(until.size) { until[it] > now }
    }

    /** Chat mode over a video: point out once that the video button lets subtitles keep up. */
    private fun maybeHintVideo() {
        if (videoHintShown) return
        val anim = animatedCells() ?: return
        val hot = anim.count { it }
        if (hot * 100 >= anim.size * VIDEO_HINT_PERCENT) {
            videoHintShown = true
            main.post { overlay?.showVideoHint() }
        }
    }

    /**
     * If only part moves (GIF, video), only that part is re-read. The area is extended by all
     * translations touching it so none stays half covered.
     * null = re-read the whole lens (e.g. scrolled).
     */
    private fun partialRegion(changed: Rect, drawn: List<Rect>, crop: Rect): Rect? {
        val r = Rect(changed)
        var grew = true
        while (grew) {
            grew = false
            for (d in drawn) {
                if (Rect.intersects(d, r) && !r.contains(d)) {
                    r.union(d)
                    grew = true
                }
            }
        }
        val area = r.width().toLong() * r.height()
        val tooBig = area * 100 > crop.width().toLong() * crop.height() * PARTIAL_MAX_PERCENT ||
            r.height() * 100 > crop.height() * PARTIAL_MAX_HEIGHT_PERCENT
        return if (tooBig) null else r
    }

    private fun startHiddenCapture(cap: ScreenCapturer, rect: Rect, region: Rect? = null) {
        hiddenCapture = true
        main.post {
            val ov = overlay
            if (ov == null || !ov.setLensHiddenForCapture(true, region)) {
                ov?.setLensHiddenForCapture(false)
                hiddenCapture = false
                return@post
            }
            val chor = Choreographer.getInstance()
            // Frame 1: windows draw empty, frame 2: screen composed, frame 3: safety margin
            chor.postFrameCallback {
                chor.postFrameCallback {
                    chor.postFrameCallback {
                        cap.handler.postDelayed({ grabHidden(cap, rect, 0, region) }, 12)
                    }
                }
            }
        }
    }

    private fun grabHidden(cap: ScreenCapturer, rect: Rect, attempt: Int, region: Rect?) {
        if (!isRunning) return
        val bitmap = cap.grab(rect)
        if (bitmap == null && attempt < 4) {
            cap.handler.postDelayed({ grabHidden(cap, rect, attempt + 1, region) }, 16)
            return
        }
        main.post {
            overlay?.setLensHiddenForCapture(false)
            lastHiddenAt = SystemClock.uptimeMillis()
            hiddenCapture = false
            if (bitmap != null) {
                val p = pipeline
                if (p != null) {
                    p.busy = true
                    p.process(bitmap, rect, region, lensMask)
                } else {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun onPrefChanged(key: String?) {
        lensMode = prefs.lensMode
        overlay?.applyStyle()
        when (key) {
            Prefs.K_LENS_MODE -> {
                overlay?.relayout()
                overlay?.clearLens()
                pipeline?.reset()
            }
            Prefs.K_BAR_ABOVE -> overlay?.relayout()
            Prefs.K_LENS_COLOR -> overlay?.applyLensColor()
            Prefs.K_VIDEO_MODE -> {
                overlay?.updateVideoButton()
                fullRequested = true
            }
            Prefs.K_COMPOSER -> overlay?.setComposerEnabled(prefs.composerEnabled)
            Prefs.K_SOURCE, Prefs.K_TARGET, Prefs.K_SCRIPT -> {
                overlay?.clearLens()
                pipeline?.reset()
            }
        }
        if (key == Prefs.K_SOURCE || key == Prefs.K_TARGET || key == Prefs.K_REPLY) {
            overlay?.setReplyLanguages(prefs.targetLang, replyLanguage())
        }
    }

    /** The language your own reply is translated into. */
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
        notifyState()
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
        if (rect == cropRect) return
        cropRect = Rect(rect)
        capturer?.let { cap ->
            cap.handler.post {
                prevSig = null
                heat = null
                animUntil = null
                baseline = null
            }
        }
    }

    override fun onLensChanged(rects: List<Rect>) {
        lensMask = rects.map { Rect(it) }
        capturer?.let { cap -> cap.handler.post { baseline = null } }
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

    override fun onResult(items: List<LensItem>, crop: Rect, sourceLang: String?) {
        if (lensMode) {
            overlay?.showLens(items, crop, sourceLang)
        } else {
            val original = items.joinToString("\n") { it.original }
            val translated = items.joinToString("\n") { it.translated }
            overlay?.showTranslation(original, translated, sourceLang)
        }
        if (prefs.sourceLang == Languages.AUTO && sourceLang != null && sourceLang != lastDetectedSource) {
            lastDetectedSource = sourceLang
            overlay?.setReplyLanguages(prefs.targetLang, replyLanguage())
        }
    }

    override fun onTextSeen() {
        lastTextAt = SystemClock.uptimeMillis()
        overlay?.setActive(true)
    }

    override fun onStale() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStaleAt < STALE_MIN_GAP_MS) return
        lastStaleAt = now
        fullRequested = true
    }

    override fun onNoText() {
        overlay?.setActive(false)
        overlay?.clearLens()
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
        private const val MIN_HIDDEN_GAP_MS = 600L
        /** If more than this percentage of the lens moves, everything is re-read. */
        private const val PARTIAL_MAX_PERCENT = 40L
        /** … or a strip taller than this percentage (e.g. all profile pictures while scrolling). */
        private const val PARTIAL_MAX_HEIGHT_PERCENT = 50
        /** If this percentage of cells changes at once, the user scrolled. */
        private const val GLOBAL_MOVE_PERCENT = 25
        private const val HEAT_DECAY = 0.85f
        /** Changed twice in quick succession = animation. */
        private const val HEAT_ANIMATED = 1.5f
        private const val VIDEO_HINT_PERCENT = 30
        /** Animated places stay ignored this long, even if they pause briefly. */
        private const val ANIM_HOLD_MS = 10_000L
        /** … including this many neighbouring cells around them. */
        private const val ANIM_SPREAD = 1
        private const val STALE_MIN_GAP_MS = 3000L

        @Volatile
        var isRunning = false
            private set

        /** Called on the main thread whenever the service starts or stops. */
        var stateListener: ((Boolean) -> Unit)? = null

        private fun notifyState() {
            val running = isRunning
            Handler(Looper.getMainLooper()).post { stateListener?.invoke(running) }
        }
    }
}
