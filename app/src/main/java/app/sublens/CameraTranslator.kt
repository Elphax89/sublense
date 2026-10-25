package app.sublens

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer

/**
 * Camera and photo translation: text recognition, QR codes, language detection and translation
 * for one picture at a time. Everything runs on the device. Callbacks arrive on the main thread.
 */
class CameraTranslator(private val prefs: Prefs) {

    /** A QR code found in the picture. [rect] is in bitmap pixels. */
    class Qr(val value: String, val url: String?, val rect: Rect?)

    enum class Status { OK, NO_TEXT, UNKNOWN_LANGUAGE, SAME_LANGUAGE, FAILED }

    class Result(
        val items: List<LensItem>,
        val qr: Qr?,
        val sourceLang: String?,
        val status: Status,
    )

    private val translator = ReplyTranslator(prefs, cacheSize = 400)
    private val languageId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.4f).build(),
    )
    private val barcodes: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
    private var recognizer: TextRecognizer? = null
    private var recognizerScript: Script? = null
    private var stickyLang: String? = null
    private var closed = false
    private var generation = 0

    /** Called when translating takes longer – usually because a language pack is downloading. */
    var onSlow: (() -> Unit)? = null

    /** Settings changed: results still in flight are dropped. */
    fun reset() {
        generation++
        stickyLang = null
    }

    fun process(bitmap: Bitmap, onResult: (Result) -> Unit) {
        if (closed) return
        val gen = generation
        val image = InputImage.fromBitmap(bitmap, 0)
        val script = Languages.scriptFor(prefs.sourceLang) ?: prefs.autoScript
        var text: Text? = null
        var qr: Qr? = null
        var pending = 2
        fun step() {
            pending--
            if (pending > 0 || closed || gen != generation) return
            val t = text
            if (t == null) {
                onResult(Result(emptyList(), qr, null, Status.FAILED))
                return
            }
            val blocks = BlockBuilder.build(t, script, bitmap, skipEdgeLines = false)
            if (blocks.isEmpty()) {
                onResult(Result(emptyList(), qr, null, Status.NO_TEXT))
                return
            }
            detect(blocks, script, gen) { source ->
                if (source == null) {
                    onResult(Result(untranslated(blocks), qr, null, Status.UNKNOWN_LANGUAGE))
                } else {
                    translateAll(blocks, source, gen) { items, status -> onResult(Result(items, qr, source, status)) }
                }
            }
        }
        recognizerFor(script).process(image)
            .addOnSuccessListener { text = it }
            .addOnCompleteListener { step() }
        barcodes.process(image)
            .addOnSuccessListener { list ->
                val code = list.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (code != null) {
                    val raw = code.rawValue ?: ""
                    val url = code.url?.url ?: raw.takeIf { looksLikeUrl(it) }
                    qr = Qr(raw, url, code.boundingBox)
                }
            }
            .addOnCompleteListener { step() }
    }

    private fun detect(blocks: List<TextBlock>, script: Script, gen: Int, done: (String?) -> Unit) {
        val chosen = prefs.sourceLang
        if (chosen != Languages.AUTO) {
            done(chosen)
            return
        }
        val raw = blocks.joinToString("\n") { it.text }
        // Without a clear result, the script itself tells the language (except Latin).
        val byScript = when (script) {
            Script.CHINESE -> ChineseScript.variantOf(raw) ?: "zh"
            Script.JAPANESE -> "ja"
            Script.KOREAN -> "ko"
            Script.DEVANAGARI -> "hi"
            Script.LATIN -> null
        }
        languageId.identifyLanguage(raw)
            .addOnSuccessListener { tag ->
                if (closed || gen != generation) return@addOnSuccessListener
                val detected = Languages.detectedFor(tag, raw)
                val lang = detected ?: byScript ?: stickyLang
                if (lang != null) stickyLang = lang
                done(lang)
            }
            .addOnFailureListener {
                if (!closed && gen == generation) done(byScript ?: stickyLang)
            }
    }

    private fun translateAll(
        blocks: List<TextBlock>,
        source: String,
        gen: Int,
        done: (List<LensItem>, Status) -> Unit,
    ) {
        val target = prefs.targetLang
        if (source == target) {
            done(untranslated(blocks), Status.SAME_LANGUAGE)
            return
        }
        val results = arrayOfNulls<String>(blocks.size)
        var remaining = blocks.size
        var failed = false
        var slowReported = false
        for ((i, b) in blocks.withIndex()) {
            translator.translate(
                b.text,
                source,
                target,
                onSlow = {
                    if (!slowReported && gen == generation) {
                        slowReported = true
                        onSlow?.invoke()
                    }
                },
                onDone = { result ->
                    if (result == null) failed = true
                    results[i] = result ?: b.text
                    remaining--
                    if (remaining == 0 && !closed && gen == generation) {
                        val items = blocks.mapIndexed { k, blk -> item(blk, results[k] ?: blk.text) }
                        done(items, if (failed && results.all { it == null }) Status.FAILED else Status.OK)
                    }
                },
            )
        }
    }

    private fun untranslated(blocks: List<TextBlock>): List<LensItem> = blocks.map { item(it, it.text) }

    private fun item(b: TextBlock, translated: String) = LensItem(
        rect = Rect(b.rect),
        original = b.text,
        translated = translated,
        background = b.background,
        foreground = b.foreground,
        lineHeightPx = b.lineHeight,
    )

    private fun recognizerFor(script: Script): TextRecognizer {
        recognizer?.let {
            if (recognizerScript == script) return it
            it.close()
        }
        return TextRecognition.getClient(BlockBuilder.options(script)).also {
            recognizer = it
            recognizerScript = script
        }
    }

    fun close() {
        closed = true
        translator.close()
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        try {
            barcodes.close()
        } catch (_: Exception) {
        }
        try {
            languageId.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        fun looksLikeUrl(s: String): Boolean {
            val t = s.trim()
            return t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true) ||
                Regex("^(www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t)
        }
    }
}
