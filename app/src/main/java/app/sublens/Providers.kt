package app.sublens

import org.json.JSONObject

/**
 * Online translation services. All speak the OpenAI "chat completions" protocol, so one client fits all.
 * Model names change every few months, so instead of one fixed model each provider has a list of
 * preferences: when testing a key, SubLens asks the provider for its current models and picks the best match.
 */
class Provider(
    val id: String,
    val name: String,
    /** Base URL ending before "/chat/completions". */
    val baseUrl: String,
    /** Page where you create a key. */
    val keyUrl: String,
    val descRes: Int,
    val dataRes: Int,
    val badges: List<Int>,
    /** Used when the model list can't be read. */
    val defaultModel: String,
    /** Regexes in order of preference; within one, the newest version wins. */
    val modelPreference: List<Regex>,
    /** Models that are never picked automatically (images, speech, embeddings …). */
    val modelExclude: Regex = DEFAULT_EXCLUDE,
    /** Extra request fields, e.g. to switch off "thinking" (slow and not needed for translation). */
    val extra: JSONObject? = null,
    /** Typical key shape, to recognise a pasted key. */
    val keyPattern: Regex? = null,
    /** The user can change the server address (own server, regional endpoint). */
    val editableUrl: Boolean = false,
) {
    companion object {
        val DEFAULT_EXCLUDE = Regex(
            "(embed|image|vision-only|tts|audio|speech|whisper|transcribe|guard|moderation|rerank|ocr|realtime|live|search|coder|math)",
            RegexOption.IGNORE_CASE,
        )
    }
}

object Providers {
    const val CUSTOM = "custom"

    val all: List<Provider> = listOf(
        Provider(
            id = "zai",
            name = "GLM · Z.AI",
            baseUrl = "https://api.z.ai/api/paas/v4",
            keyUrl = "https://z.ai/manage-apikey/apikey-list",
            descRes = R.string.prov_zai_desc,
            dataRes = R.string.prov_zai_data,
            badges = listOf(R.string.badge_free, R.string.badge_chinese),
            defaultModel = "glm-4.7-flash",
            modelPreference = listOf(Regex("^glm-[\\d.]+-flash$", RegexOption.IGNORE_CASE)),
            extra = JSONObject().put("thinking", JSONObject().put("type", "disabled")),
            keyPattern = Regex("^[0-9a-f]{32}\\.[A-Za-z0-9]{10,24}$"),
        ),
        Provider(
            id = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            keyUrl = "https://platform.deepseek.com/api_keys",
            descRes = R.string.prov_deepseek_desc,
            dataRes = R.string.prov_deepseek_data,
            badges = listOf(R.string.badge_cheap, R.string.badge_chinese),
            defaultModel = "deepseek-chat",
            // "deepseek-chat" always points to the current model without slow "thinking".
            modelPreference = listOf(Regex("^deepseek-chat$"), Regex("^deepseek-v[\\d.]+-flash$")),
            keyPattern = Regex("^sk-[0-9a-f]{32}$"),
        ),
        Provider(
            id = "qwen",
            name = "Qwen · Alibaba Cloud",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            keyUrl = "https://modelstudio.console.alibabacloud.com/model/settings/api-key",
            descRes = R.string.prov_qwen_desc,
            dataRes = R.string.prov_qwen_data,
            badges = listOf(R.string.badge_free_start, R.string.badge_chinese),
            defaultModel = "qwen-plus",
            modelPreference = listOf(Regex("^qwen-flash$"), Regex("^qwen-plus$"), Regex("^qwen-turbo$"), Regex("^qwen[\\d.]*-plus")),
            modelExclude = Regex("(mt-|vl|omni|audio|tts|coder|math|embed|image|ocr|deep-research)", RegexOption.IGNORE_CASE),
            extra = JSONObject().put("enable_thinking", false),
            keyPattern = Regex("^sk-[0-9a-f]{32}$"),
            editableUrl = true,
        ),
        Provider(
            id = "gemini",
            name = "Gemini · Google",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyUrl = "https://aistudio.google.com/apikey",
            descRes = R.string.prov_gemini_desc,
            dataRes = R.string.prov_gemini_data,
            badges = listOf(R.string.badge_free),
            defaultModel = "gemini-flash-lite-latest",
            modelPreference = listOf(
                Regex("^gemini-flash-lite-latest$"),
                Regex("^gemini-[\\d.]+-flash-lite$"),
                Regex("^gemini-flash-latest$"),
                Regex("^gemini-[\\d.]+-flash$"),
            ),
            modelExclude = Regex("(preview|exp|image|tts|audio|live|embedding|thinking|robotics|computer)", RegexOption.IGNORE_CASE),
            extra = JSONObject().put("reasoning_effort", "low"),
            keyPattern = Regex("^AIza[0-9A-Za-z_\\-]{30,40}$"),
        ),
        Provider(
            id = "groq",
            name = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            keyUrl = "https://console.groq.com/keys",
            descRes = R.string.prov_groq_desc,
            dataRes = R.string.prov_groq_data,
            badges = listOf(R.string.badge_free, R.string.badge_fast),
            defaultModel = "llama-3.3-70b-versatile",
            modelPreference = listOf(
                Regex("^qwen/qwen[\\d.]+-\\d+b$"),
                Regex("kimi-k2"),
                Regex("^llama-3\\.3-70b-versatile$"),
                Regex("^openai/gpt-oss-120b$"),
            ),
            keyPattern = Regex("^gsk_[A-Za-z0-9]{20,}$"),
        ),
        Provider(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyUrl = "https://openrouter.ai/settings/keys",
            descRes = R.string.prov_openrouter_desc,
            dataRes = R.string.prov_openrouter_data,
            badges = listOf(R.string.badge_free, R.string.badge_many),
            defaultModel = "openrouter/auto",
            modelPreference = listOf(
                Regex("^deepseek/.*:free$"),
                Regex("^qwen/.*:free$"),
                Regex("^z-ai/glm.*:free$"),
                Regex("^google/gemini.*:free$"),
                Regex("^meta-llama/.*:free$"),
            ),
            keyPattern = Regex("^sk-or-[A-Za-z0-9\\-]{20,}$"),
        ),
        Provider(
            id = CUSTOM,
            name = "OpenAI-compatible",
            baseUrl = "",
            keyUrl = "",
            descRes = R.string.prov_custom_desc,
            dataRes = R.string.prov_custom_data,
            badges = emptyList(),
            defaultModel = "",
            modelPreference = emptyList(),
            editableUrl = true,
        ),
    )

