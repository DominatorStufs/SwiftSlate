package com.musheer360.swiftslate.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class CopilotApiClient {

    companion object {
        private val HTTP_CODE_REGEX = Regex("^HTTP_(\\d+):")
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
        private const val BASE_URL = "https://copilot-api-delta.vercel.app"
    }

    suspend fun validateKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL("$BASE_URL/v1/models").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                connection.inputStream?.use { stream ->
                    val buf = ByteArray(1024)
                    while (stream.read(buf) != -1) { /* drain */ }
                }
                Result.success("Valid")
            } else {
                Result.failure(Exception("Service unavailable"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun generate(
        prompt: String,
        text: String,
        model: String,
        temperature: Double
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL("$BASE_URL/v1/chat/completions").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val systemContent = ApiClientUtils.SYSTEM_PROMPT_PREFIX + prompt

            val jsonBody = JSONObject().apply {
                put("model", "copilot")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemContent)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", text)
                    })
                })
                put("temperature", temperature)
                put("stream", false)
            }

            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)

                val jsonResponse = JSONObject(response)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val message = choice.optJSONObject("message")
                    var resultText = message?.optString("content", "") ?: ""
                    
                    if (resultText.isBlank()) {
                        return@withContext Result.failure(Exception("Model returned empty response"))
                    }

                    resultText = ApiClientUtils.stripMarkdownFences(resultText)
                    Result.success(GenerateResult(resultText, false))
                } else {
                    Result.failure(Exception("No choices found in response"))
                }
            } else if (responseCode == 429) {
                Result.failure(ApiException(ApiError.RateLimit("Rate limit exceeded", null), "Rate limit exceeded"))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "API Error")
                Result.failure(ApiException(ApiError.Other(detail), detail))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is ApiException -> e.apiError
                is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException -> 
                    ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            if (e is ApiException) Result.failure(e) else Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }
}
