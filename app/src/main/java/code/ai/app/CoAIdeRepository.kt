package code.ai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class CoAIdeRepository(
    private val workerUrl: String,
    private val clientToken: String
) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun askCoAIde(
        history: List<Pair<String, String>>,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (clientToken.isBlank()) {
                return@withContext Result.failure(
                    Exception("APP_CLIENT_TOKEN missing hai.")
                )
            }

            val historyArray = JSONArray()
            for ((role, text) in history) {
                val apiRole = if (role == "model") "assistant" else role
                historyArray.put(
                    JSONObject()
                        .put("role", apiRole)
                        .put("content", text)
                )
            }

            val payload = JSONObject()
                .put("history", historyArray)
                .put("prompt", userPrompt)

            val request = Request.Builder()
                .url(workerUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-CoAIde-Token", clientToken)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(bodyStr).optString("error").ifBlank { bodyStr }
                    } catch (_: Exception) {
                        bodyStr
                    }
                    return@withContext Result.failure(
                        Exception("Server Error (${response.code}): $errMsg")
                    )
                }

                val root = JSONObject(bodyStr)
                val replyText = root.optString("reply").trim()

                if (replyText.isBlank()) {
                    Result.failure(Exception("Koi code response nahi aaya."))
                } else {
                    Result.success(replyText)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
