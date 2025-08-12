package com.example.aivoiceassistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dagger.hilt.android.AndroidEntryPoint
import java.util.*

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    internal lateinit var textToSpeech: TextToSpeech
    private lateinit var speechRecognizerLauncher: ActivityResultLauncher<Intent>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Void?>
    private val viewModel: VoiceAssistantViewModel by viewModels()

    // MutableState to control the visibility and content of the image prompt dialog
    internal val showImagePromptDialog = mutableStateOf<Bitmap?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isRecordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val isCameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        if (isRecordAudioGranted) {
            startWakeWordService()
            requestBatteryOptimizationExemption()
        }
        if (!isRecordAudioGranted || !isCameraGranted) {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestOverlayPermission()

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            }
        }

        speechRecognizerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                results?.firstOrNull()?.let { input ->
                    viewModel.processInput(input, textToSpeech)
                }
            }
        }

        takePictureLauncher = registerForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap ->
            bitmap?.let {
                // When a picture is taken, update the state to show the dialog
                showImagePromptDialog.value = it
            }
        }

        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.CAMERA // Ensure camera permission is requested
            )
        )

        handleWakeWordIntent(intent)

        setContent {
            viewModel.setApiKey(BuildConfig.GEMINI_API_KEY)
            VoiceAssistantApp(
                viewModel = viewModel,
                onStartSpeechRecognition = {
                    startSpeechRecognition(speechRecognizerLauncher)
                },
                onStartImageRecognition = {
                    takePictureLauncher.launch()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWakeWordIntent(intent)
    }

    private fun handleWakeWordIntent(intent: Intent) {
        if (intent.getBooleanExtra("WAKE_WORD_DETECTED", false)) {
            startSpeechRecognition(speechRecognizerLauncher)
        }
    }

    private fun startSpeechRecognition(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...")
        }
        launcher.launch(intent)
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("To display the assistant over other apps, please grant the overlay permission.")
                .setPositiveButton("Open Settings") { _, _ -> startActivity(intent) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun startWakeWordService() {
        val serviceIntent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs Microphone, Camera, Contacts, and Phone permissions to function fully. Please grant them in Settings.")
            .setPositiveButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        textToSpeech.shutdown()
        super.onDestroy()
    }
}

// --- Composables ---

@SuppressLint("ContextCastToActivity")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistantApp(
    viewModel: VoiceAssistantViewModel,
    onStartSpeechRecognition: () -> Unit,
    onStartImageRecognition: () -> Unit
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isListeningForWakeWord by viewModel.isListeningForWakeWord.collectAsState()
    val listState = rememberLazyListState()

    // Handle the image prompt dialog
    val activity = (LocalContext.current as MainActivity)
    val imageToShowPromptFor by activity.showImagePromptDialog

    if (imageToShowPromptFor != null) {
        ImagePromptDialog(
            bitmap = imageToShowPromptFor!!,
            onDismiss = { activity.showImagePromptDialog.value = null },
            onSubmit = { prompt ->
                viewModel.processImageInput(prompt, imageToShowPromptFor!!, activity.textToSpeech)
                activity.showImagePromptDialog.value = null
            }
        )
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AI Voice Assistant") },
                    actions = { if (isListeningForWakeWord) { ListeningIndicator() } }
                )
            },
            bottomBar = {
                BottomBar(
                    onMicClick = {
                        viewModel.stopListeningForWakeWord()
                        onStartSpeechRecognition()
                    },
                    onCameraClick = {
                        viewModel.stopListeningForWakeWord()
                        onStartImageRecognition()
                    },
                    isLoading = isLoading,
                    isListeningForWakeWord = isListeningForWakeWord
                )
            }
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message -> MessageBubble(message) }
                if (isLoading) {
                    item {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).wrapContentWidth(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BottomBar(
    onMicClick: () -> Unit,
    onCameraClick: () -> Unit,
    isLoading: Boolean,
    isListeningForWakeWord: Boolean
) {
    Surface(modifier = Modifier.fillMaxWidth(), shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onMicClick, enabled = !isLoading) {
                Icon(Icons.Default.Mic, contentDescription = "Speak")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        isListeningForWakeWord -> "Listening..."
                        isLoading -> "Processing..."
                        else -> "Tap to Speak"
                    }
                )
            }
            IconButton(onClick = onCameraClick, enabled = !isLoading) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Use Camera", modifier = Modifier.size(28.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePromptDialog(bitmap: Bitmap, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Captured Image", modifier = Modifier.height(200.dp).fillMaxWidth())
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What do you want to ask about this image?") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { if (text.isNotBlank()) onSubmit(text) }) { Text("Submit") }
                }
            }
        }
    }
}


@Composable
fun MessageBubble(message: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (message.isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            // --- FIX IS HERE ---
            // Column to hold both the image and the text
            Column(modifier = Modifier.padding(12.dp)) {
                // Conditionally display the image if it exists
                message.image?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "User-provided image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp) // Adjust height as needed
                            .padding(bottom = 8.dp)
                    )
                }

                // Display the text
                Text(
                    text = message.text,
                    textAlign = if (message.isUser) TextAlign.End else TextAlign.Start
                )
            }
        }
    }
}
@Composable
fun ListeningIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(0xFF34A853), shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Listening", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}