package app.sublens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.widget.ImageView
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
 * Manages the overlay windows:
 *  - frame (transparent, touches go through to the video)
 *  - translation bar (bar mode only)
 *  - handles to change the height, ↕ move button, ▶ ⇅ ⚙ ✕ controls
 *  - reply line (optional, below or above the lens)
 *  - quick settings (only when open)
 *
 * Everything is always full screen width – in portrait and landscape.
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

        /** Translations in the lens changed: [rects] in crop coordinates, empty = nothing covered. */
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
        // Android blocks passed-through touches for windows above 80 % opacity.
        alpha = 0.78f
    }
    private val barParams = overlayParams(touchable = true)
    private val topHandleParams = overlayParams(touchable = true)
    private val bottomHandleParams = overlayParams(touchable = true)
    private val moveParams = overlayParams(touchable = true)
    private val controlsParams = overlayParams(touchable = true).apply {
        // measured from the right
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

    /** Difference between requested and actual window position (should be 0). */
    private var offsetX = 0
    private var offsetY = 0

    private enum class Drag { MOVE, TOP_EDGE, BOTTOM_EDGE }

    init {
        controlsView.findViewById<ImageButton>(R.id.btn_close).setOnClickListener { callbacks.onCloseRequested() }
        controlsView.findViewById<ImageButton>(R.id.btn_settings).setOnClickListener { togglePanel() }
        controlsView.findViewById<ImageButton>(R.id.btn_swap).setOnClickListener { swapComposer() }
        controlsView.findViewById<ImageButton>(R.id.btn_video).setOnClickListener {
            prefs.videoMode = !prefs.videoMode
            toast(ctx.getString(if (prefs.videoMode) R.string.video_on else R.string.video_off))
        }
        updateVideoButton()
        // The bar (bar mode only) just shows text – the controls sit on the frame.
        barView.findViewById<View>(R.id.btn_close).visibility = View.GONE
        barView.findViewById<View>(R.id.btn_settings).visibility = View.GONE
        applyLensColor()
        attachDrag(barView, Drag.MOVE)
        attachDrag(moveView, Drag.MOVE)
        attachDrag(topHandle, Drag.TOP_EDGE)
        attachDrag(bottomHandle, Drag.BOTTOM_EDGE)
        frameView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measureOffset() }
        barView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) layoutAll()
        }
    }

    // ---------------------------------------------------------------- Windows

    fun show(width: Int, height: Int) {
        setScreen(width, height)
        applyStyle()
        layoutAll()
        wm.addView(frameView, frameParams)
        attached = true
        updateBarAttached()
        if (prefs.composerEnabled) composer.attach()
        // Controls last so they sit above the frame and the bar
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

    /** The text bar only exists in bar mode. */
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

    /** Screen size changed (e.g. rotated). */
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
                // Absolute screen coordinates, no offset from status or navigation bar.
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun minFrameHeight(): Int = (screenH * Prefs.MIN_HEIGHT_FRACTION).roundToInt()

    private fun barHeight(): Int = if (barAttached) barView.height.takeIf { it > 0 } ?: dp(60f) else 0
    /**
     * Height of the reply line without the preview/status line. Only this is used to arrange the lens –
     * when "Copied" or the preview appears, the line grows away from the lens and the lens stays put.
     */
    private fun composerHeight(): Int =
        if (composer.attached) composer.baseHeight.takeIf { it > 0 } ?: composer.stableHeight.takeIf { it > 0 } ?: dp(80f) else 0

    private fun composerActualHeight(): Int =
        if (composer.attached) max(composer.stableHeight, composerHeight()) else 0

    /** Space above/below the lens needed by the bar and reply line (without or with the reply line). */
    private fun reserveTop(withComposer: Boolean = true): Int =
        (if (barAttached && prefs.barAbove) barHeight() else 0) +
            (if (withComposer && prefs.composerAbove) composerHeight() else 0)

    private fun reserveBottom(withComposer: Boolean = true): Int =
        (if (barAttached && !prefs.barAbove) barHeight() else 0) +
            (if (withComposer && !prefs.composerAbove) composerHeight() else 0)

    /** As tall as the screen allows next to the bar and reply line. */
    private fun maxFrameHeight(): Int = max(minFrameHeight(), screenH - reserveTop() - reserveBottom())

    /** Does the reply line fit on the other side of the lens without sliding off screen? */
    private fun canSwapComposer(): Boolean {
        if (!composer.attached) return false
        val need = composerHeight()
        return if (prefs.composerAbove) {
            screenH - (frameTop + frameH) - reserveBottom(withComposer = false) >= need
        } else {
            frameTop - reserveTop(withComposer = false) >= need
        }
    }

    private fun swapComposer() {
        if (!canSwapComposer()) {
            toast(ctx.getString(if (prefs.composerAbove) R.string.swap_no_room_below else R.string.swap_no_room_above))
            return
        }
        composer.stopEditing()
        prefs.composerAbove = !prefs.composerAbove
        layoutAll()
    }

    private fun toast(message: String) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Arranges everything. From top to bottom:
     *  ([reply line] – when swapped to the top)
     *  ([text bar] – bar mode only, optionally on top)
     *  [lens] with a handle at the top (inside the top edge) and at the bottom
     *          [move ↕ left · bottom handle centre · ▶ ⇅ ⚙ ✕ right] inside the bottom edge
     *  ([text bar] at the bottom)
     *  [reply line] – right against the lens, no gap
     */
    private fun layoutAll() {
        if (screenW <= 0 || screenH <= 0) return
        val tabH = dp(HANDLE_H_DP)
        val tabW = dp(HANDLE_W_DP)
        val ctlH = dp(CONTROL_DP)
        val inset = dp(INNER_DP)
        val barShown = barAttached
        val barH = barHeight()
        val barAbove = barShown && prefs.barAbove
        val composerH = composerHeight()
        val composerAbove = prefs.composerAbove

        val resTop = reserveTop()
        val resBottom = reserveBottom()
        frameH = frameH.coerceIn(minFrameHeight(), maxFrameHeight())
        val maxTop = max(resTop, screenH - frameH - resBottom)
        frameTop = frameTop.coerceIn(resTop, maxTop)
        val frameBottom = frameTop + frameH

        frameParams.x = 0
        frameParams.y = frameTop
        frameParams.width = screenW
        frameParams.height = frameH

        topHandleParams.width = tabW
        topHandleParams.height = tabH
        topHandleParams.x = (screenW - tabW) / 2
        topHandleParams.y = frameTop + inset

        bottomHandleParams.width = tabW
        bottomHandleParams.height = tabH
        bottomHandleParams.x = (screenW - tabW) / 2
        bottomHandleParams.y = frameBottom - inset - tabH

        moveParams.width = ctlH
        moveParams.height = ctlH
        moveParams.x = dp(EDGE_DP)
        moveParams.y = frameBottom - inset - ctlH

        controlsParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        controlsParams.height = ctlH
        controlsParams.x = dp(EDGE_DP)
        controlsParams.y = frameBottom - inset - ctlH

        barParams.x = 0
        barParams.width = screenW
        barParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        barParams.y = if (barAbove) frameTop - barH else frameBottom

        controlsView.findViewById<View>(R.id.btn_swap).apply {
            visibility = if (composer.attached) View.VISIBLE else View.GONE
            alpha = if (canSwapComposer()) 1f else 0.35f
        }

        if (attached) {
            update(frameView, frameParams)
            update(topHandle, topHandleParams)
            update(bottomHandle, bottomHandleParams)
            update(moveView, moveParams)
            update(controlsView, controlsParams)
            if (barAttached) update(barView, barParams)
        }
        val composerY = if (composerAbove) {
            frameTop - (if (barAbove) barH else 0) - composerActualHeight()
        } else {
            frameBottom + (if (barShown && !barAbove) barH else 0)
        }
        composer.place(composerY, screenW)
        publishCrop()
    }

    private fun update(view: View, params: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
            // window is not attached (any more)
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

    /** Top and bottom margin left out of text recognition. */
    // Text is read right up to the frame. Handles and buttons sit on top but contain no letters.
    private fun cropTopInset(): Int = frameView.insetPx
    private fun cropBottomInset(): Int = frameView.insetPx

    /** Area where text is read: the inside of the frame without the frame line. */
    private fun publishCrop() {
        val inset = frameView.insetPx
        val left = offsetX + inset
        val top = frameTop + offsetY + cropTopInset()
        val rect = Rect(left, top, offsetX + screenW - inset, frameTop + offsetY + frameH - cropBottomInset())
        if (rect.width() <= 0 || rect.height() <= 0) return
        if (rect == lastCrop) return
        // Lens moved: old translations no longer sit over their text.
        lastCrop = rect
        if (lens.hasContent) lens.clear()
        callbacks.onCropChanged(rect)
    }

    private fun saveFrame() {
        if (screenH <= 0) return
        prefs.saveFrame(landscape, frameTop.toFloat() / screenH, frameH.toFloat() / screenH)
    }

    // ---------------------------------------------------------------- Controls

    /** Move (the whole lens) or drag the top/bottom edge. */
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
                            // The other edge stays put; grow only up to the space needed for the bar/reply line.
                            Drag.BOTTOM_EDGE -> {
                                val limit = max(minFrameHeight(), screenH - reserveBottom() - startTop)
                                frameH = (startH + dy).coerceIn(minFrameHeight(), limit)
                            }
                            Drag.TOP_EDGE -> {
                                val bottom = startTop + startH
                                val limit = max(minFrameHeight(), bottom - reserveTop())
                                frameH = (startH - dy).coerceIn(minFrameHeight(), limit)
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

    // ---------------------------------------------------------------- Bar content

    /** Video button: bright + lens colour = on, dimmed = off (chat mode). */
    fun updateVideoButton() {
        val b = controlsView.findViewById<ImageButton>(R.id.btn_video)
        val on = prefs.videoMode
        b.alpha = if (on) 1f else 0.5f
        b.imageTintList = if (on) ColorStateList.valueOf(prefs.lensColor) else null
    }

    fun showVideoHint() = toast(ctx.getString(R.string.video_hint))

    /** Frame, handles and move button in the chosen colour. */
    fun applyLensColor() {
        val c = prefs.lensColor
        frameView.setColor(c)
        updateVideoButton()
        val stroke = (1.5f * density).roundToInt()
        for (v in listOf(topHandle, bottomHandle, moveView)) {
            val img = (v as ViewGroup).getChildAt(0) as ImageView
            (img.background?.mutate() as? GradientDrawable)?.setStroke(stroke, c)
            if (v === moveView) img.imageTintList = ColorStateList.valueOf(c)
        }
    }

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

    /** Put translations right into the lens. [crop] is the area they come from. */
    fun showLens(items: List<LensItem>, crop: Rect, sourceLang: String?) {
        detectedLang = if (prefs.sourceLang == Languages.AUTO) sourceLang else null
        updateChip()
        if (crop != lastCrop) return // moved in the meantime – no longer fits
        if (panelView != null) return // settings open – don't draw over them
        val addedWindows = lens.show(items, crop.left - offsetX, crop.top - offsetY, frameTop + frameH - cropBottomInset(), crop.width())
        if (addedWindows) raiseControlsIfCovered()
        hasShownText = true
        clearStatus()
    }

    /**
     * New translation windows sit on top of everything that was there before. If they cover handles or
     * buttons, those are brought back to the top so they stay visible and usable.
     */
    private fun raiseControlsIfCovered() {
        if (!attached) return
        val ctlW = controlsView.width.takeIf { it > 0 } ?: dp(100f)
        val controls = listOf(
            topHandle to Rect(topHandleParams.x, topHandleParams.y, topHandleParams.x + topHandleParams.width, topHandleParams.y + topHandleParams.height),
            bottomHandle to Rect(bottomHandleParams.x, bottomHandleParams.y, bottomHandleParams.x + bottomHandleParams.width, bottomHandleParams.y + bottomHandleParams.height),
            moveView to Rect(moveParams.x, moveParams.y, moveParams.x + moveParams.width, moveParams.y + moveParams.height),
            controlsView to Rect(screenW - controlsParams.x - ctlW, controlsParams.y, screenW - controlsParams.x, controlsParams.y + controlsParams.height),
        )
        val boxes = lens.windowRects
        for ((view, r) in controls) {
            if (boxes.none { Rect.intersects(it, r) }) continue
            val params = when (view) {
                topHandle -> topHandleParams
                bottomHandle -> bottomHandleParams
                moveView -> moveParams
                else -> controlsParams
            }
            try {
                wm.removeViewImmediate(view)
                wm.addView(view, params)
            } catch (_: Exception) {
            }
        }
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

    /** Hide briefly for capturing (true) or show again. Returns whether anything was hidden. */
    fun setLensHiddenForCapture(hidden: Boolean, region: Rect? = null): Boolean {
        lens.setCaptureHidden(hidden, region)
        return lens.hasContent
    }

    fun showStatus(message: String) {
        if (prefs.lensMode) {
            // No text bar: message in the reply line, otherwise briefly as a toast.
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

    /** No more text in the frame. Before the first text, the hint stays. */
    fun showIdle() {
        if (!hasShownText) return
        if (prefs.lensMode) return
        showStatus(ctx.getString(R.string.bar_idle))
    }

    fun setActive(active: Boolean) {
        frameView.active = active
    }

    // ---------------------------------------------------------------- Reply line

    fun setComposerEnabled(enabled: Boolean) {
        if (!attached) return
        if (enabled) composer.attach() else composer.detach()
        layoutAll()
    }

    fun setReplyLanguages(own: String, reply: String) = composer.setLanguages(own, reply)
    fun composerPreview(forText: String, translated: String) = composer.showPreview(forText, translated)
    fun composerStatus(message: String) = composer.showStatus(message)
    fun composerCopied() = composer.showCopied()

    // ---------------------------------------------------------------- Quick settings

    private fun togglePanel() {
        if (panelView != null) {
            closePanel()
            return
        }
        // Tapping ⚙️ already closes the panel via "touched outside" – don't reopen it right away.
        if (SystemClock.uptimeMillis() - panelClosedAt < 350) return
        openPanel(null)
    }

    /** Opens the quick settings, optionally straight in a language list. */
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
        // Little height in landscape – then the page scrolls.
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
        panel.findViewById<View>(R.id.btn_swap_langs).setOnClickListener {
            if (prefs.swapLanguages(detectedLang)) {
                detectedLang = null
                from.text = Languages.displayName(prefs.sourceLang)
                to.text = Languages.displayName(prefs.targetLang)
                updateChip()
            } else {
                toast(ctx.getString(R.string.swap_langs_impossible))
            }
        }
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
        // "Bar position" only in bar mode – in the lens there is no bar.
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
        private const val HANDLE_W_DP = 34f
        private const val HANDLE_H_DP = 12f
        private const val CONTROL_DP = 22f
        private const val EDGE_DP = 8f
        /** Distance of handles and buttons from the window edge (they sit inside the frame). */
        private const val INNER_DP = 5f
    }
}
