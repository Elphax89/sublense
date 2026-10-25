package app.sublens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
 * Jeder Textblock wird einzeln übersetzt und mit Position, Hintergrund- und Schriftfarbe
 * geliefert, damit die Übersetzung direkt in der Linse über dem Original liegen kann.
 * Wird ausschließlich vom Main-Thread bedient; ML Kit rechnet im Hintergrund.
 */
class TextPipeline(
    context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        /** Ein Ergebnis pro Bild. [items] sind in Koordinaten des Ausschnitts [crop]. */
        fun onResult(items: List<LensItem>, crop: Rect, sourceLang: String?)
        fun onTextSeen()
        fun onNoText()

        /** Außerhalb des neu gelesenen Bereichs passt die Anzeige nicht mehr – bitte alles neu lesen. */
        fun onStale()
        fun onStatus(message: String)
    }

    /** Ein erkannter Textblock. */
    private class Block(
        val text: String,
        val rect: Rect,
        val lineHeight: Int,
        val background: Int,
        val foreground: Int,
    )

    /** [region]: nur dieser Teil des Ausschnitts wurde neu gelesen (Rest bleibt wie angezeigt). */
    private class Frame(val blocks: List<Block>, val crop: Rect, val combined: String, val region: Rect? = null)

    /** Zuletzt gelieferte Blöcke und Sprache – für Teil-Aktualisierungen. */
    private var lastDelivered: List<LensItem> = emptyList()
    private var lastDeliveredCrop: Rect? = null
    private var lastSource: String? = null

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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 400
    }

    private var last: Frame? = null
    private var lastSettings = ""
    private var stickyLang: String? = null
    private var translating = false
    private var pending: Frame? = null

    /** Zuletzt angezeigte Übersetzungen – taucht eine davon im Bild auf, sieht die Kamera unser eigenes Overlay. */
    private var shownTranslations: Set<String> = emptySet()

    /** Steigt bei jeder Einstellungsänderung – veraltete Ergebnisse werden verworfen. */
    private var generation = 0

    // ---------------------------------------------------------------- OCR

    /**
     * Liest [bitmap]. Ist [region] gesetzt, werden nur Blöcke darin neu übersetzt; alle anderen
     * bleiben genau so stehen wie zuletzt (kein Zucken, wenn sich nur z. B. ein GIF bewegt).
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
     * Zeilen eines Blocks werden zu einem Satz verbunden, Blöcke (Absätze, Sprecher,
     * Chat-Nachrichten) bleiben getrennt. Zeilen ohne Buchstaben (Uhrzeiten, Symbole) fliegen raus.
     */
    private fun buildFrame(text: Text, script: Script, bitmap: Bitmap, crop: Rect): Frame {
        val separator = if (script.joinsWithoutSpace) "" else " "
        val blocks = ArrayList<Block>()
        for (block in text.textBlocks) {
            // Ein ML-Kit-Block kann Name (farbig, andere Größe), Zitat und Nachricht enthalten.
            // Zeilen nur zusammenfassen, wenn Schriftgröße, Farben und Abstand zusammenpassen –
            // sonst bekommt jeder Teil seine eigene Box mit eigener Schriftgröße.
            var group = ArrayList<LineInfo>()
            for (line in block.lines) {
                if (line.text.count { ch -> ch.isLetter() } < 2) continue
                val box = line.boundingBox ?: continue
                if (box.isEmpty) continue
                // Vom Linsenrand angeschnittene Zeile: nur halb lesbar – weglassen.
                if (box.top <= 1 || box.bottom >= bitmap.height - 2) continue
                val (bg, fg) = sampleColors(bitmap, box)
                val info = LineInfo(line.text.trim(), Rect(box), bg, fg)
                if (group.isNotEmpty() && !fitsGroup(group, info)) {
                    addGroup(group, separator, blocks)
                    group = ArrayList()
                }
                group += info
            }
            addGroup(group, separator, blocks)
        }
        blocks.sortWith(compareBy({ it.rect.top }, { it.rect.left }))
        val combined = blocks.joinToString("\n") { it.text }
        return Frame(blocks, Rect(crop), combined)
    }

    private class LineInfo(val text: String, val rect: Rect, val bg: Int, val fg: Int)

    private fun fitsGroup(group: List<LineInfo>, line: LineInfo): Boolean {
        val avg = group.sumOf { it.rect.height() } / group.size.toFloat()
        val h = line.rect.height().toFloat()
        if (max(avg, h) / max(1f, min(avg, h)) > 1.3f) return false
        if (line.rect.top - group.last().rect.bottom > avg * 0.9f) return false
        if (colorDistance(line.fg, group.first().fg) > 150) return false
        if (colorDistance(line.bg, group.first().bg) > 90) return false
        return true
    }

    private fun addGroup(group: List<LineInfo>, separator: String, out: MutableList<Block>) {
        if (group.isEmpty()) return
        val joined = group.joinToString(separator) { it.text }.trim()
        if (joined.length < 2) return
        val rect = Rect(group[0].rect)
        for (l in group) rect.union(l.rect)
        val lineHeight = group.sumOf { it.rect.height() } / group.size
        out += Block(joined, rect, lineHeight, group[0].bg, group[0].fg)
    }

    private fun onOcrError(e: Exception) {
        if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
            listener.onStatus(appContext.getString(R.string.status_ocr_model))
        } else {
            Log.w(TAG, "Texterkennung fehlgeschlagen", e)
        }
    }

    // ---------------------------------------------------------------- Farben

    /**
     * Hintergrund = häufigste Farbe direkt um den Text herum (Median des Randes),
     * Schrift = die Pixel im Block, die sich am stärksten vom Hintergrund abheben.
     */
    private fun sampleColors(bitmap: Bitmap, r: Rect): Pair<Int, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val pad = 3
        val ring = ArrayList<Int>(96)
        val left = (r.left - pad).coerceIn(0, w - 1)
        val right = (r.right + pad).coerceIn(0, w - 1)
        val top = (r.top - pad).coerceIn(0, h - 1)
        val bottom = (r.bottom + pad).coerceIn(0, h - 1)
        val stepX = max(1, (right - left) / 24)
        val stepY = max(1, (bottom - top) / 8)
        var x = left
        while (x <= right) {
            ring += bitmap.getPixel(x, top)
            ring += bitmap.getPixel(x, bottom)
            x += stepX
        }
        var y = top
        while (y <= bottom) {
            ring += bitmap.getPixel(left, y)
            ring += bitmap.getPixel(right, y)
            y += stepY
        }
        val bg = medianColor(ring)

        val inner = ArrayList<Int>(400)
        val il = r.left.coerceIn(0, w - 1)
        val ir = r.right.coerceIn(0, w - 1)
        val iTop = r.top.coerceIn(0, h - 1)
        val ib = r.bottom.coerceIn(0, h - 1)
        val sx = max(1, (ir - il) / 40)
        val sy = max(1, (ib - iTop) / 10)
        y = iTop
        while (y <= ib) {
            x = il
            while (x <= ir) {
                inner += bitmap.getPixel(x, y)
                x += sx
            }
            y += sy
        }
        val sorted = inner.sortedByDescending { colorDistance(it, bg) }
        val take = max(1, sorted.size / 8)
        var rs = 0
        var gs = 0
        var bs = 0
        for (i in 0 until take) {
            val c = sorted[i]
            rs += Color.red(c)
            gs += Color.green(c)
            bs += Color.blue(c)
        }
        var fg = Color.rgb(rs / take, gs / take, bs / take)
        if (colorDistance(fg, bg) < 90) {
            fg = if (luminance(bg) > 0.55f) Color.BLACK else Color.WHITE
        }
        return Color.rgb(Color.red(bg), Color.green(bg), Color.blue(bg)) to fg
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.BLACK
        val r = colors.map { Color.red(it) }.sorted()[colors.size / 2]
        val g = colors.map { Color.green(it) }.sorted()[colors.size / 2]
        val b = colors.map { Color.blue(it) }.sorted()[colors.size / 2]
        return Color.rgb(r, g, b)
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    // ---------------------------------------------------------------- Text → Übersetzung

    /**
     * Nur Blöcke im neu gelesenen Bereich behalten (die anderen zeigen gerade unsere eigene Übersetzung).
     * Passt das, was außerhalb zu sehen ist, nicht mehr zu dem, was wir dort anzeigen (z. B. weil
     * gescrollt wurde), wird das Bild verworfen und eine komplette Neuaufnahme angefordert (null).
     */
    private fun restrict(frame: Frame, region: Rect?, drawn: List<Rect>): Frame? {
        if (region == null || lastDeliveredCrop != frame.crop || lastDelivered.isEmpty()) return frame
        val inside = frame.blocks.filter { Rect.intersects(it.rect, region) }
        // Unter unseren eigenen Übersetzungen kann man nichts prüfen (fremde Schrift, die die
        // Erkennung oft gar nicht lesen kann) – nur Stellen, an denen das Original sichtbar ist.
        val outside = frame.blocks.filter { b ->
            !Rect.intersects(b.rect, region) && drawn.none { d -> Rect.intersects(Rect(d).apply { inset(-2, -2) }, b.rect) }
        }
        if (looksStale(outside)) {
            listener.onStale()
            return null
        }
        return Frame(inside, frame.crop, inside.joinToString("\n") { it.text }, Rect(region))
    }

    private fun looksStale(outside: List<Block>): Boolean {
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
        // Sichtbares Original, das nicht (mehr) dort steht, wo wir es kennen → es hat sich etwas verschoben.
        return bad >= 2 || (bad == 1 && counted == 1)
    }

    private fun textMatches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length >= 3 && b.contains(a)) return true
        if (b.length >= 3 && a.contains(b)) return true
        return fuzzySame(a, b)
    }

    /** Toleranter Textvergleich (für animierte Emojis, die OCR mal als Buchstaben liest, mal nicht). */
    private fun fuzzySame(a: String, b: String): Boolean {
        if (isSameText(a, b)) return true
        val longer = max(a.length, b.length)
        if (longer < 6 || longer > 300) return false
        val allowed = max(2, longer / 6)
        if (abs(a.length - b.length) > allowed) return false
        return levenshtein(a, b, allowed) <= allowed
    }

    /**
     * Steht ein Block praktisch unverändert an derselben Stelle, wird exakt die alte Anzeige übernommen –
     * so springen weder Schriftgröße noch Übersetzung, nur weil die Erkennung leicht schwankt.
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

    /** Alte Blöcke außerhalb des neu gelesenen Bereichs. */
    private fun keptOutside(frame: Frame): List<LensItem> {
        val region = frame.region ?: return emptyList()
        if (lastDeliveredCrop != frame.crop) return emptyList()
        return lastDelivered.filter { !Rect.intersects(it.rect, region) }
    }

    private fun onText(frame: Frame) {
        if (frame.blocks.isEmpty() && frame.region != null) {
            // Im bewegten Bereich steht kein Text (mehr) – Rest unverändert lassen.
            val kept = keptOutside(frame)
            if (kept.size != lastDelivered.size) publish(kept, frame.crop, lastSource)
            return
        }
        if (frame.blocks.isEmpty()) {
            last = null
            listener.onNoText()
            return
        }
        // Eigene Übersetzung im Bild? Dann war das Overlay beim Aufnehmen noch sichtbar – Bild verwerfen.
        if (prefs.lensMode && shownTranslations.isNotEmpty() && frame.blocks.any { normalize(it.text) in shownTranslations }) return
        listener.onTextSeen()

        val settings = prefs.sourceLang + ">" + prefs.targetLang
        val prev = last
        if (settings == lastSettings && prev != null && sameFrame(prev, frame)) return
        lastSettings = settings
        last = frame

        if (translating) {
            pending = frame // nur das neueste Bild zählt
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
                val detected = Languages.normalizeDetected(tag)
                val sticky = stickyLang
                val lang = when {
                    detected == null -> sticky ?: TranslateLanguage.ENGLISH
                    // Sehr kurze Schnipsel werden oft falsch erkannt – dann bei der bisherigen Sprache bleiben.
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
            // Paket lädt noch (aus einem früheren Versuch) – beim nächsten Bild erneut probieren.
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
                Log.w(TAG, "Sprachpaket-Download fehlgeschlagen", e)
                if (closed || gen != generation) return@addOnFailureListener
                listener.onStatus(appContext.getString(R.string.status_download_failed))
                last = null // nächster Versuch beim nächsten Bild
                finish(gen)
            }
    }

    /** Übersetzt alle Blöcke (parallel, aus dem Cache wo möglich) und liefert sie gemeinsam. */
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
                    Log.w(TAG, "Übersetzung fehlgeschlagen", e)
                    results[i] = raw // lieber Original zeigen als nichts
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
        // Feste Reihenfolge → jede Box bleibt in "ihrem" Fenster, nichts wird umgehängt.
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
        private const val MOVE_TOLERANCE = 6
    }
}
