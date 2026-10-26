package app.sublens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * Home screen: header with the app language, start area (start, camera, online AI), then the settings in the
 * same order as the desktop panel – Language, Translation, Lens, Offline language packs – and updates.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val sources = Languages.sources()
    private val targets = Languages.targets()
    private val scripts = Script.entries

    private lateinit var btnStart: Button
    private var selectEngine: ((Int) -> Unit)? = null
    private var installedPacks: Set<String> = emptySet()
    private var modelChoice: String = ""

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Languages.loadLabels(this)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        btnStart = findViewById(R.id.btn_start)
        modelChoice = prefs.targetLang

        setupAppLanguage()
        setupPermissions()
        setupStartArea()
        setupLanguages()
        setupTranslation()
        setupLens()
        setupModels()
        setupUpdates()

        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !hasNotificationPermission()) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        OverlayService.stateListener = { refreshStartButton() }
        refreshPermissions()
        refreshStartButton()
        refreshLanguages()
        refreshTranslation()
        // The lens has its own video button – show what it is set to now.
        findViewById<Switch>(R.id.switch_video).isChecked = prefs.videoMode
        refreshModels()
        if (!Updater.downloading) Updater.check(this, force = false) { release, _ -> showUpdate(release) }
    }

    override fun onPause() {
        super.onPause()
        OverlayService.stateListener = null
    }

    // ---------------------------------------------------------------- App language (header)

    private fun setupAppLanguage() {
        val current = AppLanguage.current(this)
        findViewById<TextView>(R.id.app_lang_flag).text = current.flag
        findViewById<TextView>(R.id.app_lang_code).text = AppLanguage.shortCode(current)
        findViewById<View>(R.id.btn_app_lang).setOnClickListener {
            val options = AppLanguage.options
            val chosen = AppLanguage.chosen(this)
            Picker.show(
                this,
                getString(R.string.app_language),
                options.map { o ->
                    if (o.tag.isEmpty()) Picker.Item(getString(R.string.app_language_system), o.flag) else Picker.Item(o.nativeName, o.flag)
                },
                options.indexOfFirst { it.tag == chosen }.coerceAtLeast(0),
            ) { i ->
                val tag = options[i].tag
                if (tag != chosen) {
                    AppLanguage.set(this, tag)
                    // The lens may be open behind the app: it follows the new language right away.
                    if (OverlayService.isRunning) {
                        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RELOCALIZE))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Start area

    private fun setupStartArea() {
        btnStart.setOnClickListener { onStartClicked() }
        val how = findViewById<TextView>(R.id.how_text)
        findViewById<View>(R.id.row_how).setOnClickListener {
            how.visibility = if (how.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btn_camera).setOnClickListener { startActivity(Intent(this, CameraActivity::class.java)) }
        findViewById<View>(R.id.btn_online_tile).setOnClickListener { openOnline() }
    }

    private fun onStartClicked() {
        if (OverlayService.isRunning) {
            btnStart.isEnabled = false
            btnStart.setText(R.string.stopping)
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            // In case the service doesn't report back: refresh by then at the latest.
            btnStart.postDelayed({ refreshStartButton() }, 1500)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            // Always the entire screen – otherwise the frame coordinates don't match.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        startActivityForResult(captureIntent, REQ_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.toast_need_capture, Toast.LENGTH_LONG).show()
            return
        }
        val service = Intent(this, OverlayService::class.java)
            .setAction(OverlayService.ACTION_START)
            .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
        startForegroundService(service)
        Toast.makeText(this, R.string.toast_running, Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun refreshStartButton() {
        btnStart.isEnabled = true
        if (OverlayService.isRunning) {
            btnStart.setText(R.string.stop)
            btnStart.setBackgroundResource(R.drawable.bg_button_danger)
        } else {
            btnStart.setText(R.string.start)
            btnStart.setBackgroundResource(R.drawable.bg_button_primary)
        }
    }

    // ---------------------------------------------------------------- Permissions (card only while something is missing)

    private fun setupPermissions() {
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
        }
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun refreshPermissions() {
        val overlayOk = Settings.canDrawOverlays(this)
        val notifOk = hasNotificationPermission()
        findViewById<TextView>(R.id.perm_overlay_status).setText(R.string.perm_overlay_missing)
        findViewById<TextView>(R.id.perm_notif_status).setText(R.string.perm_notif_missing)
        findViewById<View>(R.id.row_overlay).visibility = if (overlayOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.row_notif).visibility = if (notifOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.card_permissions).visibility = if (overlayOk && notifOk) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    // ---------------------------------------------------------------- Language

    private fun langLabel(tag: String) = "${Languages.flag(tag)}  ${Languages.displayName(tag)}"

    private fun langItems(tags: List<String>, autoLabel: String? = null): List<Picker.Item> = tags.map { tag ->
        if (tag == Languages.AUTO) {
            Picker.Item(autoLabel ?: Languages.displayName(tag), Languages.flag(tag))
        } else {
            Picker.Item(Languages.displayName(tag), Languages.flag(tag))
        }
    }

    private fun setupLanguages() {
        findViewById<View>(R.id.pill_source).setOnClickListener {
            Picker.show(this, getString(R.string.label_from), langItems(sources), sources.indexOf(prefs.sourceLang)) { i ->
                prefs.sourceLang = sources[i]
                refreshLanguages()
            }
        }
        findViewById<View>(R.id.pill_target).setOnClickListener {
            Picker.show(this, getString(R.string.label_to), langItems(targets), targets.indexOf(prefs.targetLang)) { i ->
                prefs.targetLang = targets[i]
                refreshLanguages()
            }
        }
        findViewById<View>(R.id.row_script).setOnClickListener {
            Picker.show(
                this,
                getString(R.string.label_script),
                scripts.map { Picker.Item(getString(it.labelRes)) },
                scripts.indexOf(prefs.autoScript),
            ) { i ->
                prefs.autoScript = scripts[i]
                refreshLanguages()
            }
        }
        val replies = listOf(Languages.AUTO) + targets
        findViewById<View>(R.id.row_reply).setOnClickListener {
            Picker.show(
                this,
                getString(R.string.reply_in),
                langItems(replies, autoLabel = getString(R.string.reply_auto)),
                replies.indexOf(prefs.replyLang),
            ) { i ->
                prefs.replyLang = replies[i]
                refreshLanguages()
            }
        }
        findViewById<View>(R.id.btn_swap_langs).setOnClickListener { v ->
            if (prefs.swapLanguages(null)) {
                v.animate().rotationBy(180f).setDuration(220).start()
                refreshLanguages()
            } else {
                Toast.makeText(this, R.string.swap_langs_impossible, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshLanguages() {
        findViewById<TextView>(R.id.value_source).text = langLabel(prefs.sourceLang)
        findViewById<TextView>(R.id.value_target).text = langLabel(prefs.targetLang)
        findViewById<View>(R.id.row_script).visibility = if (prefs.sourceLang == Languages.AUTO) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.value_script).setText(prefs.autoScript.labelRes)
        findViewById<TextView>(R.id.value_reply).text =
            if (prefs.replyLang == Languages.AUTO) getString(R.string.reply_auto) else langLabel(prefs.replyLang)
    }

    // ---------------------------------------------------------------- Translation (offline / online)

    private fun setupTranslation() {
        selectEngine = segmented(
            R.id.seg_engine,
            listOf("📱  " + getString(R.string.engine_offline_short), "✨  " + getString(R.string.tile_online)),
            if (prefs.onlineEnabled) 1 else 0,
        ) { i ->
            val online = i == 1
            if (online != prefs.onlineEnabled) {
                prefs.onlineEnabled = online
                if (online) {
                    OnlineTranslator.resume()
                    // Not set up yet → straight to the catalog.
                    if (!onlineConfigured()) openOnline()
                }
            }
            refreshTranslation()
        }
        findViewById<View>(R.id.row_online).setOnClickListener { openOnline() }
    }

    private fun onlineConfigured(): Boolean {
        val p = Providers.byId(prefs.onlineProvider) ?: return false
        return OnlineTranslator.isConfigured(this, p)
    }

    private fun openOnline() = startActivity(Intent(this, OnlineActivity::class.java))

    private fun refreshTranslation() {
        val p = Providers.byId(prefs.onlineProvider)
        val configured = onlineConfigured()
        val on = prefs.onlineEnabled
        selectEngine?.invoke(if (on) 1 else 0)
        findViewById<TextView>(R.id.engine_status).text = when {
            !on -> getString(R.string.engine_status_offline)
            !configured -> getString(R.string.engine_status_not_set)
            else -> getString(R.string.engine_status_online, OnlineTranslator.summary(this) ?: p!!.name)
        }
        findViewById<TextView>(R.id.value_online).text = if (configured) p!!.name else getString(R.string.tile_online_setup)
        // Tile in the start area
        findViewById<TextView>(R.id.online_tile_state).text = when {
            on && configured -> p!!.name
            !configured -> getString(R.string.tile_online_cta)
            else -> getString(R.string.tile_online_off)
        }
        findViewById<View>(R.id.online_dot).background = if (on && configured) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.ok))
            }
        } else {
            null
        }
        findViewById<TextView>(R.id.footer).text =
            if (on && configured) getString(R.string.footer_online, p!!.name) else getString(R.string.footer)
    }

    // ---------------------------------------------------------------- Lens

    private fun setupLens() {
        setupLensColors()

        segmented(
            R.id.seg_mode,
            listOf(getString(R.string.choice_lens), getString(R.string.choice_bar)),
            if (prefs.lensMode) 0 else 1,
        ) { i ->
            prefs.lensMode = i == 0
            refreshMode()
        }
        segmented(
            R.id.seg_bar_pos,
            listOf(getString(R.string.choice_above), getString(R.string.choice_below)),
            if (prefs.barAbove) 0 else 1,
        ) { i -> prefs.barAbove = i == 0 }
        refreshMode()

        val value = findViewById<TextView>(R.id.value_text_size)
        val seek = findViewById<SeekBar>(R.id.seek_text_size)
        seek.max = MAX_TEXT_SP - MIN_TEXT_SP
        seek.progress = (prefs.textSizeSp - MIN_TEXT_SP).coerceIn(0, seek.max)
        value.text = getString(R.string.text_size_value, prefs.textSizeSp)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = MIN_TEXT_SP + progress
                value.text = getString(R.string.text_size_value, size)
                if (fromUser) prefs.textSizeSp = size
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        switchRow(R.id.row_original, R.id.switch_original, prefs.showOriginal) { prefs.showOriginal = it }
        switchRow(R.id.row_video, R.id.switch_video, prefs.videoMode) { prefs.videoMode = it }
        switchRow(R.id.row_composer, R.id.switch_composer, prefs.composerEnabled) { prefs.composerEnabled = it }

        segmented(
            R.id.seg_speed,
            listOf(getString(R.string.speed_fast), getString(R.string.speed_normal), getString(R.string.speed_saver)),
            when {
                prefs.intervalMs <= 350L -> 0
                prefs.intervalMs >= 900L -> 2
                else -> 1
            },
        ) { i ->
            prefs.intervalMs = when (i) {
                0 -> 300L
                2 -> 1000L
                else -> 500L
            }
        }
    }

    /** "Bar position" only exists when the translation is shown in the bar. */
    private fun refreshMode() {
        findViewById<View>(R.id.box_bar_pos).visibility = if (prefs.lensMode) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.mode_hint).setText(if (prefs.lensMode) R.string.mode_lens_long else R.string.mode_bar_long)
    }

    /** Colour circles for the lens frame and handles. */
    private fun setupLensColors() {
        val row = findViewById<LinearLayout>(R.id.row_lens_color)
        val d = resources.displayMetrics.density
        // All nine colours in one row: screen minus page and card padding, a gap after each.
        val gap = (8 * d).toInt()
        val available = resources.displayMetrics.widthPixels - (64 * d).toInt() - (6 * d).toInt()
        val size = ((available - Prefs.LENS_COLORS.size * gap) / Prefs.LENS_COLORS.size).coerceIn((24 * d).toInt(), (34 * d).toInt())
        fun render() {
            for (i in 0 until row.childCount) {
                val c = Prefs.LENS_COLORS[i]
                val selected = c == prefs.lensColor
                val v = row.getChildAt(i)
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke((if (selected) 3 * d else 1 * d).toInt(), if (selected) 0xFFFFFFFF.toInt() else 0x55000000)
                }
                v.scaleX = if (selected) 1.12f else 1f
                v.scaleY = v.scaleX
            }
        }
        row.removeAllViews()
        for (c in Prefs.LENS_COLORS) {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
            v.contentDescription = getString(R.string.label_lens_color)
            v.setOnClickListener {
                prefs.lensColor = c
                render()
            }
            row.addView(v)
        }
        render()
    }

    // ---------------------------------------------------------------- Building blocks

    /**
     * Fills a segmented control with [labels]. Returns a function that changes the selection without
     * calling [onSelect] (for refreshing from the settings).
     */
    private fun segmented(id: Int, labels: List<String>, selected: Int, onSelect: (Int) -> Unit): (Int) -> Unit {
        val container = findViewById<LinearLayout>(id)
        container.removeAllViews()
        val views = labels.map { label ->
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                maxLines = 2
                minHeight = dp(42)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setBackgroundResource(R.drawable.bg_segment_item)
            }
        }
        val select: (Int) -> Unit = { index ->
            views.forEachIndexed { k, v ->
                v.isSelected = k == index
                v.setTextColor(getColor(if (k == index) R.color.on_accent else R.color.text_main))
                v.typeface = if (k == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                select(i)
                onSelect(i)
            }
            container.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected)
        return select
    }

    /** A row with a switch: tapping anywhere on the row toggles it. */
    private fun switchRow(rowId: Int, switchId: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(switchId)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        findViewById<View>(rowId).setOnClickListener { sw.toggle() }
    }

    // ---------------------------------------------------------------- Offline language packs

    private fun setupModels() {
        val details = findViewById<View>(R.id.models_details)
        val arrow = findViewById<View>(R.id.models_expand)
        findViewById<View>(R.id.row_models).setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (open) 180f else 0f).setDuration(150).start()
        }
        switchRow(R.id.row_wifi, R.id.switch_wifi, prefs.wifiOnly) { prefs.wifiOnly = it }

        // Any language can be downloaded by hand, e.g. before a trip without internet.
        findViewById<View>(R.id.pill_model).setOnClickListener {
            Picker.show(
                this,
                getString(R.string.models_pick),
                targets.map { tag ->
                    val name = Languages.displayName(tag)
                    val label = if (Languages.mlkit(tag) in installedPacks) getString(R.string.models_installed_mark, name) else name
                    Picker.Item(label, Languages.flag(tag))
                },
                targets.indexOf(modelChoice),
            ) { i ->
                modelChoice = targets[i]
                updateModelChoice()
            }
        }
        updateModelChoice()

        val btn = findViewById<Button>(R.id.btn_download_model)
        btn.setOnClickListener {
            val lang = modelChoice.takeIf { it in targets } ?: return@setOnClickListener
            btn.isEnabled = false
            btn.setText(R.string.models_downloading)
            val conditions = DownloadConditions.Builder().apply { if (prefs.wifiOnly) requireWifi() }.build()
            val model = TranslateRemoteModel.Builder(Languages.mlkit(lang)).build()
            RemoteModelManager.getInstance().download(model, conditions)
                .addOnSuccessListener { toast(R.string.models_download_done) }
                .addOnFailureListener { toast(R.string.models_download_failed) }
                .addOnCompleteListener {
                    btn.isEnabled = true
                    btn.setText(R.string.models_download)
                    refreshModels()
                }
        }
    }

    private fun updateModelChoice() {
        val name = langLabel(modelChoice)
        findViewById<TextView>(R.id.value_model).text =
            if (Languages.mlkit(modelChoice) in installedPacks) getString(R.string.models_installed_mark, name) else name
    }

    private fun refreshModels() {
        val list = findViewById<LinearLayout>(R.id.models_list)
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                list.removeAllViews()
                val langs = models.map { it.language }.sortedBy { Languages.packName(it) }
                installedPacks = langs.toSet()
                updateModelChoice()
                findViewById<TextView>(R.id.value_models).text =
                    if (langs.isEmpty()) getString(R.string.models_none_short) else getString(R.string.models_count, langs.size)
                if (langs.isEmpty()) {
                    list.addView(textRow(getString(R.string.models_empty), dim = true))
                    return@addOnSuccessListener
                }
                for (lang in langs) list.addView(modelRow(lang))
            }
    }

    private fun modelRow(lang: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(44)
        }
        val name = textRow("${Languages.flag(lang)}  ${Languages.packName(lang)}", dim = false)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // English is the base pack (pivot language) and always stays installed.
        if (lang != TranslateLanguage.ENGLISH) {
            val delete = TextView(this).apply {
                setText(R.string.models_delete)
                setTextColor(getColor(R.color.danger))
                textSize = 14f
                setPadding(dp(12), dp(8), dp(4), dp(8))
                setOnClickListener {
                    isEnabled = false
                    RemoteModelManager.getInstance()
                        .deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build())
                        .addOnCompleteListener { refreshModels() }
                }
            }
            row.addView(delete)
        }
        return row
    }

    private fun textRow(text: String, dim: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(getColor(if (dim) R.color.text_dim else R.color.text_main))
        setPadding(0, dp(6), 0, dp(6))
    }

    // ---------------------------------------------------------------- Updates

    private var pendingRelease: Updater.Release? = null

    private fun setupUpdates() {
        findViewById<TextView>(R.id.version_line).text =
            getString(R.string.version_installed, Updater.installedVersionName(this))
        val btn = findViewById<Button>(R.id.btn_update)
        btn.setOnClickListener {
            if (pendingRelease != null) {
                installUpdate()
                return@setOnClickListener
            }
            btn.isEnabled = false
            btn.setText(R.string.update_checking)
            Updater.check(this, force = true) { release, error ->
                if (isFinishing || isDestroyed) return@check
                btn.isEnabled = true
                when {
                    release != null -> showUpdate(release)
                    error != null -> flashUpdateButton(R.string.update_check_failed)
                    else -> flashUpdateButton(R.string.update_latest)
                }
            }
        }
    }

    /** Show the result briefly in the button, then "Check for updates" again. */
    private fun flashUpdateButton(res: Int) {
        val btn = findViewById<Button>(R.id.btn_update)
        btn.setText(res)
        btn.postDelayed({ if (pendingRelease == null && !Updater.downloading) btn.setText(R.string.update_check) }, 3000)
    }

    /** "Check for updates" turns into "New version … install now?". */
    private fun showUpdate(release: Updater.Release?) {
        if (isFinishing || isDestroyed || release == null || Updater.downloading) return
        pendingRelease = release
        val btn = findViewById<Button>(R.id.btn_update)
        btn.text = getString(R.string.update_ready, release.versionName)
        btn.setBackgroundResource(R.drawable.bg_button_primary)
        btn.setTextColor(getColor(R.color.on_accent))
    }

    private fun installUpdate() {
        val release = pendingRelease ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            // Once: SubLens may install apps (only its own updates).
            Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        val btn = findViewById<Button>(R.id.btn_update)
        btn.isEnabled = false
        btn.text = getString(R.string.update_downloading, 0)
        // Close the lens first and wait until it is really gone: Android ignores taps on the install and
        // Play Protect dialogs while another app's window lies over the screen. Installing restarts the app anyway.
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
        whenOverlayGone(System.currentTimeMillis()) {
            Updater.downloadAndInstall(
                this,
                release,
                onProgress = { pct -> btn.text = getString(R.string.update_downloading, pct) },
            ) { ok ->
                btn.isEnabled = true
                btn.text = getString(R.string.update_ready, release.versionName)
                if (!ok) toast(R.string.update_failed)
            }
        }
    }

    private fun whenOverlayGone(since: Long, then: () -> Unit) {
        if (!OverlayService.isRunning || System.currentTimeMillis() - since > 3000) {
            btnStart.postDelayed(then, 300)
        } else {
            btnStart.postDelayed({ whenOverlayGone(since, then) }, 100)
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val MIN_TEXT_SP = 14
        private const val MAX_TEXT_SP = 28
    }
}
