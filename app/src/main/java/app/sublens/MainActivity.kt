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

/** Startseite: Berechtigungen, Start/Stopp und alle Einstellungen. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val sources = Languages.sources()
    private val targets = Languages.targets()
    private val scripts = Script.entries

    private lateinit var btnStart: Button
    private lateinit var rowScript: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        btnStart = findViewById(R.id.btn_start)
        rowScript = findViewById(R.id.row_script)

        setupPermissions()
        setupLanguages()
        setupDisplay()
        setupSpeed()
        setupModels()
        btnStart.setOnClickListener { onStartClicked() }

        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications && !hasNotificationPermission()) {
            prefs.askedNotifications = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshStartButton()
        refreshModels()
    }

    // ---------------------------------------------------------------- Start / Stopp

    private fun onStartClicked() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            btnStart.postDelayed({ refreshStartButton() }, 400)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            // Immer den ganzen Bildschirm – sonst passen die Rahmen-Koordinaten nicht.
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
        if (OverlayService.isRunning) {
            btnStart.setText(R.string.stop)
            btnStart.setBackgroundResource(R.drawable.bg_button_danger)
        } else {
            btnStart.setText(R.string.start)
            btnStart.setBackgroundResource(R.drawable.bg_button_primary)
        }
    }

    // ---------------------------------------------------------------- Berechtigungen

    private fun setupPermissions() {
        findViewById<Button>(R.id.btn_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.btn_notif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT < 33) findViewById<View>(R.id.row_notif).visibility = View.GONE
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
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshPermissions()
    }

    // ---------------------------------------------------------------- Sprache

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
            scripts.map { it.label },
            scripts.indexOf(prefs.autoScript),
        ) { pos -> prefs.autoScript = scripts[pos] }
        updateScriptRow()

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

    // ---------------------------------------------------------------- Anzeige & Tempo

    private fun setupDisplay() {
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

    // ---------------------------------------------------------------- Sprachpakete

    private fun setupModels() {
        findViewById<Switch>(R.id.switch_wifi).apply {
            isChecked = prefs.wifiOnly
            setOnCheckedChangeListener { _, checked -> prefs.wifiOnly = checked }
        }
        val btn = findViewById<Button>(R.id.btn_download_target)
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.setText(R.string.models_downloading)
            val conditions = DownloadConditions.Builder().apply { if (prefs.wifiOnly) requireWifi() }.build()
            val model = TranslateRemoteModel.Builder(prefs.targetLang).build()
            RemoteModelManager.getInstance().download(model, conditions)
                .addOnSuccessListener { toast(R.string.models_download_done) }
                .addOnFailureListener { toast(R.string.models_download_failed) }
                .addOnCompleteListener {
                    btn.isEnabled = true
                    btn.setText(R.string.models_download_target)
                    refreshModels()
                }
        }
    }

    private fun refreshModels() {
        val list = findViewById<LinearLayout>(R.id.models_list)
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (isFinishing || isDestroyed) return@addOnSuccessListener
                list.removeAllViews()
                val langs = models.map { it.language }.sortedBy { Languages.displayName(it) }
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
        val name = textRow(Languages.displayName(lang), dim = false)
        row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        // Englisch ist das Basis-Paket (Zwischensprache) und bleibt immer installiert.
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
