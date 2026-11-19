package code.ai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CoAIdeRepository(private val apiKey: String) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val systemPrompt = """
        Tum 'CoAIde' ho — ek strict aur expert Software & Android Code Assistant.
        Niyam:
        1. Sirf programming, code likhne, bug fix karne, code review, system design, aur developer tools se jude sawaalon ka jawab do.
        2. Agar user coding/programming ke alawa koi sawaal pooche (jaise movies, jokes, general GK, personal advice), toh seedha aur polite tareeke se mana karo aur kaho ki tum 'CoAIde' ho aur sirf coding mein madad kar sakte ho.
        3. Code hamesha clean, well-formatted, aur practical explanation ke saath do.
    """.trimIndent()

    suspend fun askCoAIde(
        history: List<Pair<String, String>>,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API Key missing hai. GitHub Secret GEMINI_API_KEY check karein."))
            }

            val contentsArray = JSONArray()
            for ((role, text) in history) {
                contentsArray.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }

            contentsArray.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            )

            val payload = JSONObject()
                .put(
                    "system_instruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                )
                .put("contents", contentsArray)
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", 0.2)
                )

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("API Error (${response.code}): $bodyStr"))
                }

                val root = JSONObject(bodyStr)
                val replyText = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()

                if (replyText.isBlank()) {
                    Result.failure(Exception("Koi code response nahi aaya."))
                } else {
                    Result.success(replyText.trim())
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
