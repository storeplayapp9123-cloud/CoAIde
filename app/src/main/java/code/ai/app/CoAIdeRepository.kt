package code.ai.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CoAIdeRepository(
    private val workerUrl: String,
    private val clientToken: String
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun askCoAIde(
        history: List<Pair<String, String>>,
        userPrompt: String,
        imageBase64: String? = null,
        imageMime: String = "image/jpeg",
        attachedFileName: String? = null,
        attachedFileContent: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (clientToken.isBlank()) {
                return@withContext Result.failure(
                    Exception("APP_CLIENT_TOKEN missing hai.")
                )
            }

            val finalPrompt = if (!attachedFileContent.isNullOrBlank()) {
                val safeName = attachedFileName ?: "attached_file.txt"
                val clipped = attachedFileContent.take(12000)
                buildString {
                    append(userPrompt)
                    append("\n\n--- Attached File: ")
                    append(safeName)
                    append(" ---\n```\n")
                    append(clipped)
                    append("\n```")
                }
            } else {
                userPrompt
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
                .put("prompt", finalPrompt)

            if (!imageBase64.isNullOrBlank()) {
                payload.put("imageBase64", imageBase64)
                payload.put("imageMime", imageMime)
            }

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
