package app.sublens

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Talks to one OpenAI-compatible service ("chat completions"). Blocking – call it off the main thread.
 * No Android parts here, so it can be tested on a normal JVM against a mock server.
 */
object OnlineClient {

    enum class Failure { NO_KEY, AUTH, LIMIT, MODEL, NETWORK, FORMAT, SERVER }

    class Failed(val kind: Failure, message: String) : Exception(message)

    /** Long screens go out in parts (keeps answers fast and within limits). */
    private const val MAX_ITEMS = 40
    private const val MAX_CHARS = 5000
    const val MAX_CONTEXT = 12

    /** "provider|model" that reject our optional settings (temperature, thinking off …). */
    private val bareModels = HashSet<String>()

    /**
     * Translates [texts] (one screen, in reading order) from [from] (null = detect) into [to].
     * [context] are earlier lines that only help understanding. [reply] = the user's own chat message.
     * Throws [Failed].
     */
    fun translate(
        p: Provider,
        url: String,
        key: String,
        model: String,
        texts: List<String>,
        from: String?,
        to: String,
        context: List<String> = emptyList(),
        reply: Boolean = false,
    ): List<String> {
        val out = ArrayList<String>(texts.size)
        var start = 0
        while (start < texts.size) {
            var end = start
            var chars = 0
            while (end < texts.size && end - start < MAX_ITEMS && (chars + texts[end].length < MAX_CHARS || end == start)) {
                chars += texts[end].length
                end++
            }
            val earlier = (context + texts.subList(0, start)).takeLast(MAX_CONTEXT)
            out.addAll(requestSplitting(p, url, key, model, texts.subList(start, end), from, to, earlier, reply))
            start = end
        }
        return out
    }

    /** If the answer doesn't have one translation per item (lines merged), ask again in two halves. */
    private fun requestSplitting(
        p: Provider,
        url: String,
        key: String,
        model: String,
        texts: List<String>,
        from: String?,
        to: String,
        context: List<String>,
        reply: Boolean,
    ): List<String> = try {
        request(p, url, key, model, texts, from, to, context, reply)
    } catch (f: Failed) {
        if (f.kind != Failure.FORMAT || texts.size < 2) throw f
        val half = texts.size / 2
        val first = texts.subList(0, half)
        request(p, url, key, model, first, from, to, context, reply) +
            request(p, url, key, model, texts.subList(half, texts.size), from, to, (context + first).takeLast(MAX_CONTEXT), reply)
    }

    private fun request(
        p: Provider,
        url: String,
        key: String,
        model: String,
        texts: List<String>,
        from: String?,
        to: String,
        context: List<String>,
        reply: Boolean,
    ): List<String> {
        val system = Prompt.system(from, to, texts.size, reply)
        val user = Prompt.user(texts, context)
        val chars = texts.sumOf { it.length }
        // Generous: some models "think" first, and those tokens count too. Only what is generated is billed.
        val maxTokens = (chars * 4 + 1024).coerceIn(1024, 8192)
        val content = chat(p, url, key, model, system, user, maxTokens)
        return Prompt.parse(content, texts.size) ?: throw Failed(Failure.FORMAT, "unexpected answer: ${content.take(200)}")
    }

    private fun chat(p: Provider, url: String, key: String, model: String, system: String, user: String, maxTokens: Int): String {
        val id = "${p.id}|$model"
        val bare = synchronized(bareModels) { id in bareModels }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(msg("system", system)).put(msg("user", user)))
        if (!bare) {
            body.put("temperature", 0.2).put("max_tokens", maxTokens)
            p.extra?.let { extra -> for (k in extra.keys()) body.put(k, extra.get(k)) }
        }
        val (code, text) = http("POST", "$url/chat/completions", key, body.toString(), p.id)
        if (code == 400 && !bare && !looksLikeModelError(text)) {
            // The model doesn't accept one of our settings – send only model and messages from now on.
            synchronized(bareModels) { bareModels.add(id) }
            return chat(p, url, key, model, system, user, maxTokens)
        }
        check(code, text)
        return try {
            val choice = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            choice.getJSONObject("message").optString("content", "")
        } catch (e: Exception) {
            throw Failed(Failure.FORMAT, "bad response: ${text.take(200)}")
        }
    }

    /** The service's current model ids. Throws [Failed]. */
    fun listModels(url: String, key: String): List<String> {
        val (code, text) = http("GET", "$url/models", key, null, null)
        check(code, text)
        return try {
            val data = JSONObject(text).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }
                .map { it.removePrefix("models/") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun msg(role: String, content: String) = JSONObject().put("role", role).put("content", content)

    private fun looksLikeModelError(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return "model" in t && (
            "not found" in t || "does not exist" in t || "not exist" in t || "invalid model" in t ||
                "unknown model" in t || "not supported" in t || "no such model" in t
            )
    }

    private fun check(code: Int, text: String) {
        if (code in 200..299) return
        val short = text.take(300)
        throw when {
            code == 401 || code == 403 -> Failed(Failure.AUTH, short)
            code == 402 || code == 429 -> Failed(Failure.LIMIT, short)
            code == 404 || looksLikeModelError(text) -> Failed(Failure.MODEL, short)
            code >= 500 -> Failed(Failure.SERVER, short)
            else -> Failed(Failure.SERVER, "$code $short")
        }
    }

    private fun http(method: String, url: String, key: String, body: String?, provider: String?): Pair<Int, String> {
        val c = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw Failed(Failure.NETWORK, "bad address: $url")
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 45_000
            if (key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (provider == "openrouter") {
                c.setRequestProperty("HTTP-Referer", "https://github.com/Elphax89/sublense")
                c.setRequestProperty("X-Title", "SubLens")
            }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to text
        } catch (e: IOException) {
            throw Failed(Failure.NETWORK, e.message ?: "network")
        } finally {
            c.disconnect()
        }
    }

    /** The readable part of an error answer (most services send {"error": {"message": …}}). */
    fun shortError(raw: String?): String {
        val text = raw ?: return "?"
        val json = text.indexOf('{')
        if (json >= 0) {
            try {
                val o = JSONObject(text.substring(json))
                val m = when (val e = o.opt("error")) {
                    is JSONObject -> e.optString("message")
                    is String -> e
                    else -> o.optString("message")
                }
                if (!m.isNullOrBlank()) return m.take(160)
            } catch (_: Exception) {
            }
        }
        return text.take(160)
    }
}
