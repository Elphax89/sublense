package app.sublens

import android.content.Context
import android.graphics.Bitmap
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
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bild → Text (OCR) → Sprache erkennen → übersetzen. Alles auf dem Gerät.
 * Wird ausschließlich vom Main-Thread bedient; ML Kit rechnet im Hintergrund.
 */
class TextPipeline(
    context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onTranslation(original: String, translated: String, sourceLang: String?)
        fun onTextSeen()
        fun onNoText()
        fun onStatus(message: String)
    }

    private val appContext = context.applicationContext

    /** Wird vom Aufnahme-Thread gelesen: solange true, wird kein neues Bild geschickt. */
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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 120
    }

    private var lastNormalized = ""
    private var lastSettings = ""
    private var stickyLang: String? = null
    private var translating = false
    private var pending: String? = null

    /** Steigt bei jeder Einstellungsänderung – veraltete Ergebnisse werden verworfen. */
    private var generation = 0

    // ---------------------------------------------------------------- OCR

    fun process(bitmap: Bitmap) {
        if (closed) {
            bitmap.recycle()
            busy = false
            return
        }
        busy = true
        val script = Languages.scriptFor(prefs.sourceLang) ?: prefs.autoScript
        val rec = recognizerFor(script)
        rec.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> if (!closed) onText(clean(text, script)) }
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
        val options: TextRecognizerOptionsInterface = when (script) {
            Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            Script.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        }
        return TextRecognition.getClient(options).also {
            recognizer = it
            recognizerScript = script
        }
    }

    /**
     * Untertitel bestehen meist aus 1–2 Zeilen, die zusammen einen Satz ergeben:
     * Zeilen eines Blocks werden verbunden, Blöcke (z. B. zwei Sprecher) bleiben getrennt.
     * Zeilen ohne Buchstaben (Zeitangaben, Symbole) fliegen raus.
     */
    private fun clean(text: Text, script: Script): String {
        val separator = if (script.joinsWithoutSpace) "" else " "
        return text.textBlocks
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .mapNotNull { block ->
                block.lines
                    .map { it.text.trim() }
                    .filter { line -> line.count { ch -> ch.isLetter() } >= 2 }
                    .joinToString(separator)
                    .takeIf { it.isNotBlank() }
            }
            .joinToString("\n")
            .trim()
    }

    private fun onOcrError(e: Exception) {
        if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
            listener.onStatus(appContext.getString(R.string.status_ocr_model))
        } else {
            Log.w(TAG, "Texterkennung fehlgeschlagen", e)
        }
    }

    // ---------------------------------------------------------------- Text → Übersetzung

    private fun onText(raw: String) {
        if (raw.length < 2) {
            // Nichts im Rahmen: beim nächsten Auftauchen neu anzeigen (kommt dann aus dem Cache).
            lastNormalized = ""
            listener.onNoText()
            return
        }
        listener.onTextSeen()

        val settings = prefs.sourceLang + ">" + prefs.targetLang
        val normalized = normalize(raw)
        if (settings == lastSettings && isSameText(normalized, lastNormalized)) return
        lastSettings = settings
        lastNormalized = normalized

        if (translating) {
            pending = raw // nur der neueste Untertitel zählt
            return
        }
        detectAndTranslate(raw)
    }

    private fun detectAndTranslate(raw: String) {
        translating = true
        val gen = generation
        val chosen = prefs.sourceLang
        if (chosen != Languages.AUTO) {
            translateWith(chosen, raw, gen)
            return
        }
        languageId.identifyLanguage(raw)
            .addOnSuccessListener { tag ->
                if (closed || gen != generation) return@addOnSuccessListener
                val detected = Languages.normalizeDetected(tag)
                val sticky = stickyLang
                val lang = when {
                    detected == null -> sticky ?: TranslateLanguage.ENGLISH
                    // Sehr kurze Schnipsel werden oft falsch erkannt – dann bei der bisherigen Sprache bleiben.
                    raw.length < 12 && sticky != null -> sticky
                    else -> detected.also { stickyLang = it }
                }
                translateWith(lang, raw, gen)
            }
            .addOnFailureListener {
                if (!closed && gen == generation) translateWith(stickyLang ?: TranslateLanguage.ENGLISH, raw, gen)
            }
    }

    private fun translateWith(source: String, raw: String, gen: Int) {
        val target = prefs.targetLang
        if (source == target) {
            deliver(raw, raw, source, gen)
            return
        }
        val pair = "$source>$target"
        cache["$pair|$raw"]?.let {
            deliver(raw, it, source, gen)
            return
        }

        val translator = translatorFor(source, target)
        if (pair in readyPairs) {
            runTranslation(translator, pair, raw, source, gen)
            return
        }
        if (pair in downloadingPairs) {
            // Paket lädt noch (aus einem früheren Versuch) – beim nächsten Bild erneut probieren.
            lastNormalized = ""
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
                runTranslation(translator, pair, raw, source, gen)
            }
            .addOnFailureListener { e ->
                downloadingPairs -= pair
                Log.w(TAG, "Sprachpaket-Download fehlgeschlagen", e)
                if (closed || gen != generation) return@addOnFailureListener
                listener.onStatus(appContext.getString(R.string.status_download_failed))
                lastNormalized = "" // nächster Versuch beim nächsten Bild
                finish(gen)
            }
    }

    private fun runTranslation(translator: Translator, pair: String, raw: String, source: String, gen: Int) {
        translator.translate(raw)
            .addOnSuccessListener { result ->
                cache["$pair|$raw"] = result
                deliver(raw, result, source, gen)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Übersetzung fehlgeschlagen", e)
                readyPairs -= pair
                deliver(raw, raw, source, gen) // lieber Original zeigen als nichts
            }
    }

    private fun deliver(original: String, translated: String, source: String?, gen: Int) {
        if (gen != generation) return
        if (!closed) listener.onTranslation(original, translated, source)
        finish(gen)
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
        // Nur wenige Übersetzer gleichzeitig offen halten (jeder belegt Speicher).
        while (translators.size >= MAX_TRANSLATORS) {
            val oldest = translators.keys.first()
            if (oldest in downloadingPairs) break
            translators.remove(oldest)?.close()
            readyPairs -= oldest
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also { translators[pair] = it }
    }

    // ---------------------------------------------------------------- Hilfen

    /** Einstellungen geändert: nächsten Text sofort neu übersetzen. */
    fun reset() {
        generation++
        translating = false
        pending = null
        lastNormalized = ""
        lastSettings = ""
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

    /** OCR schwankt leicht von Bild zu Bild – kleine Abweichungen gelten als "gleicher Text". */
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
    }
}
