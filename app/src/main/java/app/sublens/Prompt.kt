package app.sublens

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The instructions for the AI and reading its answer. */
object Prompt {

    /** Language names the model understands without doubt. */
    fun name(tag: String): String = when (tag) {
        "zh" -> "Chinese written in Traditional characters (as in Taiwan and Hong Kong)"
        Languages.ZH_HANS -> "Chinese written in Simplified characters (as in mainland China)"
        else -> Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifBlank { tag }
    }

    fun system(from: String?, to: String, count: Int, reply: Boolean): String {
        val target = name(to)
        val source = if (from == null || from == Languages.AUTO) "" else " from ${name(from)}"
        return if (reply) {
            """
            You translate the user's own chat message$source into $target, ready to be sent.
            Write it the way a native speaker would type it in a chat: natural, same tone, same emojis, no added politeness, no explanations.
            The "earlier_context" lines are the conversation so far – use them only to understand what is meant.
            Answer with JSON only, no markdown: {"translations": ["…"]} with exactly $count string(s) in the input order.
            """.trimIndent()
        } else {
            """
            You translate text that was read from a screen – chat messages, subtitles, menus, signs, websites – into $target$source.
            Rules:
            - The items appear on the same screen in reading order. Use the others (and "earlier_context") as context, but translate each item on its own.
            - Write natural, everyday $target the way a native speaker would say it. Keep the tone, slang, emojis and punctuation style.
            - Keep names, @usernames, URLs, numbers, prices, codes and emojis as they are.
            - The text was recognised by OCR and may contain small mistakes. Silently correct obvious ones.
            - If an item is already in $target, return it unchanged. Never add notes, quotes or explanations.
            Answer with JSON only, no markdown: {"translations": ["…"]} with exactly $count strings in the input order.
            """.trimIndent()
        }
    }

    fun user(texts: List<String>, context: List<String>): String {
        val o = JSONObject().put("items", JSONArray(texts))
        if (context.isNotEmpty()) o.put("earlier_context", JSONArray(context.takeLast(OnlineClient.MAX_CONTEXT)))
        return o.toString()
    }

    /** Reads {"translations": [...]} (or a bare list) out of the answer; null if it doesn't fit. */
    fun parse(content: String, count: Int): List<String>? {
        var t = content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val objStart = t.indexOf('{')
        val objEnd = t.lastIndexOf('}')
        if (objStart >= 0 && objEnd > objStart) {
            try {
                val o = JSONObject(t.substring(objStart, objEnd + 1))
                val arr = o.optJSONArray("translations") ?: o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull()
                if (arr != null && arr.length() == count) return strings(arr)
            } catch (_: Exception) {
            }
        }
        val arrStart = t.indexOf('[')
        val arrEnd = t.lastIndexOf(']')
        if (arrStart >= 0 && arrEnd > arrStart) {
            try {
                val arr = JSONArray(t.substring(arrStart, arrEnd + 1))
                if (arr.length() == count) return strings(arr)
            } catch (_: Exception) {
            }
        }
        // A single item may come back as plain text.
        if (count == 1 && t.isNotBlank() && !t.startsWith("{") && !t.startsWith("[")) return listOf(t)
        return null
    }

    private fun strings(arr: JSONArray): List<String> = (0 until arr.length()).map { i ->
        when (val v = arr.opt(i)) {
            is String -> v
            is JSONObject -> v.optString("translation", v.optString("text", v.toString()))
            null -> ""
            else -> v.toString()
        }.trim()
    }
}
