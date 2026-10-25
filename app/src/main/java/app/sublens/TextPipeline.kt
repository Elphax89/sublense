package app.sublens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Image → text (OCR) → detect language → translate. All on the device.
 * Each text block is translated separately and delivered with position, background and text colour,
 * so the translation can sit right over the original in the lens.
 * Used from the main thread only; ML Kit computes in the background.
 */
class TextPipeline(
    context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        /** One result per frame. [items] are in coordinates of the crop [crop]. */
        fun onResult(items: List<LensItem>, crop: Rect, sourceLang: String?)
        fun onTextSeen()
        fun onNoText()

        /** Outside the re-read area the display no longer matches – please re-read everything. */
        fun onStale()
        fun onStatus(message: String)
    }


    /** [region]: only this part of the crop was re-read (the rest stays as displayed). */
    private class Frame(val blocks: List<TextBlock>, val crop: Rect, val combined: String, val region: Rect? = null)

    /** Last delivered blocks and language – for partial updates. */
    private var lastDelivered: List<LensItem> = emptyList()
    private var lastDeliveredCrop: Rect? = null
    private var lastSource: String? = null

    private val appContext = context.applicationContext

    /** Read by the capture thread: while true, no new frame is sent. */
    @Volatile
    var busy = false

    private var closed = false
    private var recognizer: TextRecognizer? = null
    private var recognizerScript: Script? = null
    private val languageId: LanguageIdentifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build(),
    )

    private val translators = LinkedHashMap<String, Translator>()
    private val readyPairs = HashSet<String>()
    private val downloadingPairs = HashSet<String>()
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 400
    }

    private var last: Frame? = null
    private var lastSettings = ""
    private var stickyLang: String? = null
    private var translating = false
    private var pending: Frame? = null

    /** Last shown translations – if one of them shows up in the frame, the capture saw our own overlay. */
    private var shownTranslations: Set<String> = emptySet()

    /** Increases with every settings change – outdated results are discarded. */
    private var generation = 0

    // ---------------------------------------------------------------- OCR

    /**
     * Reads [bitmap]. If [region] is set, only blocks inside it are re-translated; all others
     * stay exactly as before (no twitching when only, say, a GIF moves).
     */
    fun process(bitmap: Bitmap, crop: Rect, region: Rect? = null, drawn: List<Rect> = emptyList()) {
        if (closed) {
            bitmap.recycle()
            busy = false
            return
        }
        busy = true
        val script = Languages.scriptFor(prefs.sourceLang) ?: prefs.autoScript
        val rec = recognizerFor(script)
        rec.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                if (!closed) restrict(buildFrame(text, script, bitmap, crop), region, drawn)?.let { onText(it) }
            }
            .addOnFailureListener { e -> if (!closed) onOcrError(e) }
            .addOnCompleteListener {
                bitmap.recycle()
                busy = false
            }
    }

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

    private fun buildFrame(text: Text, script: Script, bitmap: Bitmap, crop: Rect): Frame {
        val blocks = BlockBuilder.build(text, script, bitmap, skipEdgeLines = true)
        return Frame(blocks, Rect(crop), blocks.joinToString("\n") { it.text })
    }

    private fun onOcrError(e: Exception) {
        if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
            listener.onStatus(appContext.getString(R.string.status_ocr_model))
        } else {
            Log.w(TAG, "Text recognition failed", e)
        }
    }

    // ---------------------------------------------------------------- Text → translation

    /**
     * Keep only blocks in the re-read area (the others currently show our own translation).
     * If what is visible outside no longer matches what we show there (e.g. because
     * the user scrolled), the frame is dropped and a full re-read is requested (null).
     */
    private fun restrict(frame: Frame, region: Rect?, drawn: List<Rect>): Frame? {
        if (region == null || lastDeliveredCrop != frame.crop || lastDelivered.isEmpty()) return frame
        val inside = frame.blocks.filter { Rect.intersects(it.rect, region) }
        // Nothing can be checked under our own translations (foreign script that recognition
        // often can't read at all) – only places where the original is visible.
        val outside = frame.blocks.filter { b ->
            !Rect.intersects(b.rect, region) && drawn.none { d -> Rect.intersects(Rect(d).apply { inset(-2, -2) }, b.rect) }
        }
        if (looksStale(outside)) {
            listener.onStale()
            return null
        }
        return Frame(inside, frame.crop, inside.joinToString("\n") { it.text }, Rect(region))
    }

    private fun looksStale(outside: List<TextBlock>): Boolean {
        var counted = 0
        var bad = 0
        for (b in outside) {
            val t = normalize(b.text)
            if (t.length < 4) continue
            counted++
            val ok = lastDelivered.any { item ->
                val grow = max(8, item.lineHeightPx)
                val area = Rect(item.rect).apply { inset(-grow, -grow) }
                Rect.intersects(area, b.rect) && textMatches(t, normalize(item.original))
            }
            if (!ok) bad++
        }
        // Visible original that isn't (any more) where we know it → something moved.
        return bad >= 2 || (bad == 1 && counted == 1)
    }

    private fun textMatches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length >= 3 && b.contains(a)) return true
        if (b.length >= 3 && a.contains(b)) return true
        return fuzzySame(a, b)
    }

    /** Tolerant text comparison (for animated emojis that OCR sometimes reads as letters, sometimes not). */
    private fun fuzzySame(a: String, b: String): Boolean {
        if (isSameText(a, b)) return true
        val longer = max(a.length, b.length)
        if (longer < 6 || longer > 300) return false
        val allowed = max(2, longer / 6)
        if (abs(a.length - b.length) > allowed) return false
        return levenshtein(a, b, allowed) <= allowed
    }

    /**
     * If a block sits practically unchanged in the same place, exactly the old display is kept –
     * so neither font size nor translation jumps just because recognition varies slightly.
     */
    private fun stabilize(fresh: List<LensItem>, crop: Rect): List<LensItem> {
        if (lastDeliveredCrop != crop || lastDelivered.isEmpty()) return fresh
        return fresh.map { n ->
            val lh = max(1, n.lineHeightPx)
            lastDelivered.firstOrNull { o ->
                abs(o.rect.top - n.rect.top) <= max(6, lh / 3) &&
                    abs(o.rect.left - n.rect.left) <= max(8, lh / 2) &&
                    abs(o.rect.height() - n.rect.height()) <= max(6, lh / 2) &&
                    fuzzySame(normalize(o.original), normalize(n.original))
            } ?: n
        }
    }

    /** Old blocks outside the re-read area. */
    private fun keptOutside(frame: Frame): List<LensItem> {
        val region = frame.region ?: return emptyList()
        if (lastDeliveredCrop != frame.crop) return emptyList()
        return lastDelivered.filter { !Rect.intersects(it.rect, region) }
    }

    private fun onText(frame: Frame) {
        if (frame.blocks.isEmpty() && frame.region != null) {
            // No text (any more) in the moving area – leave the rest unchanged.
            val kept = keptOutside(frame)
            if (kept.size != lastDelivered.size) publish(kept, frame.crop, lastSource)
            return
        }
        if (frame.blocks.isEmpty()) {
            last = null
            listener.onNoText()
            return
        }
        // Our own translation in the frame? Then the overlay was still visible during capture – drop the frame.
        if (prefs.lensMode && shownTranslations.isNotEmpty() && frame.blocks.any { normalize(it.text) in shownTranslations }) return
        listener.onTextSeen()

        val settings = prefs.sourceLang + ">" + prefs.targetLang
        val prev = last
        if (settings == lastSettings && prev != null && sameFrame(prev, frame)) return
        lastSettings = settings
        last = frame

        if (translating) {
            pending = frame // only the newest frame counts
            return
        }
        detectAndTranslate(frame)
    }

    private fun sameFrame(a: Frame, b: Frame): Boolean {
        if (a.crop != b.crop || a.blocks.size != b.blocks.size) return false
        if (!isSameText(normalize(a.combined), normalize(b.combined))) return false
        for (i in a.blocks.indices) {
            val ra = a.blocks[i].rect
            val rb = b.blocks[i].rect
            if (abs(ra.left - rb.left) > MOVE_TOLERANCE || abs(ra.top - rb.top) > MOVE_TOLERANCE ||
                abs(ra.right - rb.right) > MOVE_TOLERANCE * 3 || abs(ra.bottom - rb.bottom) > MOVE_TOLERANCE
            ) {
                return false
            }
        }
        return true
    }

    private fun detectAndTranslate(frame: Frame) {
        translating = true
        val gen = generation
        val chosen = prefs.sourceLang
        if (chosen != Languages.AUTO) {
            translateWith(chosen, frame, gen)
            return
        }
        val raw = frame.combined
        languageId.identifyLanguage(raw)
            .addOnSuccessListener { tag ->
                if (closed || gen != generation) return@addOnSuccessListener
                val detected = Languages.detectedFor(tag, raw)
                val sticky = stickyLang
                val lang = when {
                    detected == null -> sticky ?: TranslateLanguage.ENGLISH
                    // Very short snippets are often misdetected – stay with the previous language then.
                    raw.length < 12 && sticky != null -> sticky
                    else -> detected.also { stickyLang = it }
                }
                translateWith(lang, frame, gen)
            }
            .addOnFailureListener {
                if (!closed && gen == generation) translateWith(stickyLang ?: TranslateLanguage.ENGLISH, frame, gen)
            }
    }

    private fun translateWith(source: String, frame: Frame, gen: Int) {
        val target = prefs.targetLang
        if (source == target) {
            deliver(frame, frame.blocks.map { it.text }, source, gen)
            return
        }
        if (Languages.samePack(source, target)) {
            // Traditional ↔ simplified Chinese: just convert the characters.
            deliver(frame, frame.blocks.map { ChineseScript.convertBetween(it.text, source, target) }, source, gen)
            return
        }
        val pair = "$source>$target"
        val cached = frame.blocks.map { cache["$pair|${it.text}"] }
        if (cached.all { it != null }) {
            deliver(frame, cached.map { it!! }, source, gen)
            return
        }

        val translator = translatorFor(source, target)
        if (pair in readyPairs) {
            runTranslation(translator, pair, frame, source, gen)
            return
        }
        if (pair in downloadingPairs) {
            // Pack is still downloading (from an earlier attempt) – try again with the next frame.
            last = null
            finish(gen)
            return
        }

        downloadingPairs += pair
        val wifiOnly = prefs.wifiOnly
        listener.onStatus(
            appContext.getString(
                if (wifiOnly) R.string.status_downloading_wifi else R.string.status_downloading,
                Languages.displayName(source),
                Languages.displayName(target),
            ),
        )
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                downloadingPairs -= pair
                readyPairs += pair
                if (closed || gen != generation) return@addOnSuccessListener
                runTranslation(translator, pair, frame, source, gen)
            }
            .addOnFailureListener { e ->
                downloadingPairs -= pair
                Log.w(TAG, "Language pack download failed", e)
                if (closed || gen != generation) return@addOnFailureListener
                listener.onStatus(appContext.getString(R.string.status_download_failed))
                last = null // try again with the next frame
                finish(gen)
            }
    }

    /** Translates all blocks (in parallel, from the cache where possible) and delivers them together. */
    private fun runTranslation(translator: Translator, pair: String, frame: Frame, source: String, gen: Int) {
        val texts = frame.blocks.map { it.text }
        val results = arrayOfNulls<String>(texts.size)
        var remaining = texts.size
        fun done() {
            remaining--
            if (remaining == 0) deliver(frame, results.map { it ?: "" }, source, gen)
        }
        for ((i, raw) in texts.withIndex()) {
            val hit = cache["$pair|$raw"]
            if (hit != null) {
                results[i] = hit
                done()
                continue
            }
            translator.translate(ChineseScript.forInput(raw, source))
                .addOnSuccessListener { translated ->
                    val result = ChineseScript.forOutput(translated, pair.substringAfter('>'))
                    cache["$pair|$raw"] = result
                    results[i] = result
                    done()
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Translation failed", e)
                    results[i] = raw // better show the original than nothing
                    done()
                }
        }
    }

    private fun deliver(frame: Frame, translations: List<String>, source: String?, gen: Int) {
        if (gen != generation) return
        if (!closed) {
            val fresh = frame.blocks.mapIndexed { i, b ->
                LensItem(
                    rect = Rect(b.rect),
                    original = b.text,
                    translated = translations[i].ifBlank { b.text },
                    background = b.background,
                    foreground = b.foreground,
                    lineHeightPx = b.lineHeight,
                )
            }
            val stable = stabilize(fresh, frame.crop)
            val kept = keptOutside(frame).filter { k -> stable.none { Rect.intersects(it.rect, k.rect) } }
            publish(kept + stable, frame.crop, source)
        }
        finish(gen)
    }

    private fun publish(unsorted: List<LensItem>, crop: Rect, source: String?) {
        // Fixed order → each box stays in "its" window, nothing gets reassigned.
        val items = unsorted.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        lastDelivered = items
        lastDeliveredCrop = Rect(crop)
        if (source != null) lastSource = source
        shownTranslations = items
            .filter { normalize(it.translated) != normalize(it.original) }
            .map { normalize(it.translated) }
            .filter { it.length >= 4 }
            .toSet()
        listener.onResult(items, crop, source ?: lastSource)
    }

    private fun finish(gen: Int) {
        if (gen != generation) return
        translating = false
        val next = pending
        pending = null
        if (next != null && !closed) detectAndTranslate(next)
    }

    private fun translatorFor(source: String, target: String): Translator {
        val pair = "$source>$target"
        translators[pair]?.let { return it }
        // Keep only a few translators open at once (each one uses memory).
        while (translators.size >= MAX_TRANSLATORS) {
            val oldest = translators.keys.first()
            if (oldest in downloadingPairs) break
            translators.remove(oldest)?.close()
            readyPairs -= oldest
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(Languages.mlkit(source))
            .setTargetLanguage(Languages.mlkit(target))
            .build()
        return Translation.getClient(options).also { translators[pair] = it }
    }

    // ---------------------------------------------------------------- Helpers

    /** Settings changed: re-translate the next text right away. */
    fun reset() {
        generation++
        translating = false
        pending = null
        last = null
        lastSettings = ""
        shownTranslations = emptySet()
        lastDelivered = emptyList()
        lastDeliveredCrop = null
        if (prefs.sourceLang != Languages.AUTO) stickyLang = null
    }

    fun close() {
        closed = true
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
        try {
            languageId.close()
        } catch (_: Exception) {
        }
        for (t in translators.values) {
            try {
                t.close()
            } catch (_: Exception) {
            }
        }
        translators.clear()
    }

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** OCR varies slightly from frame to frame – small differences count as "same text". */
    private fun isSameText(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        val longer = max(a.length, b.length)
        if (abs(a.length - b.length) > 2 || longer > 300) return false
        val allowed = max(1, longer / 20)
        return levenshtein(a, b, allowed) <= allowed
    }

    private fun levenshtein(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            var rowMin = curr[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > limit) return rowMin
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[b.length]
    }

    companion object {
        private const val TAG = "SubLens"
        private const val MAX_TRANSLATORS = 3
        private const val MOVE_TOLERANCE = 6
    }
}
