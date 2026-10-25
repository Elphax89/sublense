package app.sublens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.sublens.OnlineClient.Failed
import app.sublens.OnlineClient.Failure
import java.util.concurrent.Executors

/**
 * Translation with an online AI service (see [OnlineClient]), with the key, model and settings of the
 * chosen provider. All texts of one screen go out in one request, so the model sees the conversation and
 * translates chats, slang and Chinese far better than the small offline packs. When it fails (no network,
 * limit reached, wrong key) it pauses for a while and callers translate offline.
 */
object OnlineTranslator {

    class TestResult(
        val ok: Boolean,
        val model: String?,
        val models: List<String>,
        val sampleIn: String,
        val sampleOut: String?,
        val failure: Failed?,
    )

    private const val TAG = "SubLens"
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var pausedUntil = 0L

    @Volatile
    private var pauseStep = 0

    private val keyCache = HashMap<String, String?>()

    // ---------------------------------------------------------------- Keys and state

    fun key(context: Context, provider: String): String? = synchronized(keyCache) {
        keyCache.getOrPut(provider) { SecretStore(context).get("key_$provider") }
    }

    fun setKey(context: Context, provider: String, key: String?) {
        SecretStore(context).put("key_$provider", key)
        synchronized(keyCache) { keyCache[provider] = key?.trim()?.takeIf { it.isNotEmpty() } }
        Prefs(context).setOnlineVerified(provider, false)
        resume()
    }

    fun url(prefs: Prefs, p: Provider): String = (prefs.onlineUrl(p.id) ?: p.baseUrl).trimEnd('/')

    /** Online translation is switched on, set up and not paused after an error. */
    fun isActive(context: Context): Boolean {
        val prefs = Prefs(context)
        if (!prefs.onlineEnabled) return false
        if (SystemClock.uptimeMillis() < pausedUntil) return false
        return isConfigured(context, Providers.byId(prefs.onlineProvider) ?: return false)
    }

    /** The service has what it needs: a key (or, for an own server, an address and a model). */
    fun isConfigured(context: Context, p: Provider): Boolean {
        if (p.id == Providers.CUSTOM) {
            val prefs = Prefs(context)
            return url(prefs, p).isNotBlank() && !prefs.onlineModel(p.id).isNullOrBlank()
        }
        return key(context, p.id) != null
    }

    /** Short description for the home screen, e.g. "DeepSeek · deepseek-chat". */
    fun summary(context: Context): String? {
        val prefs = Prefs(context)
        val p = Providers.byId(prefs.onlineProvider) ?: return null
        val model = prefs.onlineModel(p.id) ?: p.defaultModel
        return if (model.isBlank()) p.name else "${p.name} · $model"
    }

    /** End a pause (new key, switched on again …). */
    fun resume() {
        pausedUntil = 0L
        pauseStep = 0
    }

    // ---------------------------------------------------------------- Translating

    /**
     * Translates [texts] (one screen, in reading order) from [from] (null = detect) into [to].
     * [contextLines] are earlier lines that are only there to help understanding.
     * [reply] = the texts are the user's own reply for a chat.
     * [done] runs on the main thread with the translations, or null and the reason.
     */
    fun translate(
        context: Context,
        texts: List<String>,
        from: String?,
        to: String,
        contextLines: List<String> = emptyList(),
        reply: Boolean = false,
        done: (List<String>?, Failed?) -> Unit,
    ) {
        val app = context.applicationContext
        pool.execute {
            try {
                val result = translateNow(app, texts, from, to, contextLines, reply)
                pauseStep = 0
                main.post { done(result, null) }
            } catch (f: Failed) {
                pauseAfter(f)
                main.post { done(null, f) }
            } catch (e: Exception) {
                val f = Failed(Failure.SERVER, e.message ?: e.toString())
                pauseAfter(f)
                main.post { done(null, f) }
            }
        }
    }

    private fun pauseAfter(f: Failed) {
        val now = SystemClock.uptimeMillis()
        pausedUntil = when (f.kind) {
            // Limits reset after a while – try again later, longer each time (1, 2, 4 … 15 minutes).
            Failure.LIMIT -> now + minOf(15 * 60_000L, 60_000L shl minOf(pauseStep++, 4))
            Failure.NETWORK -> now + 20_000L
            Failure.AUTH, Failure.NO_KEY -> now + 10 * 60_000L
            else -> now + 30_000L
        }
        Log.w(TAG, "Online translation paused: ${f.kind} ${f.message}")
    }

    private fun translateNow(
        context: Context,
        texts: List<String>,
        from: String?,
        to: String,
        contextLines: List<String>,
        reply: Boolean,
    ): List<String> {
        val prefs = Prefs(context)
        val p = Providers.byId(prefs.onlineProvider) ?: throw Failed(Failure.NO_KEY, "no provider")
        val key = key(context, p.id) ?: if (p.id == Providers.CUSTOM) "" else throw Failed(Failure.NO_KEY, "no key")
        val url = url(prefs, p)
        val model = prefs.onlineModel(p.id) ?: p.defaultModel
        return try {
            OnlineClient.translate(p, url, key, model, texts, from, to, contextLines, reply)
        } catch (f: Failed) {
            if (f.kind != Failure.MODEL) throw f
            // The model was renamed or retired: pick a current one and try once more.
            val fresh = Providers.pickModel(p, OnlineClient.listModels(url, key))?.takeIf { it != model } ?: throw f
            prefs.setOnlineModel(p.id, fresh)
            OnlineClient.translate(p, url, key, fresh, texts, from, to, contextLines, reply)
        }
    }

