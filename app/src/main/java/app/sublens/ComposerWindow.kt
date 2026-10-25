package app.sublens

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Antwortzeile am unteren Ende des Overlays: in der eigenen Sprache schreiben,
 * Übersetzung in die Sprache des Chats landet in der Zwischenablage.
 *
 * Das Fenster ist normalerweise nicht fokussierbar (damit Tastatur und Zurück-Taste
 * bei der App darunter bleiben). Erst beim Antippen des Eingabefelds wird es kurz
 * fokussierbar und holt die Tastatur; nach dem Kopieren oder Tippen daneben wieder zurück.
 */
class ComposerWindow(
    private val ctx: Context,
    private val wm: WindowManager,
    private val listener: Listener,
) {
    interface Listener {
        fun onPreviewRequested(text: String)
        fun onCopyRequested(text: String)
        fun onReplyLanguageTapped()
        fun onPeekTapped()
        fun onComposerHeightChanged()
    }

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).roundToInt()
    private val imm: InputMethodManager = ctx.getSystemService(InputMethodManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    val view: View = LayoutInflater.from(ctx).inflate(R.layout.overlay_composer, null)
    private val input: EditText = view.findViewById(R.id.composer_input)
    private val preview: TextView = view.findViewById(R.id.composer_preview)
    private val chip: TextView = view.findViewById(R.id.composer_chip)
    private val copyButton: ImageButton = view.findViewById(R.id.composer_copy)
    private val peekButton: TextView = view.findViewById(R.id.composer_peek)

    private val colorMain = ctx.getColor(R.color.text_main)
    private val colorDim = ctx.getColor(R.color.text_dim)
    private val colorAccent = ctx.getColor(R.color.accent)

    @Suppress("DEPRECATION") // ADJUST_RESIZE nur noch für Android < 11 relevant
    val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        if (Build.VERSION.SDK_INT >= 30) {
            // Nur der Tastatur ausweichen: das Fenster rutscht beim Tippen über die Tastatur.
            fitInsetsTypes = WindowInsets.Type.ime()
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    var attached = false
        private set
    var editing = false
        private set

    /** Höhe im Ruhezustand – für die Anordnung des Overlays. */
    var stableHeight = 0
        private set

    private var normalY = 0
    private var imeShift = 0
    private var hadFocus = false
    private var ownLang = "de"
    private var replyLang = "en"

    private val previewRunnable = Runnable {
        val text = currentText()
        if (text.isNotEmpty()) listener.onPreviewRequested(text)
    }
    private val resetRunnable = Runnable { if (currentText().isEmpty()) showPlaceholder() }

    init {
        input.setHorizontallyScrolling(false)
        input.maxLines = 3
        setupInput()
        copyButton.setOnClickListener { requestCopy() }
        peekButton.setOnClickListener { listener.onPeekTapped() }
        chip.setOnClickListener {
            stopEditing()
            listener.onReplyLanguageTapped()
        }
        view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (!editing && bottom - top != oldBottom - oldTop) {
                stableHeight = bottom - top
                listener.onComposerHeightChanged()
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (!editing) return@addOnWindowFocusChangeListener
            if (hasFocus) {
                hadFocus = true
                input.requestFocus()
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            } else if (hadFocus) {
                // In die App darunter getippt → Tastatur gehört wieder ihr.
                stopEditing()
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // Sicherheitsnetz: falls das System das Fenster nicht über die Tastatur schiebt, selbst nachrücken.
            view.setOnApplyWindowInsetsListener { v, insets ->
                if (editing) {
                    val overlap = insets.getInsets(WindowInsets.Type.ime()).bottom
                    if (overlap > 0 && normalY - imeShift > dp(24f)) {
                        imeShift += overlap + dp(8f)
                        applyPosition()
                    }
                }
                v.onApplyWindowInsets(insets)
            }
        }
        showPlaceholder()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInput() {
        input.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !editing) startEditing()
            false
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                requestCopy()
                true
            } else {
                false
            }
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && editing) {
                stopEditing()
                true
            } else {
                false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = schedulePreview()
        })
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && editing) stopEditing()
            false
        }
    }

    // ---------------------------------------------------------------- Fenster

    fun attach() {
        if (attached) return
        try {
            wm.addView(view, params)
            attached = true
        } catch (_: Exception) {
            attached = false
        }
    }

    fun detach() {
        stopEditing()
        main.removeCallbacks(previewRunnable)
        main.removeCallbacks(resetRunnable)
        if (!attached) return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        }
        attached = false
    }

    /** Position im Ruhezustand (direkt unter dem restlichen Overlay). */
    fun place(y: Int, width: Int) {
        normalY = y
        params.width = width
        applyPosition()
    }

    private fun applyPosition() {
        params.x = 0
        params.y = when {
            // Vor Android 11 weicht das Fenster der Tastatur nicht zuverlässig aus → beim Tippen oben anzeigen.
            editing && Build.VERSION.SDK_INT < 30 -> dp(56f)
            else -> (normalY - imeShift).coerceAtLeast(0)
        }
        if (!attached) return
        try {
            wm.updateViewLayout(view, params)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------------------------------------------------------------- Bearbeiten

    private fun startEditing() {
        editing = true
        hadFocus = false
        imeShift = 0
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        applyPosition()
        input.requestFocus()
    }

    fun stopEditing() {
        if (!editing) return
        editing = false
        imeShift = 0
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        applyPosition()
    }

    private fun requestCopy() {
        val text = currentText()
        if (text.isEmpty()) return
        main.removeCallbacks(previewRunnable)
        showStatus(ctx.getString(R.string.composer_translating))
        listener.onCopyRequested(text)
    }

    private fun schedulePreview() {
        main.removeCallbacks(previewRunnable)
        main.removeCallbacks(resetRunnable)
        if (currentText().isEmpty()) {
            showPlaceholder()
        } else {
            main.postDelayed(previewRunnable, PREVIEW_DELAY_MS)
        }
    }

    private fun currentText(): String = input.text?.toString()?.trim().orEmpty()

    // ---------------------------------------------------------------- Anzeige

    fun setLanguages(own: String, reply: String) {
        val changed = own != ownLang || reply != replyLang
        ownLang = own
        replyLang = reply
        chip.text = "${Languages.shortCode(own)} → ${Languages.shortCode(reply)}"
        input.hint = ctx.getString(R.string.composer_hint, Languages.displayName(own))
        if (currentText().isEmpty()) showPlaceholder() else if (changed) schedulePreview()
    }

    /** Vorschau-Knopf hervorheben, solange das Original gezeigt wird. */
    fun setPeekActive(active: Boolean) {
        peekButton.isSelected = active
        peekButton.setTextColor(if (active) colorAccent else colorMain)
    }

    /** Live-Vorschau; wird ignoriert, wenn inzwischen etwas anderes im Feld steht. */
    fun showPreview(forText: String, translated: String) {
        if (forText != currentText()) return
        setLine(translated, colorMain)
    }

    fun showStatus(message: String) {
        setLine(message, colorDim)
    }

    /** Statusmeldung, die nach ein paar Sekunden von selbst verschwindet. */
    fun showTimedStatus(message: String, ms: Long) {
        setLine(message, colorDim)
        main.removeCallbacks(resetRunnable)
        main.postDelayed(resetRunnable, ms)
    }

    /** Statusmeldung entfernen, sofern gerade nichts getippt wird. */
    fun clearStatus() {
        if (currentText().isEmpty()) showPlaceholder()
    }

    private fun setLine(text: String, color: Int) {
        preview.setTextColor(color)
        preview.text = text
        preview.visibility = View.VISIBLE
    }

    fun showCopied() {
        main.removeCallbacks(previewRunnable)
        input.setText("")
        setLine(ctx.getString(R.string.composer_copied, Languages.displayName(replyLang)), colorAccent)
        stopEditing()
        main.removeCallbacks(resetRunnable)
        main.postDelayed(resetRunnable, COPIED_HINT_MS)
    }

    /** Nichts zu zeigen: Zeile ausblenden, damit die Antwortzeile schmal bleibt. */
    private fun showPlaceholder() {
        preview.text = ""
        preview.visibility = View.GONE
    }

    companion object {
        private const val PREVIEW_DELAY_MS = 550L
        private const val COPIED_HINT_MS = 3500L
    }
}
