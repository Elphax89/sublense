package app.sublens

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Camera translation, like a pocket interpreter: point at a menu, sign or QR code.
 * Live: the preview is read about once a second and translations are drawn on top.
 * Shutter: takes a sharp photo and translates it calmly (also for photos from the gallery).
 * QR codes: open the link – optionally with the SubLens lens running, to translate the website.
 */
class CameraActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var translator: CameraTranslator
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var previewView: PreviewView
    private lateinit var still: ImageView
    private lateinit var overlay: CameraOverlayView
    private lateinit var status: TextView
    private lateinit var fromChip: TextView
    private lateinit var toChip: TextView
    private lateinit var flash: ImageButton
    private lateinit var shutter: ImageButton
    private lateinit var copyAll: ImageButton
    private lateinit var originalBtn: TextView
    private lateinit var qrCard: LinearLayout
    private lateinit var qrValue: TextView
    private lateinit var qrLens: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analysis: ImageAnalysis? = null

    /** Frozen frame or gallery photo on screen (live reading paused). */
    private var frozen = false

    @Volatile
    private var analyzing = false
    private var lastAnalyzedAt = 0L
    private var torch = false
    private var qr: CameraTranslator.Qr? = null
    private var qrSeenAt = 0L
    private var pendingUrl: String? = null
    private var detectedLang: String? = null

    private val hideQr = Runnable { if (!frozen) showQr(null) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        prefs = Prefs(this)
        Languages.autoLabel = getString(R.string.auto_detect)
        translator = CameraTranslator(prefs)
        translator.onSlow = { setStatus(getString(R.string.cam_downloading)) }
        analysisExecutor = Executors.newSingleThreadExecutor()

        previewView = findViewById(R.id.cam_preview)
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER)
        still = findViewById(R.id.cam_still)
        overlay = findViewById(R.id.cam_overlay)
        overlay.accentColor = prefs.lensColor
        status = findViewById(R.id.cam_status)
        fromChip = findViewById(R.id.cam_from)
        toChip = findViewById(R.id.cam_to)
        flash = findViewById(R.id.cam_flash)
        shutter = findViewById(R.id.cam_shutter)
        copyAll = findViewById(R.id.cam_copy)
        originalBtn = findViewById(R.id.cam_original)
        qrCard = findViewById(R.id.cam_qr)
        qrValue = findViewById(R.id.cam_qr_value)
        qrLens = findViewById(R.id.cam_qr_lens)

        setupEdgeToEdge()
        findViewById<View>(R.id.cam_back).setOnClickListener { finish() }
        fromChip.setOnClickListener { pickSource() }
        toChip.setOnClickListener { pickTarget() }
        findViewById<View>(R.id.cam_swap).setOnClickListener {
            if (prefs.swapLanguages(detectedLang)) onLanguagesChanged() else toast(R.string.swap_langs_impossible)
        }
        flash.setOnClickListener { toggleTorch() }
        shutter.setOnClickListener { if (frozen) unfreeze() else capture() }
        findViewById<View>(R.id.cam_gallery).setOnClickListener { pickPhoto() }
        copyAll.setOnClickListener { copy(overlay.allTranslations()) }
        originalBtn.setOnClickListener {
            overlay.showOriginal = !overlay.showOriginal
            originalBtn.isSelected = overlay.showOriginal
            originalBtn.setTextColor(getColor(if (overlay.showOriginal) R.color.accent else R.color.text_main))
        }
        findViewById<View>(R.id.cam_qr_open).setOnClickListener { qr?.let { openQr(it, withLens = false) } }
        qrLens.setOnClickListener { qr?.let { openQr(it, withLens = true) } }
        findViewById<View>(R.id.cam_qr_copy).setOnClickListener { qr?.let { copy(it.value) } }
        findViewById<View>(R.id.cam_perm_btn).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }

        updateChips()
        setStatus(getString(R.string.cam_hint))
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
        // Opened with a shared picture ("Share → SubLens").
        sharedImage(intent)?.let { loadPhoto(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let { loadPhoto(it) }
    }

    override fun onResume() {
        super.onResume()
        if (camera == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            findViewById<View>(R.id.cam_perm).visibility = View.GONE
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        analysis?.clearAnalyzer()
        analysisExecutor.shutdown()
        translator.close()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            findViewById<View>(R.id.cam_perm).visibility = View.VISIBLE
            status.visibility = View.GONE
        }
    }

    private fun setupEdgeToEdge() {
        window.statusBarColor = 0
        window.navigationBarColor = 0
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val top = findViewById<View>(R.id.cam_top)
        val bottom = findViewById<View>(R.id.cam_bottom)
        val dp = resources.displayMetrics.density
        findViewById<View>(R.id.cam_root).setOnApplyWindowInsetsListener { _, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                i.top to i.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }
            top.setPadding(top.paddingLeft, t + (10 * dp).roundToInt(), top.paddingRight, top.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, b + (18 * dp).roundToInt())
            (status.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin = t + (66 * dp).roundToInt()
            status.requestLayout()
            insets
        }
    }

    // ---------------------------------------------------------------- Camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val provider = try {
                future.get()
            } catch (_: Exception) {
                setStatus(getString(R.string.cam_no_camera))
                return@addListener
            }
            // Same 4:3 field of view for preview, live reading and photos, so positions match.
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            preview.setSurfaceProvider(previewView.getSurfaceProvider())
            val analysisUseCase = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        )
                        .build(),
                )
                .build()
            analysisUseCase.setAnalyzer(analysisExecutor) { image -> analyze(image) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysisUseCase, capture)
                analysis = analysisUseCase
                imageCapture = capture
                flash.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.INVISIBLE
            } catch (_: Exception) {
                setStatus(getString(R.string.cam_no_camera))
            }
        }, mainExecutor)
    }

    /** Analysis thread: take a frame about once a second while live. */
    private fun analyze(image: ImageProxy) {
        val now = SystemClock.uptimeMillis()
        if (frozen || analyzing || now - lastAnalyzedAt < LIVE_INTERVAL_MS) {
            image.close()
            return
        }
        analyzing = true
        lastAnalyzedAt = now
        val bitmap = try {
            rotate(image.toBitmap(), image.imageInfo.rotationDegrees)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            analyzing = false
            return
        }
        main.post {
            if (frozen || isDestroyed) {
                analyzing = false
                return@post
            }
            translator.process(bitmap) { result ->
                analyzing = false
                if (!frozen) showResult(result, bitmap.width, bitmap.height, CameraOverlayView.Fit.FILL, live = true)
            }
        }
    }

    private fun capture() {
        val ic = imageCapture ?: return
        freeze()
        setStatus(getString(R.string.cam_translating))
        ic.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = try {
                    scaleDown(rotate(image.toBitmap(), image.imageInfo.rotationDegrees))
                } catch (_: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (bitmap == null) {
                    unfreeze()
                    return
                }
                showStill(bitmap)
            }

            override fun onError(exception: ImageCaptureException) {
                // Fall back to what the preview shows right now.
                val fallback = previewView.getBitmap()
                if (fallback != null) showStill(fallback) else unfreeze()
            }
        })
    }

    private fun freeze() {
        frozen = true
        overlay.clear()
        main.removeCallbacks(hideQr)
        shutter.setImageResource(R.drawable.ic_close)
        shutter.contentDescription = getString(R.string.cam_retake)
    }

    private fun showStill(bitmap: Bitmap) {
        if (!frozen) freeze()
        still.setImageBitmap(bitmap)
        still.visibility = View.VISIBLE
        showQr(null)
        setStatus(getString(R.string.cam_translating))
        translator.process(bitmap) { result ->
            if (frozen) showResult(result, bitmap.width, bitmap.height, CameraOverlayView.Fit.FIT, live = false)
        }
    }

    private fun unfreeze() {
        frozen = false
        still.setImageDrawable(null)
        still.visibility = View.GONE
        overlay.clear()
        copyAll.visibility = View.GONE
        shutter.setImageDrawable(null)
        shutter.contentDescription = getString(R.string.cam_shutter)
        showQr(null)
        setStatus(getString(R.string.cam_hint))
    }

    private fun showResult(result: CameraTranslator.Result, w: Int, h: Int, fit: CameraOverlayView.Fit, live: Boolean) {
        if (result.sourceLang != null) {
            detectedLang = result.sourceLang
            updateChips()
        }
        overlay.show(result.items, result.qr?.rect, w, h, fit)
        copyAll.visibility = if (!live && overlay.hasText) View.VISIBLE else View.GONE
        when {
            result.qr != null -> showQr(result.qr)
            live && SystemClock.uptimeMillis() - qrSeenAt > QR_LINGER_MS -> showQr(null)
        }
        val message = when (result.status) {
            CameraTranslator.Status.OK -> null
            CameraTranslator.Status.NO_TEXT -> if (live) getString(R.string.cam_hint) else getString(R.string.cam_no_text)
            CameraTranslator.Status.UNKNOWN_LANGUAGE -> getString(R.string.cam_unknown_lang)
            CameraTranslator.Status.SAME_LANGUAGE -> getString(R.string.cam_same_lang, Languages.displayName(prefs.targetLang))
            CameraTranslator.Status.FAILED -> getString(R.string.cam_failed)
        }
        if (message == null && result.qr != null && result.items.isEmpty()) setStatus(null) else setStatus(message)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torch = !torch
        cam.cameraControl.enableTorch(torch)
        flash.isSelected = torch
    }

    // ---------------------------------------------------------------- Photos

    private fun pickPhoto() {
        val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK)
        } catch (_: Exception) {
            toast(R.string.cam_failed)
        }
    }

    private fun sharedImage(i: Intent?): Uri? {
        if (i?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun loadPhoto(uri: Uri) {
        freeze()
        setStatus(getString(R.string.cam_translating))
        analysisExecutor.execute {
            val bitmap = try {
                decode(uri)
            } catch (_: Exception) {
                null
            }
            main.post {
                if (isDestroyed) return@post
                if (bitmap == null) {
                    toast(R.string.cam_failed)
                    unfreeze()
                } else {
                    showStill(bitmap)
                }
            }
        }
    }

    /** Decodes a photo at most [MAX_SIDE] pixels long, upright. */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val f = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        return scaleDown(rotate(bmp, degrees))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { loadPhoto(it) }
            REQ_CAPTURE_SCREEN -> {
                val url = pendingUrl
                pendingUrl = null
                if (resultCode != RESULT_OK || data == null || url == null) {
                    toast(R.string.toast_need_capture)
                    return
                }
                val service = Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_START)
                    .putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                startForegroundService(service)
                // Give the lens a moment to appear, then open the website underneath it.
                main.postDelayed({ openUrl(url) }, 700)
            }
        }
    }

    // ---------------------------------------------------------------- QR codes

    private fun showQr(code: CameraTranslator.Qr?) {
        main.removeCallbacks(hideQr)
        if (code == null) {
            qr = null
            qrCard.visibility = View.GONE
            return
        }
        qr = code
        qrSeenAt = SystemClock.uptimeMillis()
        qrValue.text = code.url ?: code.value
        val isUrl = code.url != null
        findViewById<View>(R.id.cam_qr_open).visibility = if (isUrl) View.VISIBLE else View.GONE
        qrLens.visibility = if (isUrl) View.VISIBLE else View.GONE
        qrCard.visibility = View.VISIBLE
        if (!frozen) main.postDelayed(hideQr, QR_LINGER_MS)
    }

    private fun openQr(code: CameraTranslator.Qr, withLens: Boolean) {
        val url = code.url ?: return
        if (!withLens || OverlayService.isRunning) {
            openUrl(url)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(R.string.toast_need_overlay)
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        pendingUrl = url
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(captureIntent, REQ_CAPTURE_SCREEN)
    }

    private fun openUrl(url: String) {
        val full = if (url.contains("://")) url else "https://$url"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(full)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast(R.string.cam_failed)
        }
    }

    // ---------------------------------------------------------------- Languages

    private fun updateChips() {
        val src = prefs.sourceLang
        fromChip.text = if (src == Languages.AUTO) {
            val shown = detectedLang?.let { Languages.displayName(it) } ?: scriptShort(prefs.autoScript)
            getString(R.string.cam_auto_chip, shown)
        } else {
            Languages.displayName(src)
        }
        toChip.text = Languages.displayName(prefs.targetLang)
    }

    private fun scriptShort(s: Script): String = when (s) {
        Script.LATIN -> "ABC"
        Script.CHINESE -> "中文"
        Script.JAPANESE -> "日本語"
        Script.KOREAN -> "한국어"
        Script.DEVANAGARI -> "हिन्दी"
    }

    private fun pickSource() {
        // "Auto" once per script (text recognition needs to know the script), then fixed languages.
        val scripts = Script.entries
        val langs = Languages.sources().filter { it != Languages.AUTO }
        val labels = scripts.map { getString(R.string.cam_auto_script, getString(it.labelRes)) } +
            langs.map { Languages.displayName(it) }
        val current = if (prefs.sourceLang == Languages.AUTO) scripts.indexOf(prefs.autoScript) else scripts.size + langs.indexOf(prefs.sourceLang)
        AlertDialog.Builder(this)
            .setTitle(R.string.label_from)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                if (which < scripts.size) {
                    prefs.autoScript = scripts[which]
                    prefs.sourceLang = Languages.AUTO
                } else {
                    prefs.sourceLang = langs[which - scripts.size]
                }
                d.dismiss()
                onLanguagesChanged()
            }
            .show()
    }

    private fun pickTarget() {
        val langs = Languages.targets()
        AlertDialog.Builder(this)
            .setTitle(R.string.label_to)
            .setSingleChoiceItems(langs.map { Languages.displayName(it) }.toTypedArray(), langs.indexOf(prefs.targetLang)) { d, which ->
                prefs.targetLang = langs[which]
                d.dismiss()
                onLanguagesChanged()
            }
            .show()
    }

    private fun onLanguagesChanged() {
        detectedLang = null
        translator.reset()
        analyzing = false
        updateChips()
        overlay.clear()
        if (frozen) {
            val bmp = (still.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (bmp != null) showStill(bmp)
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun setStatus(message: String?) {
        status.text = message ?: ""
        status.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SubLens", text))
        toast(R.string.cam_copied)
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val f = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
    }

    companion object {
        private const val REQ_CAMERA = 31
        private const val REQ_PICK = 32
        private const val REQ_CAPTURE_SCREEN = 33
        private const val LIVE_INTERVAL_MS = 900L
        private const val QR_LINGER_MS = 4000L
        private const val MAX_SIDE = 2400
    }
}