    /** The provider's current model list (in the background; [done] on the main thread). */
    fun models(context: Context, p: Provider, done: (List<String>?, Failed?) -> Unit) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        pool.execute {
            try {
                val key = key(app, p.id) ?: if (p.id == Providers.CUSTOM) "" else throw Failed(Failure.NO_KEY, "no key")
                val list = OnlineClient.listModels(url(prefs, p), key)
                main.post { done(list, null) }
            } catch (f: Failed) {
                main.post { done(null, f) }
            } catch (e: Exception) {
                main.post { done(null, Failed(Failure.SERVER, e.toString())) }
            }
        }
    }

    // ---------------------------------------------------------------- Messages

    /** Status line in the lens when SubLens falls back to offline. */
    fun fallbackMessage(kind: Failure): Int = when (kind) {
        Failure.LIMIT -> R.string.online_fallback_limit
        Failure.NETWORK -> R.string.online_fallback_network
        Failure.AUTH, Failure.NO_KEY -> R.string.online_fallback_auth
        else -> R.string.online_fallback_other
    }

    /** Explanation in the catalog after a failed test. */
    fun errorMessage(context: Context, p: Provider, f: Failed): String = when (f.kind) {
        Failure.NO_KEY -> context.getString(R.string.online_err_no_key)
        Failure.AUTH -> context.getString(if (p.id == "qwen") R.string.online_err_auth_qwen else R.string.online_err_auth)
        Failure.LIMIT -> context.getString(R.string.online_err_limit)
        Failure.MODEL -> context.getString(if (p.id == Providers.CUSTOM) R.string.online_err_model_custom else R.string.online_err_model)
        Failure.NETWORK -> context.getString(R.string.online_err_network)
        Failure.FORMAT -> context.getString(R.string.online_err_format)
        Failure.SERVER -> context.getString(R.string.online_err_server, OnlineClient.shortError(f.message))
    }

    // ---------------------------------------------------------------- Testing a key

    /**
     * Checks a provider: reads its model list (that also checks the key), picks a model,
     * and translates a small sample. [done] runs on the main thread.
     */
    fun test(context: Context, p: Provider, done: (TestResult) -> Unit) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        val target = prefs.targetLang
        // A Chinese chat line, or an English one if you translate into Chinese anyway.
        val chineseTarget = target == "zh" || target == Languages.ZH_HANS
        val sample = if (chineseTarget) SAMPLE_EN else SAMPLE_ZH
        val sampleFrom = if (chineseTarget) "en" else Languages.ZH_HANS
        pool.execute {
            var models: List<String> = emptyList()
            var model: String? = null
            try {
                val key = key(app, p.id) ?: if (p.id == Providers.CUSTOM) "" else throw Failed(Failure.NO_KEY, "no key")
                val url = url(prefs, p)
                if (url.isBlank()) throw Failed(Failure.NETWORK, "no address")
                models = try {
                    OnlineClient.listModels(url, key)
                } catch (f: Failed) {
                    if (f.kind == Failure.AUTH || f.kind == Failure.NETWORK) throw f
                    emptyList() // some services have no model list – then the default is used
                }
                val auto = Providers.pickModel(p, models)
                    ?: models.firstOrNull { p.id == Providers.CUSTOM && !p.modelExclude.containsMatchIn(it) }
                    ?: p.defaultModel.takeIf { it.isNotBlank() }
                val chosen = prefs.onlineModel(p.id) ?: auto ?: throw Failed(Failure.MODEL, "no model")
                model = chosen
                val out = try {
                    OnlineClient.translate(p, url, key, chosen, listOf(sample), sampleFrom, target)
                } catch (f: Failed) {
                    // The saved model is gone (renamed or retired): use the recommended one.
                    if (f.kind != Failure.MODEL || auto == null || auto == chosen) throw f
                    model = auto
                    OnlineClient.translate(p, url, key, auto, listOf(sample), sampleFrom, target)
                }
                prefs.setOnlineModel(p.id, model)
                prefs.setOnlineVerified(p.id, true)
                resume()
                main.post { done(TestResult(true, model, models, sample, out.firstOrNull(), null)) }
            } catch (f: Failed) {
                prefs.setOnlineVerified(p.id, false)
                main.post { done(TestResult(false, model, models, sample, null, f)) }
            } catch (e: Exception) {
                prefs.setOnlineVerified(p.id, false)
                main.post { done(TestResult(false, model, models, sample, null, Failed(Failure.SERVER, e.toString()))) }
            }
        }
    }

    private const val SAMPLE_ZH = "哈哈，你今天晚上有空吗？我们去吃火锅吧，我请客！"
    private const val SAMPLE_EN = "Haha, are you free tonight? Let's go for hotpot, my treat!"
}
