package com.example.nikkuisgoodboy.api

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class DeepSeekAiClient {

    // 🔥 API Key Hardcoded (NOT RECOMMENDED)
    private val apiKey: String = "sk-c30f9dbb20f2469e97a1bb9c278724c5"

    /**
     * Analyzes an image for NSFW content using DeepSeek AI API
     *
     * @param bitmap The image to analyze
     * @return ContentAnalysisResult with detection results
     */
    suspend fun analyzeImage(bitmap: Bitmap): ContentAnalysisResult = withContext(Dispatchers.IO) {
        try {
            // Convert bitmap to base64 string
            val base64Image = bitmapToBase64(bitmap)

            // Create JSON payload
            val jsonPayload = JSONObject().apply {
                put("model", "deepseek-vl")
                put("prompt", "Detect if this image contains adult content, nudity, or sexual content. Return a detailed analysis.")

                // Add image data
                val messagesArray = JSONObject().apply {
                    put("role", "user")
                    put("content", arrayOf(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", "Check if this image contains any adult, NSFW, nudity, or sexual content. Rate the image on a scale of 0-10 where 0 is completely safe and 10 is explicit content.")
                        },
                        JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        }
                    ))
                }
                put("messages", arrayOf(messagesArray))
            }

            // Make API request
            val url = URL(DEEPSEEK_API_ENDPOINT)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true

            // Write request body
            OutputStreamWriter(connection.outputStream).use {
                it.write(jsonPayload.toString())
                it.flush()
            }

            // Get response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext parseResponse(response)
            } else {
                Log.e(TAG, "API request failed with code: $responseCode")
                return@withContext ContentAnalysisResult(
                    isNsfw = false,
                    nsfwScore = 0.0f,
                    error = "API request failed with code: $responseCode"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            return@withContext ContentAnalysisResult(
                isNsfw = false,
                nsfwScore = 0.0f,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val resizedBitmap = resizeBitmapIfNeeded(bitmap, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE)
        val byteArrayOutputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun resizeBitmapIfNeeded(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth && height <= maxHeight) {
            return bitmap
        }

        val scaleFactor = when {
            width > height -> maxWidth.toFloat() / width
            else -> maxHeight.toFloat() / height
        }

        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun parseResponse(response: String): ContentAnalysisResult {
        try {
            val jsonResponse = JSONObject(response)
            val choices = jsonResponse.getJSONArray("choices")
            val content = choices.getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            // Extract NSFW score from response
            val isNsfw = content.contains("adult content") ||
                    content.contains("nudity") ||
                    content.contains("sexual") ||
                    content.contains("explicit")

            // Try to extract a numeric score if provided in the response
            val scoreRegex = Regex("([0-9]|10)(/| out of |/)[10]")
            val scoreMatch = scoreRegex.find(content)
            val nsfwScore = if (scoreMatch != null) {
                val scoreText = scoreMatch.value.replace(Regex("[^0-9]"), "")
                scoreText.toFloat() / 10.0f  // Normalize to 0-1 range
            } else if (isNsfw) {
                0.8f  // Default high score if NSFW detected but no specific score
            } else {
                0.1f  // Default low score if not NSFW
            }

            return ContentAnalysisResult(
                isNsfw = isNsfw,
                nsfwScore = nsfwScore,
                details = content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response", e)
            return ContentAnalysisResult(
                isNsfw = false,
                nsfwScore = 0.0f,
                error = "Error parsing API response: ${e.message}"
            )
        }
    }

    companion object {
        private const val TAG = "DeepSeekAiClient"
        private const val DEEPSEEK_API_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
        private const val MAX_IMAGE_SIZE = 1024  // Maximum dimension for images
    }
}

/**
 * Represents the result of content analysis
 */
data class ContentAnalysisResult(
    val isNsfw: Boolean,
    val nsfwScore: Float, // 0.0 to 1.0, where 1.0 is explicit content
    val details: String? = null,
    val error: String? = null
)

