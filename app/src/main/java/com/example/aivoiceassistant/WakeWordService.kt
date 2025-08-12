package com.example.aivoiceassistant

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import ai.picovoice.porcupine.Porcupine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*
import javax.inject.Inject

// CHANGELOG:
// 1. Implemented the function calling loop within the `onResults` callback of the RecognitionListener.
// 2. The service now directly orchestrates calls between GeminiRepository and CommandExecutor.
// 3. Added a conversation history (`serviceMessages`) to maintain context for the overlay session.

@AndroidEntryPoint
class WakeWordService : Service(), SavedStateRegistryOwner {

    @Inject lateinit var geminiRepository: GeminiRepository
    @Inject lateinit var commandExecutor: CommandExecutor

    private lateinit var textToSpeech: TextToSpeech
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null
    private lateinit var speechRecognizer: SpeechRecognizer
    private var wakeWordManager: WakeWordManager? = null

    // State for the overlay UI
    private var overlayStatusText = mutableStateOf("Listening for wake word...")
    private var overlayAiResponse = mutableStateOf<String?>(null)
    private var isMicListening = mutableStateOf(false)
    private var serviceMessages = mutableStateListOf<Message>()

    // Service lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val lifecycleOwner = ServiceLifecycleOwner()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        setupTextToSpeech()
        geminiRepository.setApiKey(BuildConfig.GEMINI_API_KEY)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, createNotification())
        wakeWordManager = WakeWordManager(
            context = this,
            accessKey = BuildConfig.PICOVOICE_ACCESS_KEY,
            builtInKeyword = Porcupine.BuiltInKeyword.HEY_SIRI,
            sensitivity = 0.9f,
            onWakeWordDetected = {
                serviceScope.launch {
                    wakeWordManager?.stop()
                    showOverlay()
                }
            }
        )
        wakeWordManager?.start()
        setupSpeechRecognizer()
    }

// Inside WakeWordService.kt

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                isMicListening.value = false
                val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

                if (command != null) {
                    overlayStatusText.value = "Processing..."
                    serviceMessages.add(Message(command, true))

                    serviceScope.launch {
                        // --- THIS IS THE NEW, SMARTER LOGIC (Copied from ViewModel) ---
                        // 1. Define keywords that trigger our special functions.
                        val toolKeywords = listOf("open", "launch", "start", "call", "dial", "search", "find", "look up", "alarm", "timer", "wake me")

                        // 2. Check if the user's input contains any of these keywords.
                        val isToolRequest = toolKeywords.any { keyword -> command.lowercase().contains(keyword) }

                        // 3. Call the API with or without tools based on the check.
                        val response: GeminiResponse
                        if (isToolRequest) {
                            // If it's a tool request, enable function calling.
                            response = geminiRepository.generateContent(serviceMessages.toList(), enableTools = true)
                        } else {
                            // Otherwise, it's a general question. Disable tools to get a direct answer.
                            response = geminiRepository.generateContent(serviceMessages.toList(), enableTools = false)
                        }

                        handleGeminiResponse(response)
                    }
                } else {
                    hideOverlay()
                }
            }

            // ... other override functions like onReadyForSpeech, onError, etc. remain the same ...
            override fun onReadyForSpeech(params: Bundle?) {
                overlayStatusText.value = "Listening..."
                isMicListening.value = true
            }
            override fun onError(error: Int) {
                isMicListening.value = false
                hideOverlay()
            }
            override fun onEndOfSpeech() { isMicListening.value = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }
// Replace the old handleGeminiResponse function with this one

    private suspend fun handleGeminiResponse(response: GeminiResponse) {
        when (response) {
            is GeminiResponse.TextResponse -> {
                setFinalResponse(response.text)
            }
            is GeminiResponse.FunctionCall -> {
                // Step 1: Add the model's function call to the conversation history.
                // We create a special text format to store it.
                val functionCallText = "FunctionCall:${response.name}:${response.args}"
                serviceMessages.add(Message(functionCallText, isUser = false)) // Role is 'model'

                // Step 2: Execute the function locally.
                val functionResult = executeFunction(response.name, response.args)

                // Step 3: Add the result of the function execution to the history.
                serviceMessages.add(Message("Tool Result: ${response.name}: $functionResult", false)) // Role is 'tool'

                // Step 4: Call the API again with the complete history to get a final text response.
                val finalApiResponse = geminiRepository.generateContent(serviceMessages.toList())
                handleGeminiResponse(finalApiResponse)
            }
            is GeminiResponse.ErrorResponse -> {
                setFinalResponse("Error: ${response.message}")
            }
        }
    }
    private fun executeFunction(name: String, args: JSONObject): String {
        return when (name) {
            "setAlarm" -> commandExecutor.setAlarm(args)
            "openApp" -> commandExecutor.openApp(args)
            "callContact" -> commandExecutor.callContact(args)
            "searchWeb" -> commandExecutor.searchWeb(args)
            else -> "Unknown function: $name"
        }
    }

    private fun setFinalResponse(text: String) {
        overlayAiResponse.value = text
        serviceMessages.add(Message(text, false))
        val params = Bundle()
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, "final_response")
    }


    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
                textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == "final_response") hideOverlay() }
                    override fun onError(utteranceId: String?) { if (utteranceId == "final_response") hideOverlay() }
                })
            }
        }
    }

    private fun showOverlay() {
        if (overlayView != null) return
        overlayStatusText.value = "Listening..."
        overlayAiResponse.value = null
        isMicListening.value = false
        serviceMessages.clear() // Start a fresh conversation

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(this@WakeWordService)
            setViewTreeViewModelStoreOwner(object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore get() = ViewModelStore()
            })
            setContent {
                MaterialTheme {
                    AssistantOverlayUi(
                        statusText = overlayStatusText.value,
                        aiResponse = overlayAiResponse.value,
                        isMicActive = isMicListening.value,
                        onCloseClick = {
                            speechRecognizer.cancel()
                            textToSpeech.stop()
                            hideOverlay()
                        }
                    )
                }
            }
        }
        windowManager.addView(overlayView, params)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        startListeningForCommand()
    }

    private fun hideOverlay() {
        serviceScope.launch {
            overlayView?.let {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                windowManager.removeView(it)
                overlayView = null
                wakeWordManager?.start()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::textToSpeech.isInitialized) { textToSpeech.shutdown() }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        wakeWordManager?.delete()
        if (::speechRecognizer.isInitialized) { speechRecognizer.destroy() }
        hideOverlay()
    }

    // --- Unchanged Service Methods ---
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { return START_STICKY }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartServiceIntent = Intent(applicationContext, this.javaClass).also { it.setPackage(packageName) }
        val restartServicePendingIntent = PendingIntent.getService(applicationContext, 1, restartServiceIntent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val alarmService = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    private fun startListeningForCommand() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer.startListening(intent)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "wake_word_channel")
            .setContentTitle("AI Voice Assistant").setContentText("Listening for wake word...")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("wake_word_channel", "Wake Word Detection", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
}


// --- Unchanged Service UI ---
class ServiceLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
}

@Composable
fun AssistantOverlayUi(
    statusText: String,
    aiResponse: String?,
    isMicActive: Boolean,
    onCloseClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isMicActive) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = "Microphone Status"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onCloseClick) {
                        Icon(Icons.Default.Close, contentDescription = "Close Bar")
                    }
                }
            }

            if (!aiResponse.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = aiResponse, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}