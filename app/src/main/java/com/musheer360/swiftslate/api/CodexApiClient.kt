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

class CodexApiClient {

    companion object {
        private val HTTP_CODE_REGEX = Regex("^HTTP_(\\d+):")
        private val HTTP_PREFIX_REGEX = Regex("^HTTP_\\d+:\\s*")
        private const val BASE_URL = "https://chatbot.codexapi.workers.dev"
        private const val MODELS_ENDPOINT = "$BASE_URL/models"
        const val RANDOM_MODEL_ID = "random"
    }

    suspend fun getAvailableModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                val jsonResponse = JSONObject(response)
                val dataArray = jsonResponse.optJSONArray("data")
                
                val models = mutableListOf<String>()
                // Add "Random" as first option
                models.add(RANDOM_MODEL_ID)
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val model = dataArray.getJSONObject(i)
                        val id = model.optString("id", "")
                        if (id.isNotEmpty()) {
                            models.add(id)
                        }
                    }
                }
                Result.success(models)
            } else {
                Result.failure(Exception("Failed to fetch models"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun validateKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(MODELS_ENDPOINT).openConnection() as HttpURLConnection
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
                Result.failure(Exception("Invalid or no API key required"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * If model is RANDOM_MODEL_ID, we don't pass a model param so the API
     * picks any available model on its own.
     */
    suspend fun generate(
        prompt: String,
        text: String,
        model: String,
        temperature: Double
    ): Result<GenerateResult> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        return try {
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val baseUrl = if (model == RANDOM_MODEL_ID || model.isBlank()) {
                // No model param → API uses any random available model
                "$BASE_URL/?prompt=$encodedText"
            } else {
                val safeModel = model.replace(Regex("[^a-zA-Z0-9._\\-/: ]"), "")
                "$BASE_URL/?prompt=$encodedText&model=$safeModel"
            }
            
            connection = URL(baseUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val response = ApiClientUtils.readResponseBounded(connection)
                var resultText = response.trim()
                
                resultText = ApiClientUtils.stripMarkdownFences(resultText)
                Result.success(GenerateResult(resultText, false))
            } else {
                val errorBody = ApiClientUtils.readErrorBody(connection)
                val detail = ApiClientUtils.sanitizeErrorForUser(responseCode, errorBody, "API Error")
                Result.failure(Exception("HTTP_${responseCode}: $detail"))
            }
        } catch (e: Exception) {
            val apiError = when (e) {
                is SocketTimeoutException, is UnknownHostException, is ConnectException, is java.net.SocketException -> 
                    ApiError.Network(e.message ?: "Network error")
                is org.json.JSONException -> ApiError.Other("Invalid response from server")
                else -> ApiError.Other(e.message ?: "Unknown error")
            }
            Result.failure(ApiException(apiError, e.message ?: "Unknown error"))
        } finally {
            connection?.disconnect()
        }
    }
}
