package com.example.aivoiceassistant

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed class GeminiResponse {
    data class TextResponse(val text: String) : GeminiResponse()
    data class FunctionCall(val name: String, val args: JSONObject) : GeminiResponse()
    data class ErrorResponse(val message: String) : GeminiResponse()
}

@Singleton
class GeminiRepository @Inject constructor() {

    private val client = OkHttpClient()
    private var geminiApiKey: String = ""

    fun setApiKey(key: String) {
        geminiApiKey = key
    }

    private fun getTools(): JSONArray {
        // This function defines the tools for Gemini. It is unchanged.
        return JSONArray(listOf(
            JSONObject().put("functionDeclarations", JSONArray(listOf(
                JSONObject().apply {
                    put("name", "setAlarm")
                    put("description", "Sets an alarm.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().put("command", JSONObject().put("type", "STRING")))
                        put("required", JSONArray(listOf("command")))
                    })
                },
                JSONObject().apply {
                    put("name", "openApp")
                    put("description", "Opens an application.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().put("appName", JSONObject().put("type", "STRING")))
                        put("required", JSONArray(listOf("appName")))
                    })
                },
                JSONObject().apply {
                    put("name", "callContact")
                    put("description", "Initiates a phone call.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().put("contactName", JSONObject().put("type", "STRING")))
                        put("required", JSONArray(listOf("contactName")))
                    })
                },
                JSONObject().apply {
                    put("name", "searchWeb")
                    put("description", "Performs a web search.")
                    put("parameters", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().put("query", JSONObject().put("type", "STRING")))
                        put("required", JSONArray(listOf("query")))
                    })
                }
            )))
        ))
    }

// Replace the old generateContent function with this one
// Replace the old generateContent function with this one

    suspend fun generateContent(history: List<Message>, enableTools: Boolean = true): GeminiResponse = withContext(Dispatchers.IO) {
        if (geminiApiKey.isBlank()) return@withContext GeminiResponse.ErrorResponse("API key not set.")

        val contentsArray = JSONArray()
        history.forEach { message ->
            val role = if (message.isUser) "user" else "model"

            when {
                message.text.startsWith("FunctionCall:") -> {
                    val parts = message.text.split(":", limit = 3)
                    val functionName = parts[1]
                    val functionArgs = JSONObject(parts[2])
                    contentsArray.put(JSONObject().apply {
                        put("role", "model")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("functionCall", JSONObject().apply {
                                put("name", functionName)
                                put("args", functionArgs)
                            })
                        }))
                    })
                }
                message.text.startsWith("Tool Result:") -> {
                    contentsArray.put(JSONObject().apply {
                        put("role", "tool")
                        put("parts", JSONArray().put(JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", message.text.substringAfter("Tool Result:").substringBefore(":").trim())
                                put("response", JSONObject().put("result", message.text.substringAfterLast(": ")))
                            })
                        }))
                    })
                }
                else -> {
                    contentsArray.put(JSONObject()
                        .put("role", role)
                        .put("parts", JSONArray().put(JSONObject().put("text", message.text))))
                }
            }
        }

        val requestBodyJson = JSONObject().apply {
            put("contents", contentsArray)
            // This is the new logic: only add tools if they are enabled for this call.
            if (enableTools) {
                put("tools", getTools())
            }
        }.toString()

        Log.d("GeminiRequest", "Request Body: $requestBodyJson")
        return@withContext executeGeminiRequest(requestBodyJson)
    }
// Add this new function to your GeminiRepository class

// Replace the old generateContentWithImage function with this one

    suspend fun generateContentWithImage(prompt: String, image: Bitmap): GeminiResponse = withContext(Dispatchers.IO) {
        if (geminiApiKey.isBlank()) return@withContext GeminiResponse.ErrorResponse("API key not set.")

        val byteArrayOutputStream = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
        // --- FIX IS HERE ---
        // Use Base64.NO_WRAP to prevent the encoder from adding newline characters.
        val base64Image = Base64.encodeToString(byteArrayOutputStream.toByteArray(), Base64.NO_WRAP)

        val requestBodyJson = JSONObject().apply {
            val contentsArray = JSONArray().apply {
                val userContent = JSONObject().apply {
                    put("role", "user")
                    val partsArray = JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                        put(JSONObject().put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64Image)
                        }))
                    }
                    put("parts", partsArray)
                }
                put(userContent)
            }
            put("contents", contentsArray)
        }.toString()

        Log.d("GeminiRequest", "Image Request Body: $requestBodyJson")
        // Use the existing executeGeminiRequest function to send the request
        return@withContext executeGeminiRequest(requestBodyJson)
    }
    private fun executeGeminiRequest(requestBodyJson: String): GeminiResponse {
        val body = requestBodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$geminiApiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error details"
                    Log.e("GeminiRequest", "API Error Response: $errorBody")
                    return GeminiResponse.ErrorResponse("API Error ${response.code}: $errorBody")
                }

                val responseBody = response.body?.string() ?: ""
                Log.d("GeminiRequest", "API Success Response: $responseBody")
                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    return GeminiResponse.ErrorResponse("Error: No candidates received from API.")
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")

                val functionCallPart = parts.optJSONObject(0)?.optJSONObject("functionCall")
                if (functionCallPart != null) {
                    val functionName = functionCallPart.getString("name")
                    val functionArgs = functionCallPart.getJSONObject("args")
                    return GeminiResponse.FunctionCall(functionName, functionArgs)
                }

                val text = parts.getJSONObject(0).getString("text").trim()
                return GeminiResponse.TextResponse(text)
            }
        } catch (e: IOException) {
            return GeminiResponse.ErrorResponse("Network error: ${e.localizedMessage}")
        } catch (e: Exception) {
            return GeminiResponse.ErrorResponse("Unexpected error: ${e.localizedMessage}")
        }
    }
}