package app.sublens

import app.sublens.OnlineClient.Failure
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Online translation without a phone and without a key: prompt, parsing of answers, model choice,
 * key recognition, and the client against a local mock of an OpenAI-compatible service.
 */
class OnlineClientTest {

    // ---------------------------------------------------------------- Parsing and prompt

    @Test
    fun parsesTheUsualAnswerShapes() {
        assertEquals(listOf("a", "b"), Prompt.parse("""{"translations":["a","b"]}""", 2))
        assertEquals(listOf("a"), Prompt.parse("<think>hm {x}</think>\n```json\n{\"translations\":[\"a\"]}\n```", 1))
        assertEquals(listOf("x", "y"), Prompt.parse("""Sure: {"result":["x","y"]} done""", 2))
        assertEquals(listOf("x", "y", "z"), Prompt.parse("""["x","y","z"]""", 3))
        assertEquals(listOf("Hallo Welt"), Prompt.parse("Hallo Welt", 1))
        assertEquals(listOf("a", "b"), Prompt.parse("""{"translations":[{"translation":"a"},{"text":"b"}]}""", 2))
        assertEquals(listOf("a {b}", "c"), Prompt.parse("""{"translations":["a {b}","c"]}""", 2))
        assertNull(Prompt.parse("""{"translations":["a"]}""", 2))
    }

    @Test
    fun promptNamesLanguagesClearly() {
        val sys = Prompt.system(null, "zh", 3, reply = false)
        assertTrue(sys.contains("into Chinese written in Traditional characters (as in Taiwan and Hong Kong).\nRules"))
        assertTrue(sys.contains("exactly 3 strings"))
        assertTrue(Prompt.system("zh-Hans", "de", 1, false).contains("from Chinese written in Simplified"))
        assertTrue(Prompt.system("en", "de", 1, true).contains("chat message from English into German"))
        val user = JSONObject(Prompt.user(listOf("a", "b"), (1..20).map { "c$it" }))
        assertEquals(12, user.getJSONArray("earlier_context").length())
        assertFalse(JSONObject(Prompt.user(listOf("a"), emptyList())).has("earlier_context"))
    }

    // ---------------------------------------------------------------- Providers

    @Test
    fun picksCurrentModels() {
        val zai = Providers.byId("zai")!!
        assertEquals("glm-4.7-flash", Providers.pickModel(zai, listOf("glm-4.5-flash", "glm-4.7-flash", "glm-4-plus")))
        val gemini = Providers.byId("gemini")!!
        assertEquals(
            "gemini-3.1-flash-lite",
            Providers.pickModel(gemini, listOf("models/gemini-2.5-flash", "models/gemini-3.1-flash-lite", "models/gemini-3.5-flash-lite-preview")),
        )
        assertEquals("qwen/qwen3.8-27b", Providers.pickModel(Providers.byId("groq")!!, listOf("llama-3.3-70b-versatile", "qwen/qwen3.8-27b", "whisper-large-v3")))
        assertEquals("deepseek-chat", Providers.pickModel(Providers.byId("deepseek")!!, listOf("deepseek-reasoner", "deepseek-chat")))
        assertEquals(
            listOf("glm-4.7-flash", "glm-4.5-flash", "glm-4-plus"),
            Providers.usableModels(zai, listOf("glm-4-plus", "embedding-3", "glm-4.5-flash", "glm-4.7-flash")),
        )
    }

    @Test
    fun recognisesPastedKeys() {
        fun ids(k: String) = Providers.guess(k).map { it.id }
        assertEquals(listOf("zai"), ids("0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMn"))
        assertEquals(listOf("deepseek", "qwen"), ids("sk-0123456789abcdef0123456789abcdef"))
        assertEquals(listOf("gemini"), ids("AIzaSyA1234567890abcdefghijklmnopqrstu"))
        assertEquals(listOf("groq"), ids("gsk_abcdefghijklmnopqrstuvwxyz0123"))
        assertEquals(listOf("openrouter"), ids("sk-or-v1-0123456789abcdef0123456789abcdef"))
        assertTrue(ids("hello").isEmpty())
    }

    // ---------------------------------------------------------------- Client against a mock service

    private val zai = Providers.byId("zai")!!
    private val custom = Providers.byId(Providers.CUSTOM)!!

    private fun failure(block: () -> Unit): Failure? = try {
        block()
        null
    } catch (f: OnlineClient.Failed) {
        f.kind
    }

    @Test
    fun oneRequestPerScreen() {
        log.clear()
        val texts = listOf("你好", "今天吃什么？", "哈哈😂", "@lisa 好的")
        val out = OnlineClient.translate(zai, url, "k", "glm-4.7-flash", texts, null, "de", listOf("earlier"))
        assertEquals(texts.map { "T($it)" }, out)
        assertEquals(1, log.size)
        val body = JSONObject(log[0].second)
        assertEquals("Bearer k", log[0].first)
        assertEquals("disabled", body.getJSONObject("thinking").optString("type"))
        assertTrue(body.has("max_tokens") && body.has("temperature"))
    }

