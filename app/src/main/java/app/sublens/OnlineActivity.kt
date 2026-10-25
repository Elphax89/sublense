package app.sublens

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Online translation catalog: one card per service with "Get key", "Paste", "Test" and the model.
 * "Paste key from clipboard" at the top recognises which service a key belongs to and tests it right away.
 */
class OnlineActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var master: Switch
    private val cards = LinkedHashMap<String, Card>()
    private val knownModels = HashMap<String, List<String>>()
    private var expanded: String? = null

    private class Card(val p: Provider, val root: View) {
        val header: View = root.findViewById(R.id.header)
        val state: TextView = root.findViewById(R.id.state)
        val expandIcon: ImageView = root.findViewById(R.id.expand)
        val details: View = root.findViewById(R.id.details)
        val getKey: Button = root.findViewById(R.id.btn_get_key)
        val paste: Button = root.findViewById(R.id.btn_paste)
        val url: EditText = root.findViewById(R.id.url)
        val key: EditText = root.findViewById(R.id.key)
        val model: TextView = root.findViewById(R.id.model)
        val modelButton: Button = root.findViewById(R.id.btn_model)
        val status: TextView = root.findViewById(R.id.status)
        val test: Button = root.findViewById(R.id.btn_test)
        val remove: Button = root.findViewById(R.id.btn_remove)
        val use: Button = root.findViewById(R.id.btn_use)
        var testing = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_online)
        prefs = Prefs(this)
        scroll = findViewById(R.id.scroll)
        list = findViewById(R.id.providers)
        master = findViewById(R.id.switch_online)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        master.isChecked = prefs.onlineEnabled
        master.setOnCheckedChangeListener { _, checked ->
            prefs.onlineEnabled = checked
            if (checked) OnlineTranslator.resume()
            refreshAll()
        }
        findViewById<View>(R.id.btn_paste_any).setOnClickListener { pasteAny() }

        val inflater = LayoutInflater.from(this)
        for (p in Providers.all) {
            val card = Card(p, inflater.inflate(R.layout.item_provider, list, false))
            bind(card)
            cards[p.id] = card
            list.addView(card.root)
        }
        expanded = intent.getStringExtra(EXTRA_PROVIDER)
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        // Keep what was typed even without testing.
        for (c in cards.values) saveFields(c)
    }

    // ---------------------------------------------------------------- Cards

    private fun bind(c: Card) {
        val p = c.p
        c.root.findViewById<TextView>(R.id.name).text = p.name
        c.root.findViewById<TextView>(R.id.desc).setText(p.descRes)
        c.root.findViewById<TextView>(R.id.data).text = "ⓘ " + getString(p.dataRes)

        val badges = c.root.findViewById<LinearLayout>(R.id.badges)
        if (p.badges.isEmpty()) badges.visibility = View.GONE
        for (res in p.badges) {
            badges.addView(
                TextView(this).apply {
                    setText(res)
                    textSize = 12f
                    setTextColor(getColor(R.color.accent))
                    setBackgroundResource(R.drawable.bg_badge)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(6) },
            )
        }

        c.header.setOnClickListener {
            expanded = if (expanded == p.id) null else p.id
            refreshAll()
        }

        if (p.keyUrl.isBlank()) {
            c.getKey.visibility = View.GONE
            (c.paste.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }
        c.getKey.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)))
            } catch (_: ActivityNotFoundException) {
                toast(getString(R.string.online_no_browser))
            }
        }
        c.paste.setOnClickListener { pasteInto(c) }

        val urlViews = listOf<View>(c.root.findViewById(R.id.label_url), c.url, c.root.findViewById(R.id.note_url))
        if (p.editableUrl) {
            c.url.setText(prefs.onlineUrl(p.id) ?: p.baseUrl)
            c.url.hint = if (p.id == Providers.CUSTOM) getString(R.string.online_url_hint_custom) else p.baseUrl
            c.root.findViewById<TextView>(R.id.note_url).setText(
                if (p.id == Providers.CUSTOM) R.string.online_url_note_custom else R.string.online_url_note_qwen,
            )
        } else {
            urlViews.forEach { it.visibility = View.GONE }
        }

        c.key.setText(OnlineTranslator.key(this, p.id) ?: "")
        if (p.id == Providers.CUSTOM) c.key.setHint(R.string.online_key_hint_optional)

        c.modelButton.setOnClickListener { chooseModel(c) }
        c.test.setOnClickListener {
            saveFields(c)
            runTest(c)
        }
        c.remove.setOnClickListener {
            OnlineTranslator.setKey(this, p.id, null)
            c.key.setText("")
            knownModels.remove(p.id)
            c.status.visibility = View.GONE
            refreshAll()
        }
        c.use.setOnClickListener { use(c) }
    }

    private fun refreshAll() {
        refreshState()
        for (c in cards.values) refreshCard(c)
    }

    private fun refreshState() {
        val state = findViewById<TextView>(R.id.online_state)
        val p = Providers.byId(prefs.onlineProvider)
        state.text = when {
            !prefs.onlineEnabled -> getString(R.string.online_state_off)
            p == null || !OnlineTranslator.isConfigured(this, p) -> getString(R.string.online_need_setup)
            else -> getString(R.string.online_state_on, OnlineTranslator.summary(this) ?: p.name)
        }
        if (master.isChecked != prefs.onlineEnabled) master.isChecked = prefs.onlineEnabled
    }

    private fun refreshCard(c: Card) {
        val p = c.p
        val inUse = prefs.onlineEnabled && prefs.onlineProvider == p.id && OnlineTranslator.isConfigured(this, p)
        val configured = OnlineTranslator.isConfigured(this, p)
        val hasKey = OnlineTranslator.key(this, p.id) != null
        c.root.isActivated = inUse
        when {
            inUse -> c.state.setStateText(getString(R.string.online_in_use), R.color.accent)
            prefs.onlineVerified(p.id) && configured -> c.state.setStateText(getString(R.string.online_ready), R.color.ok)
            hasKey || (p.id == Providers.CUSTOM && configured) -> c.state.setStateText(getString(R.string.online_untested), R.color.text_dim)
            else -> c.state.setStateText("", R.color.text_dim)
        }
        val open = expanded == p.id
        c.details.visibility = if (open) View.VISIBLE else View.GONE
        c.expandIcon.rotation = if (open) 180f else 0f
        c.model.text = prefs.onlineModel(p.id) ?: p.defaultModel.ifBlank { getString(R.string.online_model_none) }
        c.remove.visibility = if (hasKey) View.VISIBLE else View.GONE
        c.use.visibility = if (inUse) View.GONE else View.VISIBLE
    }

    private fun TextView.setStateText(text: String, color: Int) {
        this.text = text
        setTextColor(getColor(color))
    }

    /** Writes the typed key and server address (only if they changed). */
    private fun saveFields(c: Card) {
        val p = c.p
        if (p.editableUrl) {
            val u = c.url.text.toString().trim().trimEnd('/')
            if (u != (prefs.onlineUrl(p.id) ?: p.baseUrl)) {
                prefs.setOnlineUrl(p.id, if (u == p.baseUrl) null else u)
                prefs.setOnlineVerified(p.id, false)
            }
        }
        val k = cleanKey(c.key.text.toString())
        if (k != (OnlineTranslator.key(this, p.id) ?: "")) OnlineTranslator.setKey(this, p.id, k.ifEmpty { null })
    }

    private fun use(c: Card) {
        saveFields(c)
        if (!OnlineTranslator.isConfigured(this, c.p)) {
            showStatus(c, getString(if (c.p.id == Providers.CUSTOM) R.string.online_err_model_custom else R.string.online_err_no_key), ok = false)
            return
        }
        prefs.onlineProvider = c.p.id
        prefs.onlineEnabled = true
        OnlineTranslator.resume()
        refreshAll()
        if (!prefs.onlineVerified(c.p.id)) runTest(c)
    }

    // ---------------------------------------------------------------- Test

    private fun runTest(c: Card, then: ((Boolean, OnlineClient.Failed?) -> Unit)? = null) {
        if (c.testing) return
        c.testing = true
        c.test.isEnabled = false
        c.test.setText(R.string.online_testing)
        showStatus(c, getString(R.string.online_testing), ok = null)
        OnlineTranslator.test(this, c.p) { r ->
            if (isFinishing || isDestroyed) return@test
            c.testing = false
            c.test.isEnabled = true
            c.test.setText(R.string.online_test)
            if (r.models.isNotEmpty()) knownModels[c.p.id] = r.models
            if (r.ok) {
                showStatus(
                    c,
                    getString(R.string.online_test_ok, r.model ?: "") + "\n" + getString(R.string.online_sample, r.sampleIn, r.sampleOut ?: ""),
                    ok = true,
                )
                // Tested successfully = that's the service you want to use.
                prefs.onlineProvider = c.p.id
                prefs.onlineEnabled = true
                toast(getString(R.string.online_enabled_toast, c.p.name))
            } else {
                val f = r.failure ?: OnlineClient.Failed(OnlineClient.Failure.SERVER, "?")
                showStatus(c, "✗ " + OnlineTranslator.errorMessage(this, c.p, f), ok = false)
            }
            refreshAll()
            then?.invoke(r.ok, r.failure)
        }
    }

    private fun showStatus(c: Card, text: String, ok: Boolean?) {
        c.status.visibility = View.VISIBLE
        c.status.text = text
        c.status.setTextColor(getColor(if (ok == true) R.color.ok else if (ok == false) R.color.danger else R.color.text_dim))
    }

    // ---------------------------------------------------------------- Paste

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** Keys never contain spaces – remove line breaks, quotes and a copied "Bearer ". */
    private fun cleanKey(raw: String): String =
        raw.trim().removePrefix("Bearer ").removePrefix("bearer ").trim('"', '\'', ' ', '\n', '\t').replace(Regex("\\s+"), "")

    private fun pasteAny() {
        val text = clipboardText() ?: return toast(getString(R.string.online_paste_empty))
        val k = cleanKey(text)
        val candidates = Providers.guess(k)
        if (candidates.isEmpty()) {
            toast(getString(R.string.online_paste_unknown), long = true)
            return
        }
        tryKey(k, candidates, 0)
    }

    /** Same key shape at several services (DeepSeek and Qwen): try them one after another. */
    private fun tryKey(k: String, candidates: List<Provider>, index: Int) {
        val p = candidates[index]
        val c = cards[p.id] ?: return
        OnlineTranslator.setKey(this, p.id, k)
        c.key.setText(k)
        expanded = p.id
        refreshAll()
        scrollTo(c)
        toast(getString(R.string.online_paste_found, p.name))
        runTest(c) { ok, failure ->
            if (!ok && failure?.kind == OnlineClient.Failure.AUTH && index + 1 < candidates.size) {
                OnlineTranslator.setKey(this, p.id, null)
                c.key.setText("")
                c.status.visibility = View.GONE
                tryKey(k, candidates, index + 1)
            }
        }
    }

    private fun pasteInto(c: Card) {
        val text = clipboardText() ?: return toast(getString(R.string.online_paste_empty))
        c.key.setText(cleanKey(text))
        saveFields(c)
        runTest(c)
    }

    private fun scrollTo(c: Card) {
        scroll.post { scroll.smoothScrollTo(0, (list.top + c.root.top - dp(12)).coerceAtLeast(0)) }
    }

    // ---------------------------------------------------------------- Model

    private fun chooseModel(c: Card) {
        saveFields(c)
        val known = knownModels[c.p.id]
        if (!known.isNullOrEmpty()) {
            showModelList(c, known)
            return
        }
        c.modelButton.isEnabled = false
        OnlineTranslator.models(this, c.p) { models, failure ->
            if (isFinishing || isDestroyed) return@models
            c.modelButton.isEnabled = true
            when {
                !models.isNullOrEmpty() -> {
                    knownModels[c.p.id] = models
                    showModelList(c, models)
                }
                failure != null && failure.kind in setOf(
                    OnlineClient.Failure.NO_KEY,
                    OnlineClient.Failure.AUTH,
                    OnlineClient.Failure.NETWORK,
                ) -> showStatus(c, "✗ " + OnlineTranslator.errorMessage(this, c.p, failure), ok = false)
                else -> showModelInput(c) // no model list – type the name
            }
        }
    }

    private fun showModelList(c: Card, models: List<String>) {
        val usable = Providers.usableModels(c.p, models)
        val items = (usable + getString(R.string.online_model_other)).toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.online_model)
            .setItems(items) { _, which ->
                if (which == usable.size) showModelInput(c) else setModel(c, usable[which])
            }
            .show()
    }

    private fun showModelInput(c: Card) {
        val input = EditText(this).apply {
            setText(prefs.onlineModel(c.p.id) ?: c.p.defaultModel)
            setHint(R.string.online_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.online_model)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val m = input.text.toString().trim()
                if (m.isNotEmpty()) setModel(c, m)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setModel(c: Card, model: String) {
        prefs.setOnlineModel(c.p.id, model)
        prefs.setOnlineVerified(c.p.id, false)
        refreshAll()
        runTest(c)
    }

    // ---------------------------------------------------------------- Helpers

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Open this provider's card right away. */
        const val EXTRA_PROVIDER = "provider"
    }
}
