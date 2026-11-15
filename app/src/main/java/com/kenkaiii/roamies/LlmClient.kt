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
        val screenElements = RoamieHandsAccessibilityService.instance?.dumpActiveScreenElements().orEmpty()
        val screenDigest = screenElements.take(30).joinToString("\n") {
            "[Element] '${it.text.ifEmpty { it.contentDesc }}' pkg=${it.packageName} bounds=${it.bounds.flattenToString()} clickable=${it.clickable}"
        }
        val memoriesDigest = memoryStore.getMemories().takeLast(10).joinToString("; ") { it.fact }

        val systemPrompt = """
            You are ${auth.character.displayName} from Roamies, an on-screen AI sidekick living on the user's Android phone.
            Spoken responses should be punchy, friendly, natural, and concise.
            Active memories: $memoriesDigest
            Visible screen elements right now:
            $screenDigest

            Respond ONLY with a JSON object strictly matching this schema:
            {
              "spoken_reply": "Short answer spoken out loud by the character",
              "tool_calls": [
                {
                  "tool": "ring_button | show_how_walkthrough | click_element | type_in_app | open_app | draw_ink_card | save_memory | schedule_reminder | clear_ink",
                  "target_label": "Button text or element description to target",
                  "text": "Text to type or reminder fact or memory fact",
                  "delay_seconds": 60,
                  "card_title": "Card title if drawing card",
                  "card_bullets": ["Point 1", "Point 2"],
                  "diagram_type": "CARD | GRAPH | TIMELINE"
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
                val spoken = obj.optString("spoken_reply", "At your service.")
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
                            diagramType = call.optString("diagram_type", "CARD")
                        )
                    )
                }
                LlmTurnResult(spokenReply = spoken, toolCalls = toolList)
            } else {
                LlmTurnResult(spokenReply = raw.take(240), toolCalls = emptyList())
            }
        } catch (e: Exception) {
            LlmTurnResult(spokenReply = raw.take(240), toolCalls = emptyList())
        }
    }

    private fun callGemini(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey.trim()
        if (apiKey.isBlank()) {
            return "Please tap the settings gear at the top right to paste your API key!"
        }
        val model = if (auth.modelId.isBlank() || auth.modelId.contains("2.5")) "gemini-1.5-flash" else auth.modelId.trim()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val bodyJson = JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val resBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) return "Gemini API error ${response.code}. Please verify your API key in Settings."

        val root = JSONObject(resBody)
        val candidates = root.optJSONArray("candidates") ?: return ""
        val first = candidates.optJSONObject(0) ?: return ""
        val content = first.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        return parts.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun callAnthropic(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey
        val model = auth.modelId.ifEmpty { "claude-3-5-sonnet-20241022" }
        val url = "https://api.anthropic.com/v1/messages"

        val bodyJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            }))
        }

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val resBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) return "Error from Claude: ${response.code} $resBody"

        val root = JSONObject(resBody)
        val content = root.optJSONArray("content") ?: return ""
        return content.optJSONObject(0)?.optString("text").orEmpty()
    }

    private fun callOpenAiCompatible(systemPrompt: String, userPrompt: String): String {
        val apiKey = auth.apiKey
        val model = auth.modelId.ifEmpty { "gpt-4o-mini" }
        val baseUrl = if (auth.provider.equals("ollama", true)) "http://localhost:11434/v1/chat/completions" else "https://api.openai.com/v1/chat/completions"

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
            .url(baseUrl)
            .header("Authorization", "Bearer $apiKey")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = http.newCall(request).execute()
        val resBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) return "Error: ${response.code} $resBody"

        val root = JSONObject(resBody)
        val choices = root.optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: return ""
        return message.optString("content").orEmpty()
    }
}