    @Test
    fun readsUntidyAnswers() {
        assertEquals(listOf("T(a)", "T(b)"), OnlineClient.translate(custom, url, "", "fenced", listOf("a", "b"), "en", "de"))
        assertEquals(listOf("T(a)"), OnlineClient.translate(custom, url, "", "plain", listOf("a"), "en", "de"))
    }

    @Test
    fun mergedLinesAreAskedAgainInHalves() {
        log.clear()
        val four = listOf("1", "2", "3", "4")
        assertEquals(four.map { "T($it)" }, OnlineClient.translate(custom, url, "", "merge", four, "en", "de"))
        assertEquals(3, log.size)
    }

    @Test
    fun unknownSettingsAreLeftOut() {
        log.clear()
        assertEquals(listOf("T(a)"), OnlineClient.translate(zai, url, "k", "strict", listOf("a"), "en", "de"))
        OnlineClient.translate(zai, url, "k", "strict", listOf("b"), "en", "de")
        assertEquals(3, log.size)
        assertFalse(JSONObject(log[2].second).has("thinking"))
    }

    @Test
    fun errorsAreClassified() {
        assertEquals(Failure.LIMIT, failure { OnlineClient.translate(custom, url, "", "limit", listOf("a"), "en", "de") })
        assertEquals(Failure.MODEL, failure { OnlineClient.translate(custom, url, "", "gone", listOf("a"), "en", "de") })
        assertEquals(Failure.AUTH, failure { OnlineClient.translate(custom, url, "bad", "echo", listOf("a"), "en", "de") })
        assertEquals(Failure.AUTH, failure { OnlineClient.listModels(url, "bad") })
        assertEquals(Failure.NETWORK, failure { OnlineClient.translate(custom, "http://127.0.0.1:9/v1", "", "echo", listOf("a"), "en", "de") })
        assertEquals("Rate limit reached", OnlineClient.shortError("""429 {"error":{"message":"Rate limit reached"}}"""))
        assertTrue(OnlineClient.listModels(url, "k").contains("glm-4.7-flash"))
    }

    @Test
    fun longScreensGoOutInParts() {
        log.clear()
        val many = (1..95).map { "line $it" }
        assertEquals(many.map { "T($it)" }, OnlineClient.translate(custom, url, "", "echo", many, "en", "de"))
        assertEquals(3, log.size)
        val third = JSONObject(JSONObject(log[2].second).getJSONArray("messages").getJSONObject(1).optString("content"))
        assertEquals(12, third.getJSONArray("earlier_context").length())
    }

    companion object {
        /** (Authorization header, request body) of every chat request. */
        val log: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())

        val url: String by lazy {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization") ?: ""
                val raw = ex.requestBody.bufferedReader().readText()
                fun send(code: Int, body: String) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
                    ex.sendResponseHeaders(code, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                if (ex.requestMethod == "GET" && ex.requestURI.path.endsWith("/models")) {
                    if (auth == "Bearer bad") return@createContext send(401, """{"error":{"message":"Invalid API key"}}""")
                    val ids = listOf("glm-4.5-flash", "glm-4.7-flash", "glm-4-plus", "embedding-3")
                    return@createContext send(200, JSONObject().put("data", JSONArray(ids.map { JSONObject().put("id", it) })).toString())
                }
                log.add(auth to raw)
                if (auth == "Bearer bad") return@createContext send(401, """{"error":{"message":"Invalid API key"}}""")
                val body = JSONObject(raw)
                val items = JSONObject(body.getJSONArray("messages").getJSONObject(1).getString("content")).getJSONArray("items")
                val out = (0 until items.length()).map { "T(${items.getString(it)})" }
                var content = JSONObject().put("translations", JSONArray(out)).toString()
                when (body.getString("model")) {
                    "limit" -> return@createContext send(429, """{"error":{"message":"Rate limit reached"}}""")
                    "gone" -> return@createContext send(404, """{"error":{"message":"The model `gone` does not exist"}}""")
                    "strict" -> if (body.has("temperature") || body.has("thinking")) {
                        return@createContext send(400, """{"error":{"message":"Unrecognized request argument supplied: thinking"}}""")
                    }
                    "merge" -> if (out.size >= 3) content = JSONObject().put("translations", JSONArray(out.dropLast(1))).toString()
                    "fenced" -> content = "<think>hmm {}</think>\n```json\n$content\n```"
                    "plain" -> if (out.size == 1) content = out[0]
                }
                val message = JSONObject().put("role", "assistant").put("content", content)
                send(200, JSONObject().put("choices", JSONArray().put(JSONObject().put("index", 0).put("message", message))).toString())
            }
            server.start()
            "http://127.0.0.1:${server.address.port}/v1"
        }
    }
}