    fun byId(id: String?): Provider? = all.firstOrNull { it.id == id }

    /** Which providers a pasted key could belong to (most likely first). */
    fun guess(key: String): List<Provider> {
        val k = key.trim()
        return all.filter { p -> p.keyPattern?.matches(k) == true }
    }

    /**
     * Picks the model to use from the provider's list: first matching preference wins,
     * among several matches the highest version number.
     */
    fun pickModel(p: Provider, available: List<String>): String? {
        val usable = available.map { it.removePrefix("models/") }.filter { !p.modelExclude.containsMatchIn(it) }
        for (pref in p.modelPreference) {
            val hits = usable.filter { pref.containsMatchIn(it) }
            if (hits.isNotEmpty()) return hits.maxWithOrNull(compareBy<String> { versionKey(it) }.thenBy { -it.length })
        }
        return null
    }

    /** Models to offer in the picker: recommended ones first (newest first), then the rest A–Z. */
    fun usableModels(p: Provider, available: List<String>): List<String> {
        val ids = available.map { it.removePrefix("models/") }.distinct()
        val usable = if (p.id == CUSTOM) ids else ids.filter { !p.modelExclude.containsMatchIn(it) }
        val preferred = p.modelPreference
            .flatMap { pref -> usable.filter { pref.containsMatchIn(it) }.sortedByDescending { versionKey(it) } }
            .distinct()
        return preferred + (usable - preferred.toSet()).sorted()
    }

    /** "gemini-3.8-flash" → 3.008 … – good enough to order versions. */
    private fun versionKey(id: String): Double {
        val m = Regex("(\\d+)(?:\\.(\\d+))?").find(id) ?: return 0.0
        val major = m.groupValues[1].toDoubleOrNull() ?: 0.0
        val minor = m.groupValues[2].toDoubleOrNull() ?: 0.0
        return major + minor / 1000.0
    }
}
