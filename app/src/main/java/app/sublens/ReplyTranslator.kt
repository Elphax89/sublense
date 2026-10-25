package app.sublens

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Translates your own reply (own language → chat's language).
 * Uses the same offline language packs as the subtitle translation.
 */
class ReplyTranslator(private val prefs: Prefs, private val cacheSize: Int = 40) {

    private val main = Handler(Looper.getMainLooper())
    private val translators = LinkedHashMap<String, Translator>()
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > cacheSize
    }
    private var closed = false

    /**
     * [onSlow] fires when it takes longer (usually: a language pack is downloading).
     * [onDone] delivers the translation, or null on error.
     */
    fun translate(text: String, from: String, to: String, onSlow: () -> Unit, onDone: (String?) -> Unit) {
        if (from == to) {
            onDone(text)
            return
        }
        if (Languages.samePack(from, to)) {
            onDone(ChineseScript.convertBetween(text, from, to))
            return
        }
        val pair = "$from>$to"
        cache["$pair|$text"]?.let {
            onDone(it)
            return
        }

        val translator = translatorFor(from, to)
        var finished = false
        val slow = Runnable { if (!finished && !closed) onSlow() }
        main.postDelayed(slow, SLOW_AFTER_MS)

        fun done(result: String?) {
            finished = true
            main.removeCallbacks(slow)
            if (!closed) onDone(result)
        }

        val conditions = DownloadConditions.Builder().apply { if (prefs.wifiOnly) requireWifi() }.build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                if (closed) return@addOnSuccessListener
                translator.translate(ChineseScript.forInput(text, from))
                    .addOnSuccessListener { translated ->
                        val result = ChineseScript.forOutput(translated, to)
                        cache["$pair|$text"] = result
                        done(result)
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Reply translation failed", e)
                        done(null)
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Language pack for reply missing", e)
                done(null)
            }
    }

    private fun translatorFor(from: String, to: String): Translator {
        val pair = "$from>$to"
        translators[pair]?.let { return it }
        while (translators.size >= MAX_TRANSLATORS) {
            val oldest = translators.keys.first()
            translators.remove(oldest)?.close()
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(Languages.mlkit(from))
            .setTargetLanguage(Languages.mlkit(to))
            .build()
        return Translation.getClient(options).also { translators[pair] = it }
    }

    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        for (t in translators.values) {
            try {
                t.close()
            } catch (_: Exception) {
            }
        }
        translators.clear()
    }

    companion object {
        private const val TAG = "SubLens"
        private const val SLOW_AFTER_MS = 700L
        private const val MAX_TRANSLATORS = 3
    }
}
