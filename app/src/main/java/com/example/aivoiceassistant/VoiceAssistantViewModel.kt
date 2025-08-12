package com.example.aivoiceassistant

import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Message(
    val text: String,
    val isUser: Boolean,
    val image: Bitmap? = null
)
@HiltViewModel
class VoiceAssistantViewModel @Inject constructor(
    private val geminiRepository: GeminiRepository,
    private val commandExecutor: CommandExecutor
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isListeningForWakeWord = MutableStateFlow(true)
    val isListeningForWakeWord: StateFlow<Boolean> = _isListeningForWakeWord

    fun setApiKey(key: String) {
        geminiRepository.setApiKey(key)
    }

    fun startListeningForWakeWord() { _isListeningForWakeWord.value = true }
    fun stopListeningForWakeWord() { _isListeningForWakeWord.value = false }

// In VoiceAssistantViewModel.kt

    fun processInput(input: String, tts: TextToSpeech) {
        viewModelScope.launch {
            stopListeningForWakeWord()
            _messages.value = _messages.value + Message(input, true)
            _isLoading.value = true

            // --- THIS IS THE NEW, SMARTER LOGIC ---
            // 1. Define keywords that trigger our special functions.
            val toolKeywords = listOf("open", "launch", "start", "call", "dial", "search", "find", "look up", "alarm", "timer", "wake me")

            // 2. Check if the user's input contains any of these keywords.
            val isToolRequest = toolKeywords.any { keyword -> input.lowercase().contains(keyword) }

            // 3. Call the API with or without tools based on the check.
            val response: GeminiResponse
            if (isToolRequest) {
                // If it's a tool request, enable function calling.
                response = geminiRepository.generateContent(_messages.value, enableTools = true)
            } else {
                // Otherwise, it's a general question. Disable tools to get a direct answer.
                response = geminiRepository.generateContent(_messages.value, enableTools = false)
            }

            handleGeminiResponse(response, tts)
        }
    }
    // Add this new function to your VoiceAssistantViewModel class

    fun processImageInput(prompt: String, image: Bitmap, tts: TextToSpeech) {
        viewModelScope.launch {
            stopListeningForWakeWord()
            // --- FIX IS HERE ---
            // Add the image to the Message object
            _messages.value = _messages.value + Message("$prompt (with image)", true, image)
            _isLoading.value = true
            val response = geminiRepository.generateContentWithImage(prompt, image)
            handleGeminiResponse(response, tts)
        }
    }

    private suspend fun handleGeminiResponse(response: GeminiResponse, tts: TextToSpeech) {
        when (response) {
            is GeminiResponse.TextResponse -> {
                addFinalResponse(response.text, tts)
            }
            is GeminiResponse.FunctionCall -> {
                val functionName = response.name
                val knownFunctions = listOf("setAlarm", "openApp", "callContact", "searchWeb")

                // Check if the function is one we have defined
                if (functionName in knownFunctions) {
                    // --- This is the logic for KNOWN functions ---
                    // 1. Add model's function call turn to history
                    val functionCallText = "FunctionCall:${response.name}:${response.args}"
                    _messages.value = _messages.value + Message(functionCallText, false)

                    // 2. Execute the function
                    val functionResult = executeFunction(functionName, response.args)

                    // 3. Add the tool's result turn to history
                    _messages.value = _messages.value + Message("Tool Result: $functionName: $functionResult", false)

                    // 4. Call API again for the final text response
                    val finalApiResponse = geminiRepository.generateContent(_messages.value, enableTools = true)
                    handleGeminiResponse(finalApiResponse, tts)
                } else {
                    // --- This is the NEW fallback logic for UNKNOWN functions ---
                    _isLoading.value = true

                    // Find the original user message that caused the confusion
                    val originalUserMessage = _messages.value.lastOrNull { it.isUser }

                    if (originalUserMessage != null) {
                        // Call the API again with ONLY the user's message and with tools DISABLED
                        val fallbackResponse = geminiRepository.generateContent(
                            history = listOf(originalUserMessage),
                            enableTools = false
                        )
                        // Process the new response, which will be simple text
                        handleGeminiResponse(fallbackResponse, tts)
                    } else {
                        // Fallback if no user message is found for some reason
                        addFinalResponse("I got confused. Could you please ask again?", tts)
                    }
                }
            }
            is GeminiResponse.ErrorResponse -> {
                addFinalResponse("Error: ${response.message}", tts)
            }
        }
    }

    private fun executeFunction(name: String, args: org.json.JSONObject): String {
        return when (name) {
            "setAlarm" -> commandExecutor.setAlarm(args)
            "openApp" -> commandExecutor.openApp(args)
            "callContact" -> commandExecutor.callContact(args)
            "searchWeb" -> commandExecutor.searchWeb(args)
            else -> "Unknown function: $name" // This will now rarely be seen by the user
        }
    }

    private fun addFinalResponse(text: String, tts: TextToSpeech) {
        _isLoading.value = false
        _messages.value = _messages.value + Message(text, false)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        startListeningForWakeWord()
    }
}