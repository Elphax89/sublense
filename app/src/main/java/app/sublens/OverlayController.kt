package app.sublens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Verwaltet die Overlay-Fenster:
 *  - Rahmen (durchsichtig, Berührungen gehen ans Video durch)
 *  - Übersetzungsleiste (verschiebbar, ✕ und ⚙️)
 *  - Griff zum Ändern der Höhe
 *  - Antwortzeile (optional, ganz unten)
 *  - Schnell-Einstellungen (nur wenn geöffnet)
 *
 * Alles ist immer bildschirmbreit – im Hoch- und im Querformat.
 */
class OverlayController(
    private val ctx: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onCloseRequested()
        fun onCropChanged(rect: Rect)
        fun onOpenApp()
        fun onReplyPreview(text: String)
        fun onReplyCopy(text: String)

        /** Übersetzungen in der Linse geändert: [rects] in Ausschnitt-Koordinaten, leer = nichts verdeckt. */
        fun onLensChanged(rects: List<Rect>)
    }

    private enum class ListMode { SOURCE, TARGET, REPLY }

    private val wm: WindowManager = ctx.getSystemService(WindowManager::class.java)
    private val inflater = LayoutInflater.from(ctx)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).roundToInt()

    private val frameView = FrameView(ctx)
    private val barView: View = inflater.inflate(R.layout.overlay_bar, null)
    private val topHandle: View = inflater.inflate(R.layout.overlay_handle, null)
    private val bottomHandle: View = inflater.inflate(R.layout.overlay_handle, null)
    private val moveView: View = inflater.inflate(R.layout.overlay_move, null)
    private val controlsView: View = inflater.inflate(R.layout.overlay_controls, null)
    private var panelView: View? = null
    private val composer: ComposerWindow = ComposerWindow(ctx, wm, object : ComposerWindow.Listener {
        override fun onPreviewRequested(text: String) = callbacks.onReplyPreview(text)
        override fun onCopyRequested(text: String) = callbacks.onReplyCopy(text)
        override fun onReplyLanguageTapped() = openPanel(ListMode.REPLY)
        override fun onPeekTapped() = lens.setPeek(!lens.peeking)
        override fun onComposerHeightChanged() = layoutAll()
    })

    private val lens: LensLayer = LensLayer(ctx, wm).also { layer ->
        layer.onChanged = { callbacks.onLensChanged(if (layer.showing) layer.drawnRects else emptyList()) }
        layer.onPeekChanged = { on -> composer.setPeekActive(on) }
    }
    private var lastCrop: Rect? = null

    private val tvTranslation: TextView = barView.findViewById(R.id.translation)
    private val tvOriginal: TextView = barView.findViewById(R.id.original)
    private val tvChip: TextView = barView.findViewById(R.id.lang_chip)
    private val colorMain = ctx.getColor(R.color.text_main)
    private val colorDim = ctx.getColor(R.color.text_dim)
    private val colorAccent = ctx.getColor(R.color.accent)

    private val frameParams = overlayParams(touchable = false).apply {
        // Android blockiert durchgereichte Berührungen bei Fenstern über 80 % Deckkraft.
        alpha = 0.78f
    }
    private val barParams = overlayParams(touchable = true)
    private val topHandleParams = overlayParams(touchable = true)
    private val bottomHandleParams = overlayParams(touchable = true)
    private val moveParams = overlayParams(touchable = true)
    private val controlsParams = overlayParams(touchable = true).apply {
        // von rechts gemessen
        gravity = Gravity.TOP or Gravity.END
    }

    private var screenW = 0
    private var screenH = 0
    private var landscape = false
    private var frameTop = 0
    private var frameH = 0
    private var attached = false
    private var barAttached = false
    private var hasShownText = false
    private var detectedLang: String? = null
    private var panelClosedAt = 0L
    private var statusShowing = false
    private var lastToast = ""

    /** Abweichung zwischen gewünschter und tatsächlicher Fensterposition (sollte 0 sein). */
    private var offsetX = 0
    private var offsetY = 0

    private enum class Drag { MOVE, TOP_EDGE, BOTTOM_EDGE }

    init {
        controlsView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { callbacks.onCloseRequested() }
        controlsView.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { togglePanel() }
        // Die Leiste (nur im Leisten-Modus) zeigt nur noch Text – Bedienung sitzt am Rahmen.
        barView.findViewById<View>(R.id.btn_close).visibility = View.GONE
        barView.findViewById<View>(R.id.btn_settings).visibility = View.GONE
        attachDrag(barView, Drag.MOVE)
        attachDrag(moveView, Drag.MOVE)
        attachDrag(topHandle, Drag.TOP_EDGE)
        attachDrag(bottomHandle, Drag.BOTTOM_EDGE)
        frameView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measureOffset() }
        barView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) layoutAll()
        }
    }

    // ---------------------------------------------------------------- Fenster

    fun show(width: Int, height: Int) {
        setScreen(width, height)
        applyStyle()
        layoutAll()
        wm.addView(frameView, frameParams)
        attached = true
        updateBarAttached()
        if (prefs.composerEnabled) composer.attach()
        // Bedienelemente zuletzt, damit sie über Rahmen und Leiste liegen
        wm.addView(topHandle, topHandleParams)
        wm.addView(bottomHandle, bottomHandleParams)
        wm.addView(moveView, moveParams)
        wm.addView(controlsView, controlsParams)
        layoutAll()
    }

    fun destroy() {
        closePanel()
        lens.destroy()
        composer.detach()
        for (v in listOf(frameView, topHandle, bottomHandle, moveView, controlsView)) {
            try {
                wm.removeView(v)
            } catch (_: Exception) {
            }
        }
        if (barAttached) {
            try {
                wm.removeView(barView)
            } catch (_: Exception) {
            }
            barAttached = false
        }
        attached = false
    }

    /** Die Textleiste gibt es nur im Leisten-Modus. */
    private fun updateBarAttached() {
        if (!attached) return
        val want = !prefs.lensMode
        if (want == barAttached) return
        try {
            if (want) wm.addView(barView, barParams) else wm.removeView(barView)
            barAttached = want
        } catch (_: Exception) {
        }
    }

    /** Bildschirmgröße hat sich geändert (z. B. gedreht). */
    fun setScreen(width: Int, height: Int) {
        if (width == screenW && height == screenH) return
        screenW = width
        screenH = height
        landscape = width > height
        frameTop = (prefs.frameTopFraction(landscape) * screenH).roundToInt()
        frameH = (prefs.frameHeightFraction(landscape) * screenH).roundToInt()
        if (panelView != null) closePanel()
        composer.stopEditing()
        if (attached) layoutAll()
    }

    fun relayout() {
        updateBarAttached()
        layoutAll()
    }

    private fun overlayParams(touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) {
                // Absolute Bildschirmkoordinaten, keine Verschiebung durch Status- oder Navigationsleiste.
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun minFrameHeight(): Int = (screenH * Prefs.MIN_HEIGHT_FRACTION).roundToInt()
    private fun maxFrameHeight(): Int = max(minFrameHeight(), (screenH * Prefs.MAX_HEIGHT_FRACTION).roundToInt())

    /**
     * Ordnet alles an. Von oben nach unten:
     *  [Griff oben]  – auf der Oberkante, ändert die Höhe nach oben
     *  [Linse]
     *  [Verschieben ↕ links · Griff unten Mitte · ⚙ ✕ rechts] – auf der Unterkante
     *  ([Textleiste] – nur im Leisten-Modus, wahlweise oben)
     *  [Antwortzeile]
     */
    private fun layoutAll() {
        if (screenW <= 0 || screenH <= 0) return
        val tabH = dp(HANDLE_H_DP)
        val tabW = dp(HANDLE_W_DP)
        val ctlH = dp(CONTROL_DP)
        val topOut = tabH / 2
        val bottomOut = ctlH / 2
        val barShown = barAttached
        val barH = if (barShown) barView.height.takeIf { it > 0 } ?: dp(60f) else 0
        val above = barShown && prefs.barAbove
        val composerH = if (composer.attached) composer.stableHeight.takeIf { it > 0 } ?: dp(80f) else 0

        frameH = frameH.coerceIn(minFrameHeight(), maxFrameHeight())
        val reserveTop = topOut + (if (above) barH else 0)
        val reserveBottom = bottomOut + (if (barShown && !above) barH else 0) + composerH
        val maxTop = max(reserveTop, screenH - frameH - reserveBottom)
        frameTop = frameTop.coerceIn(reserveTop, maxTop)
        val frameBottom = frameTop + frameH

        frameParams.x = 0
        frameParams.y = frameTop
        frameParams.width = screenW
        frameParams.height = frameH

        topHandleParams.width = tabW
        topHandleParams.height = tabH
        topHandleParams.x = (screenW - tabW) / 2
        topHandleParams.y = frameTop - topOut

        bottomHandleParams.width = tabW
        bottomHandleParams.height = tabH
        bottomHandleParams.x = (screenW - tabW) / 2
        bottomHandleParams.y = frameBottom - tabH / 2

        moveParams.width = ctlH
        moveParams.height = ctlH
        moveParams.x = dp(EDGE_DP)
        moveParams.y = frameBottom - bottomOut

        controlsParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        controlsParams.height = ctlH
        controlsParams.x = dp(EDGE_DP)
        controlsParams.y = frameBottom - bottomOut

        barParams.x = 0
        barParams.width = screenW
        barParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        barParams.y = if (above) frameTop - topOut - barH else frameBottom + bottomOut

        if (attached) {
            update(frameView, frameParams)
            update(topHandle, topHandleParams)
            update(bottomHandle, bottomHandleParams)
            update(moveView, moveParams)
            update(controlsView, controlsParams)
            if (barAttached) update(barView, barParams)
        }
        composer.place(frameBottom + bottomOut + (if (barShown && !above) barH else 0), screenW)
        publishCrop()
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            // Fenster ist nicht (mehr) angehängt
        }
    }

    private fun measureOffset() {
        if (!attached || frameView.width == 0) return
        val loc = IntArray(2)
        frameView.getLocationOnScreen(loc)
        val ox = loc[0] - frameParams.x
        val oy = loc[1] - frameParams.y
        if (ox != offsetX || oy != offsetY) {
            offsetX = ox
            offsetY = oy
            publishCrop()
        }
    }

    /** Oberer und unterer Rand, an dem Griffe und Knöpfe liegen – dort wird kein Text gelesen. */
    private fun cropTopInset(): Int = max(frameView.insetPx, dp(HANDLE_H_DP) / 2 + dp(2f))
    private fun cropBottomInset(): Int = max(frameView.insetPx, dp(CONTROL_DP) / 2 + dp(2f))

    /** Bereich, in dem Text gelesen wird: das Rahmen-Innere ohne Rahmen, Griffe und Knöpfe. */
    private fun publishCrop() {
        val inset = frameView.insetPx
        val left = offsetX + inset
        val top = frameTop + offsetY + cropTopInset()
        val rect = Rect(left, top, offsetX + screenW - inset, frameTop + offsetY + frameH - cropBottomInset())
        if (rect.width() <= 0 || rect.height() <= 0) return
        if (rect != lastCrop) {
            // Linse verschoben: alte Übersetzungen liegen nicht mehr über ihrem Text.
            lastCrop = rect
            if (lens.hasContent) lens.clear()
        }
        callbacks.onCropChanged(rect)
    }

    private fun saveFrame() {
        if (screenH <= 0) return
        prefs.saveFrame(landscape, frameTop.toFloat() / screenH, frameH.toFloat() / screenH)
    }

    // ---------------------------------------------------------------- Bedienung

    /** Verschieben (ganze Linse) oder Ober-/Unterkante ziehen. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, mode: Drag) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downY = 0f
        var startTop = 0
        var startH = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY
                    startTop = frameTop
                    startH = frameH
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).roundToInt()
                    if (!dragging && abs(dy) > slop) dragging = true
                    if (dragging) {
                        when (mode) {
                            Drag.MOVE -> frameTop = startTop + dy
                            Drag.BOTTOM_EDGE -> frameH = (startH + dy).coerceIn(minFrameHeight(), maxFrameHeight())
                            Drag.TOP_EDGE -> {
                                val bottom = startTop + startH
                                frameH = (startH - dy).coerceIn(minFrameHeight(), maxFrameHeight())
                                frameTop = bottom - frameH
                            }
                        }
                        layoutAll()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) saveFrame()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    // ---------------------------------------------------------------- Inhalt der Leiste

    fun applyStyle() {
        val lensMode = prefs.lensMode
        val size = if (lensMode) 13f else prefs.textSizeSp.toFloat()
        tvTranslation.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tvTranslation.maxLines = if (lensMode) 2 else 4
        if (!lensMode && lens.hasContent) lens.clear()
        tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, max(11f, size * 0.65f))
        if (!prefs.showOriginal || lensMode) tvOriginal.visibility = View.GONE
        if (prefs.sourceLang != Languages.AUTO) detectedLang = null
        updateChip()
    }

    private fun updateChip() {
        val src = detectedLang ?: prefs.sourceLang
        tvChip.text = "${Languages.shortCode(src)} → ${Languages.shortCode(prefs.targetLang)}"
    }

    fun showTranslation(original: String, translated: String, sourceLang: String?) {
        hasShownText = true
        clearStatus()
        detectedLang = if (prefs.sourceLang == Languages.AUTO) sourceLang else null
        updateChip()
        tvTranslation.setTextColor(colorMain)
        tvTranslation.setTypeface(null, Typeface.NORMAL)
        tvTranslation.text = translated
        val showOrig = prefs.showOriginal && original != translated
        tvOriginal.text = original
        tvOriginal.visibility = if (showOrig) View.VISIBLE else View.GONE
    }

    /** Übersetzungen direkt in die Linse legen. [crop] ist der Ausschnitt, aus dem sie stammen. */
    fun showLens(items: List<LensItem>, crop: Rect, sourceLang: String?) {
        detectedLang = if (prefs.sourceLang == Languages.AUTO) sourceLang else null
        updateChip()
        if (crop != lastCrop) return // inzwischen verschoben – passt nicht mehr
        if (panelView != null) return // Einstellungen offen – nichts darüber legen
        lens.show(items, crop.left - offsetX, crop.top - offsetY, frameTop + frameH - cropBottomInset())
        hasShownText = true
        clearStatus()
    }

    private fun clearStatus() {
        if (!statusShowing) return
        statusShowing = false
        lastToast = ""
        composer.clearStatus()
    }

    fun clearLens() {
        if (lens.hasContent) lens.clear()
    }

    /** Für die Aufnahme kurz ausblenden (true) bzw. wieder zeigen. Gibt zurück, ob etwas ausgeblendet wurde. */
    fun setLensHiddenForCapture(hidden: Boolean): Boolean {
        lens.setCaptureHidden(hidden)
        return lens.hasContent
    }

    fun showStatus(message: String) {
        if (prefs.lensMode) {
            // Keine Textleiste: Meldung in der Antwortzeile, sonst kurz als Hinweis.
            statusShowing = true
            if (composer.attached) {
                composer.showStatus(message)
            } else if (message != lastToast) {
                lastToast = message
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
            }
            return
        }
        tvTranslation.setTextColor(colorDim)
        tvTranslation.setTypeface(null, Typeface.ITALIC)
        tvTranslation.text = message
        tvOriginal.visibility = View.GONE
    }

    /** Kein Text mehr im Rahmen. Vor dem ersten Text bleibt der Hinweis stehen. */
    fun showIdle() {
        if (!hasShownText) return
        if (prefs.lensMode) return
        showStatus(ctx.getString(R.string.bar_idle))
    }

    fun setActive(active: Boolean) {
        frameView.active = active
    }

    // ---------------------------------------------------------------- Antwortzeile

    fun setComposerEnabled(enabled: Boolean) {
        if (!attached) return
        if (enabled) composer.attach() else composer.detach()
        layoutAll()
    }

    fun setReplyLanguages(own: String, reply: String) = composer.setLanguages(own, reply)
    fun composerPreview(forText: String, translated: String) = composer.showPreview(forText, translated)
    fun composerStatus(message: String) = composer.showStatus(message)
    fun composerCopied() = composer.showCopied()

    // ---------------------------------------------------------------- Schnell-Einstellungen

    private fun togglePanel() {
        if (panelView != null) {
            closePanel()
            return
        }
        // Tippen auf ⚙️ schließt das Panel bereits über "außerhalb berührt" – nicht sofort wieder öffnen.
        if (SystemClock.uptimeMillis() - panelClosedAt < 350) return
        openPanel(null)
    }

    /** Öffnet die Schnell-Einstellungen, optional direkt in einer Sprachliste. */
    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel(startList: ListMode?) {
        closePanel()
        composer.stopEditing()
        val panel = inflater.inflate(R.layout.overlay_panel, null)
        val params = overlayParams(touchable = true).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.CENTER
            width = min(screenW - dp(32f), dp(420f))
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        panel.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closePanel()
                true
            } else {
                false
            }
        }
        panel.findViewById<ImageButton>(R.id.panel_close).setOnClickListener { closePanel() }
        panel.findViewById<ImageButton>(R.id.panel_back).setOnClickListener { bindPanelMain(panel) }
        if (startList != null) showLanguageList(panel, startList) else bindPanelMain(panel)
        try {
            wm.addView(panel, params)
            panelView = panel
        } catch (_: Exception) {
            panelView = null
        }
    }

    private fun closePanel() {
        val panel = panelView ?: return
        panelView = null
        panelClosedAt = SystemClock.uptimeMillis()
        try {
            wm.removeView(panel)
        } catch (_: Exception) {
        }
    }

    private fun bindPanelMain(panel: View) {
        val page = panel.findViewById<ScrollView>(R.id.page_main)
        page.visibility = View.VISIBLE
        // Im Querformat ist wenig Höhe – dann scrollt die Seite.
        page.layoutParams = page.layoutParams.apply {
            height = if (landscape) (screenH * 0.62f).roundToInt() else LinearLayout.LayoutParams.WRAP_CONTENT
        }
        panel.findViewById<View>(R.id.list_scroll).visibility = View.GONE
        panel.findViewById<View>(R.id.panel_back).visibility = View.GONE
        panel.findViewById<TextView>(R.id.panel_title).setText(R.string.panel_title)

        val from = panel.findViewById<TextView>(R.id.value_from)
        val to = panel.findViewById<TextView>(R.id.value_to)
        from.text = Languages.displayName(prefs.sourceLang)
        to.text = Languages.displayName(prefs.targetLang)
        from.setOnClickListener { showLanguageList(panel, ListMode.SOURCE) }
        to.setOnClickListener { showLanguageList(panel, ListMode.TARGET) }

        val reply = panel.findViewById<TextView>(R.id.value_reply)
        reply.text = replyLabel(prefs.replyLang)
        reply.setOnClickListener { showLanguageList(panel, ListMode.REPLY) }

        val on = panel.findViewById<TextView>(R.id.choice_composer_on)
        val off = panel.findViewById<TextView>(R.id.choice_composer_off)
        on.isSelected = prefs.composerEnabled
        off.isSelected = !prefs.composerEnabled
        on.setOnClickListener {
            prefs.composerEnabled = true
            on.isSelected = true
            off.isSelected = false
        }
        off.setOnClickListener {
            prefs.composerEnabled = false
            on.isSelected = false
            off.isSelected = true
        }

        val lensChoice = panel.findViewById<TextView>(R.id.choice_lens)
        val barChoice = panel.findViewById<TextView>(R.id.choice_bar)
        lensChoice.isSelected = prefs.lensMode
        barChoice.isSelected = !prefs.lensMode
        // "Position der Leiste" nur im Leisten-Modus – in der Linse gibt es keine Leiste.
        val barPosViews = listOf(panel.findViewById<View>(R.id.label_bar_pos), panel.findViewById<View>(R.id.row_bar_pos))
        fun syncBarPos() = barPosViews.forEach { it.visibility = if (prefs.lensMode) View.GONE else View.VISIBLE }
        syncBarPos()
        lensChoice.setOnClickListener {
            prefs.lensMode = true
            lensChoice.isSelected = true
            barChoice.isSelected = false
            syncBarPos()
        }
        barChoice.setOnClickListener {
            prefs.lensMode = false
            lensChoice.isSelected = false
            barChoice.isSelected = true
            syncBarPos()
        }

        val above = panel.findViewById<TextView>(R.id.choice_above)
        val below = panel.findViewById<TextView>(R.id.choice_below)
        above.isSelected = prefs.barAbove
        below.isSelected = !prefs.barAbove
        above.setOnClickListener {
            prefs.barAbove = true
            above.isSelected = true
            below.isSelected = false
        }
        below.setOnClickListener {
            prefs.barAbove = false
            above.isSelected = false
            below.isSelected = true
        }
        panel.findViewById<View>(R.id.open_app).setOnClickListener {
            closePanel()
            callbacks.onOpenApp()
        }
    }

    private fun replyLabel(tag: String): String =
        if (tag == Languages.AUTO) ctx.getString(R.string.reply_auto) else Languages.displayName(tag)

    private fun showLanguageList(panel: View, mode: ListMode) {
        panel.findViewById<View>(R.id.page_main).visibility = View.GONE
        panel.findViewById<View>(R.id.panel_back).visibility = View.VISIBLE
        panel.findViewById<TextView>(R.id.panel_title).setText(
            when (mode) {
                ListMode.SOURCE -> R.string.label_from
                ListMode.TARGET -> R.string.label_to
                ListMode.REPLY -> R.string.label_reply
            },
        )

        val scroll = panel.findViewById<ScrollView>(R.id.list_scroll)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = min(dp(320f), (screenH * 0.55f).roundToInt())
        }
        scroll.visibility = View.VISIBLE

        val list = panel.findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        val items = when (mode) {
            ListMode.SOURCE -> Languages.sources()
            ListMode.TARGET -> Languages.targets()
            ListMode.REPLY -> listOf(Languages.AUTO) + Languages.targets()
        }
        val current = when (mode) {
            ListMode.SOURCE -> prefs.sourceLang
            ListMode.TARGET -> prefs.targetLang
            ListMode.REPLY -> prefs.replyLang
        }
        var currentView: View? = null
        for (tag in items) {
            val tv = inflater.inflate(R.layout.item_language, list, false) as TextView
            val selected = tag == current
            val label = if (mode == ListMode.REPLY) replyLabel(tag) else Languages.displayName(tag)
            tv.text = if (selected) "✓  $label" else label
            tv.setTextColor(if (selected) colorAccent else colorMain)
            tv.setOnClickListener {
                when (mode) {
                    ListMode.SOURCE -> prefs.sourceLang = tag
                    ListMode.TARGET -> prefs.targetLang = tag
                    ListMode.REPLY -> prefs.replyLang = tag
                }
                bindPanelMain(panel)
            }
            list.addView(tv)
            if (selected) currentView = tv
        }
        currentView?.let { v -> scroll.post { scroll.scrollTo(0, max(0, v.top - dp(48f))) } }
    }

    companion object {
        private const val HANDLE_W_DP = 64f
        private const val HANDLE_H_DP = 22f
        private const val CONTROL_DP = 40f
        private const val EDGE_DP = 12f
    }
}
