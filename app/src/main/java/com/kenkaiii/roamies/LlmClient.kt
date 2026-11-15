package com.kenkaiii.roamies

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmToolCall(
    val tool: String,
    val targetLabel: String?,
    val text: String?,
    val delaySeconds: Long,
    val cardTitle: String?,
    val cardBullets: List<String>,
    val diagramType: String?
)

data class LlmTurnResult(
    val spokenReply: String,
    val toolCalls: List<LlmToolCall>
)

class LlmClient(
    private val context: Context,
    private val auth: AuthStorage,
    private val memoryStore: SidekickMemoryStore
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun executeTurn(userPrompt: String): LlmTurnResult = withContext(Dispatchers.IO) {
        val memoriesDigest = memoryStore.getMemories().takeLast(10).joinToString("; ") { it.fact }

        val systemPrompt = """
            You are J.A.R.V.I.S. (Just A Rather Very Intelligent System) & Roamies AI on the user's Android phone.
            Inspired by Tony Stark / Iron Man's sophisticated AI companion.
            You are articulate, witty, unflappable, deeply loyal, concise, and hyper-capable.
            Address the user as 'sir' or their preferred name naturally.
            Keep spoken replies punchy, natural, and direct (1-3 sentences max).

            Productivity & Pomodoro Focus Engine:
            - When the user asks to focus, study, work, or start a Pomodoro (e.g. "start 25 min pomodoro", "I need to focus", "set focus timer"):
              Generate a tool call:
              { "tool": "start_pomodoro", "text": "Focus Session", "delay_seconds": 1500, "card_title": "FOCUS SPRINT // 25:00", "card_bullets": ["Arc Reactor tuned to Focus state", "All distractions filtered"] }
            - When the user asks to pause or stop pomodoro:
              Generate tool "pause_pomodoro" or "reset_pomodoro".
            - When user asks for a break:
              Generate { "tool": "start_pomodoro", "text": "Short Break", "delay_seconds": 300 }

            Memories: $memoriesDigest

            Respond ONLY with a JSON object strictly matching this schema:
            {
              "spoken_reply": "Short answer spoken out loud by J.A.R.V.I.S.",
              "tool_calls": [
                {
                  "tool": "start_pomodoro | pause_pomodoro | reset_pomodoro | draw_ink_card | save_memory",
                  "target_label": "Optional label",
                  "text": "Session title or memory note",
                  "delay_seconds": 1500,
                  "card_title": "Title if card",
                  "card_bullets": ["Point 1", "Point 2"],
                  "diagram_type": "CARD"
                }
              ]
            }
        """.trimIndent()

        val rawResponse = when (auth.provider.lowercase()) {
            "gemini" -> callGemini(systemPrompt, userPrompt)
            "anthropic" -> callAnthropic(systemPrompt, userPrompt)
            else -> callOpenAiCompatible(systemPrompt, userPrompt)
        }

        parseTurnResponse(rawResponse)
    }

    private fun parseTurnResponse(raw: String): LlmTurnResult {
        return try {
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                val clean = raw.substring(jsonStart, jsonEnd + 1)
                val obj = JSONObject(clean)
                val spoken = obj.optString("spoken_reply", "At your service, sir.")
                val toolsJson = obj.optJSONArray("tool_calls") ?: JSONArray()
                val toolList = mutableListOf<LlmToolCall>()

                for (i in 0 until toolsJson.length()) {
                    val call = toolsJson.getJSONObject(i)
                    val bullets = mutableListOf<String>()
                    val bArr = call.optJSONArray("card_bullets")
                    if (bArr != null) {
                        for (j in 0 until bArr.length()) bullets.add(bArr.getString(j))
                    }
                    toolList.add(
                        LlmToolCall(
                            tool = call.optString("tool", ""),
                            targetLabel = call.optString("target_label").ifEmpty { null },
                            text = call.optString("text").ifEmpty { null },
                            delaySeconds = call.optLong("delay_seconds", 0L),
                            cardTitle = call.optString("card_title").ifEmpty { null },
                            cardBullets = bullets,
                            diagramType = call.optString("diagram_type").ifEmpty { null }
                        )
                    )
                }
                LlmTurnResult(spokenReply = spoken, toolCalls = toolList)
            } else {
                LlmTurnResult(spokenReply = raw.trim(), toolCalls = emptyList())
            }
        } catch (e: Exception) {
            LlmTurnResult(
                spokenReply = "Diagnostics nominal. At your service, sir.",
                toolCalls = emptyList()
            )
        }
    }

    private fun callGemini(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey.trim()
        if (apiKey.isBlank()) {
            return """{"spoken_reply": "Please paste your Gemini API key in Settings (gear icon at top right), sir.", "tool_calls": []}"""
        }

        // Sanitize model name - remove prefixes and invalid versions like 2.5
        var model = auth.model.trim().removePrefix("models/").ifBlank { "gemini-1.5-flash" }
        if (model.contains("2.5") || model == "gemini-flash") {
            model = "gemini-1.5-flash"
            auth.model = model
        }

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "$systemPrompt\n\nUser request: $userPrompt"))
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.7)
                put("responseMimeType", "application/json")
            })
        }

        val requestBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val root = JSONObject(body)
                    val candidates = root.optJSONArray("candidates") ?: return "{}"
                    val first = candidates.optJSONObject(0) ?: return "{}"
                    val content = first.optJSONObject("content") ?: return "{}"
                    val parts = content.optJSONArray("parts") ?: return "{}"
                    return parts.optJSONObject(0)?.optString("text").orEmpty()
                }

                // If 404 (Model Not Found), auto-fallback to gemini-1.5-flash
                if (resp.code == 404 && model != "gemini-1.5-flash") {
                    auth.model = "gemini-1.5-flash"
                    val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
                    val fallbackReq = Request.Builder().url(fallbackUrl).post(requestBody).build()
                    http.newCall(fallbackReq).execute().use { fbResp ->
                        val fbBody = fbResp.body?.string().orEmpty()
                        if (fbResp.isSuccessful) {
                            val root = JSONObject(fbBody)
                            val candidates = root.optJSONArray("candidates") ?: return "{}"
                            val first = candidates.optJSONObject(0) ?: return "{}"
                            val content = first.optJSONObject("content") ?: return "{}"
                            val parts = content.optJSONArray("parts") ?: return "{}"
                            return parts.optJSONObject(0)?.optString("text").orEmpty()
                        }
                    }
                }

                // Extract specific error message from Google's response
                val errorMessage = try {
                    val err = JSONObject(body).optJSONObject("error")
                    err?.optString("message") ?: "HTTP ${resp.code}"
                } catch (e: Exception) {
                    "HTTP ${resp.code}"
                }

                return """{"spoken_reply": "Gemini error (${resp.code}): $errorMessage", "tool_calls": []}"""
            }
        } catch (e: Exception) {
            return """{"spoken_reply": "Network connection error: ${e.localizedMessage ?: "Unable to reach Gemini"}", "tool_calls": []}"""
        }
    }

    private fun callOpenAiCompatible(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey.trim()
        val url = if (auth.endpoint.isNotBlank()) auth.endpoint else "https://api.openai.com/v1/chat/completions"
        val model = auth.model.ifBlank { "gpt-4o-mini" }

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return """{"spoken_reply": "Service error: ${resp.code}", "tool_calls": []}"""
            }
            val root = JSONObject(body)
            val choices = root.optJSONArray("choices") ?: return "{}"
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return "{}"
            return msg.optString("content").orEmpty()
        }
    }

    private fun callAnthropic(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey.trim()
        val url = "https://api.anthropic.com/v1/messages"
        val model = auth.model.ifBlank { "claude-3-5-sonnet-20241022" }

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                return """{"spoken_reply": "Anthropic error: ${resp.code}", "tool_calls": []}"""
            }
            val root = JSONObject(body)
            val contents = root.optJSONArray("content") ?: return "{}"
            return contents.optJSONObject(0)?.optString("text").orEmpty()
        }
    }
}
