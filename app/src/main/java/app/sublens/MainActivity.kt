package app.sublens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * Home screen: permissions (only while missing), start/stop and all settings, in the same order as
 * the desktop panel – Language, Translation (offline / online), Lens, Offline language packs, About.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val sources = Languages.sources()
    private val targets = Languages.targets()
    private val scripts = Script.entries

    private lateinit var btnStart: Button
    private lateinit var rowScript: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Languages.autoLabel = getString(R.string.auto_detect)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        btnStart = findViewById(R.id.btn_start)
        rowScript = findViewById(R.id.row_script)

        setupPermissions()
        setupLanguages()
        setupTranslation()
        setupDisplay()
        setupSpeed()
        setupModels()
        btnStart.setOnClickListener { onStartClicked() }
        findViewById<View>(R.id.btn_camera).setOnClickListener { startActivity(Intent(this, CameraActivity::class.java)) }
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
        refreshTranslation()
        refreshModels()
        if (!Updater.downloading) Updater.check(this, force = false) { release, _ -> showUpdate(release) }
    }

    override fun onPause() {
        super.onPause()
        OverlayService.stateListener = null
    }

    // ---------------------------------------------------------------- Start / stop

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
        // Stop the running overlay first – installing restarts the app.
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
        }
        val btn = findViewById<Button>(R.id.btn_update)
        btn.isEnabled = false
        btn.text = getString(R.string.update_downloading, 0)
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

    // ---------------------------------------------------------------- Permissions

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
        findViewById<TextView>(R.id.perm_overlay_status).apply {
            setText(if (overlayOk) R.string.perm_overlay_ok else R.string.perm_overlay_missing)
            setTextColor(getColor(if (overlayOk) R.color.ok else R.color.danger))
        }
        findViewById<View>(R.id.btn_overlay).visibility = if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = hasNotificationPermission()
        findViewById<TextView>(R.id.perm_notif_status).apply {
            setText(if (notifOk) R.string.perm_notif_ok else R.string.perm_notif_missing)
            setTextColor(getColor(if (notifOk) R.color.ok else R.color.text_dim))
        }
        findViewById<View>(R.id.btn_notif).visibility = if (notifOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.row_overlay).visibility = if (overlayOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.row_notif).visibility = if (notifOk) View.GONE else View.VISIBLE
        // Everything granted → the card disappears.
        findViewById<View>(R.id.card_permissions).visibility = if (overlayOk && notifOk) View.GONE else View.VISIBLE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    // ---------------------------------------------------------------- Language

    private fun setupLanguages() {
        setupSpinner(
            R.id.spinner_source,
            sources.map { Languages.displayName(it) },
            sources.indexOf(prefs.sourceLang),
        ) { pos ->
            prefs.sourceLang = sources[pos]
            updateScriptRow()
        }
        setupSpinner(
            R.id.spinner_target,
            targets.map { Languages.displayName(it) },
            targets.indexOf(prefs.targetLang),
        ) { pos -> prefs.targetLang = targets[pos] }
        setupSpinner(
            R.id.spinner_script,
            scripts.map { getString(it.labelRes) },
            scripts.indexOf(prefs.autoScript),
        ) { pos -> prefs.autoScript = scripts[pos] }
        updateScriptRow()
        findViewById<View>(R.id.btn_swap_langs).setOnClickListener {
            if (prefs.swapLanguages(null)) {
                findViewById<Spinner>(R.id.spinner_source).setSelection(sources.indexOf(prefs.sourceLang).coerceAtLeast(0))
                findViewById<Spinner>(R.id.spinner_target).setSelection(targets.indexOf(prefs.targetLang).coerceAtLeast(0))
                updateScriptRow()
            } else {
                Toast.makeText(this, R.string.swap_langs_impossible, Toast.LENGTH_SHORT).show()
            }
        }
        setupLensColors()

        val replies = listOf(Languages.AUTO) + targets
        setupSpinner(
            R.id.spinner_reply,
            replies.map { if (it == Languages.AUTO) getString(R.string.reply_auto) else Languages.displayName(it) },
            replies.indexOf(prefs.replyLang),
        ) { pos -> prefs.replyLang = replies[pos] }
        findViewById<Switch>(R.id.switch_composer).apply {
            isChecked = prefs.composerEnabled
            setOnCheckedChangeListener { _, checked -> prefs.composerEnabled = checked }
        }
    }

    private fun updateScriptRow() {
        rowScript.visibility = if (prefs.sourceLang == Languages.AUTO) View.VISIBLE else View.GONE
    }

    /** Colour circles for the lens frame and handles. */
    private fun setupLensColors() {
        val row = findViewById<LinearLayout>(R.id.row_lens_color)
        val d = resources.displayMetrics.density
        val size = (36 * d).toInt()
        fun render() {
            for (i in 0 until row.childCount) {
                val c = Prefs.LENS_COLORS[i]
                val selected = c == prefs.lensColor
                val v = row.getChildAt(i)
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
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
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * d).toInt() }
            v.contentDescription = getString(R.string.label_lens_color)
            v.setOnClickListener {
                prefs.lensColor = c
                render()
            }
            row.addView(v)
        }
        render()
    }

    private fun setupSpinner(id: Int, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val spinner = findViewById<Spinner>(id)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selected.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelect(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---------------------------------------------------------------- Translation (offline / online)

    private fun setupTranslation() {
        val group = findViewById<RadioGroup>(R.id.radio_engine)
        group.setOnCheckedChangeListener { _, checkedId ->
            val online = checkedId == R.id.radio_online
            if (online == prefs.onlineEnabled) return@setOnCheckedChangeListener
            prefs.onlineEnabled = online
            if (online) {
                OnlineTranslator.resume()
                val p = Providers.byId(prefs.onlineProvider)
                // Not set up yet → straight to the catalog.
                if (p == null || !OnlineTranslator.isConfigured(this, p)) openOnline()
            }
            refreshTranslation()
        }
        findViewById<View>(R.id.btn_online_setup).setOnClickListener { openOnline() }
    }

    private fun openOnline() = startActivity(Intent(this, OnlineActivity::class.java))

    private fun refreshTranslation() {
        val p = Providers.byId(prefs.onlineProvider)
        val configured = p != null && OnlineTranslator.isConfigured(this, p)
        findViewById<RadioGroup>(R.id.radio_engine).check(if (prefs.onlineEnabled) R.id.radio_online else R.id.radio_offline)
        findViewById<TextView>(R.id.engine_status).text = when {
            !prefs.onlineEnabled -> getString(R.string.engine_status_offline)
            !configured -> getString(R.string.engine_status_not_set)
            else -> getString(R.string.engine_status_online, OnlineTranslator.summary(this) ?: p!!.name)
        }
        findViewById<Button>(R.id.btn_online_setup).setText(if (configured) R.string.engine_manage else R.string.engine_setup)
        findViewById<TextView>(R.id.footer).text =
            if (prefs.onlineEnabled && configured) getString(R.string.footer_online, p!!.name) else getString(R.string.footer)
    }

    // ---------------------------------------------------------------- Display & speed

    private fun setupDisplay() {
        val mode = findViewById<RadioGroup>(R.id.radio_mode)
        mode.check(if (prefs.lensMode) R.id.radio_lens else R.id.radio_bar)
        mode.setOnCheckedChangeListener { _, checkedId ->
            prefs.lensMode = checkedId == R.id.radio_lens
            updateBarPosRow()
        }
        updateBarPosRow()

        val group = findViewById<RadioGroup>(R.id.radio_bar_pos)
        group.check(if (prefs.barAbove) R.id.radio_above else R.id.radio_below)
        group.setOnCheckedChangeListener { _, checkedId -> prefs.barAbove = checkedId == R.id.radio_above }

        val label = findViewById<TextView>(R.id.label_text_size)
        val seek = findViewById<SeekBar>(R.id.seek_text_size)
        seek.max = MAX_TEXT_SP - MIN_TEXT_SP
        seek.progress = (prefs.textSizeSp - MIN_TEXT_SP).coerceIn(0, seek.max)
        label.text = getString(R.string.label_text_size, prefs.textSizeSp)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = MIN_TEXT_SP + progress
                label.text = getString(R.string.label_text_size, size)
                if (fromUser) prefs.textSizeSp = size
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        findViewById<Switch>(R.id.switch_original).apply {
            isChecked = prefs.showOriginal
            setOnCheckedChangeListener { _, checked -> prefs.showOriginal = checked }
        }
        findViewById<Switch>(R.id.switch_video).apply {
            isChecked = prefs.videoMode
            setOnCheckedChangeListener { _, checked -> prefs.videoMode = checked }
        }
    }

    /** "Bar position" only exists when the translation is shown in the bar. */
    private fun updateBarPosRow() {
        val v = if (prefs.lensMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.label_bar_pos).visibility = v
        findViewById<View>(R.id.radio_bar_pos).visibility = v
    }

    private fun setupSpeed() {
        val group = findViewById<RadioGroup>(R.id.radio_speed)
        group.check(
            when {
                prefs.intervalMs <= 350L -> R.id.radio_fast
                prefs.intervalMs >= 900L -> R.id.radio_saver
                else -> R.id.radio_normal
            },
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.intervalMs = when (checkedId) {
                R.id.radio_fast -> 300L
                R.id.radio_saver -> 1000L
                else -> 500L
            }
        }
    }

    // ---------------------------------------------------------------- Language packs

    private fun setupModels() {
        findViewById<Switch>(R.id.switch_wifi).apply {
            isChecked = prefs.wifiOnly
            setOnCheckedChangeListener { _, checked -> prefs.wifiOnly = checked }
        }
        // Any language can be downloaded by hand, e.g. before a trip without internet.
        val spinner = findViewById<Spinner>(R.id.spinner_model)
        modelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ArrayList<String>()).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = it
        }
        updateModelSpinner(emptySet())
        spinner.setSelection(targets.indexOf(prefs.targetLang).coerceAtLeast(0), false)

        val btn = findViewById<Button>(R.id.btn_download_model)
        btn.setOnClickListener {
            val lang = targets.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
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

    private var modelAdapter: ArrayAdapter<String>? = null

    /** Picker: all languages, already downloaded ones with ✓. */
    private fun updateModelSpinner(installed: Set<String>) {
        val adapter = modelAdapter ?: return
        adapter.clear()
        adapter.addAll(
            targets.map { tag ->
                val name = Languages.displayName(tag)
                if (Languages.mlkit(tag) in installed) getString(R.string.models_installed_mark, name) else name
            },
        )
        adapter.notifyDataSetChanged()
    }

    private fun refreshModels() {
        val list = findViewById<LinearLayout>(R.id.models_list)
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                list.removeAllViews()
                val langs = models.map { it.language }.sortedBy { Languages.packName(it) }
                updateModelSpinner(langs.toSet())
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
        val name = textRow(Languages.packName(lang), dim = false)
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

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CAPTURE = 1001
        private const val REQ_NOTIFICATIONS = 1002
        private const val MIN_TEXT_SP = 14
        private const val MAX_TEXT_SP = 28
    }
}
