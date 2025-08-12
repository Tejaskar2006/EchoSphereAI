package com.example.aivoiceassistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import androidx.annotation.RequiresPermission

class WakeWordManager(
    private val context: Context,
    accessKey: String,
    private val onWakeWordDetected: () -> Unit,
    builtInKeyword: Porcupine.BuiltInKeyword,
    private var sensitivity: Float = 0.5f
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var processingThread: Thread? = null
    private var isListening = false

    init {
        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(accessKey)
                .setKeyword(builtInKeyword)
                .setSensitivity(sensitivity)
                .build(context)
        } catch (e: PorcupineException) {
            Log.e("WakeWordManager", "Porcupine init error: ${e.message}", e)
        }
    }

    fun start() {
        if (porcupine == null) {
            Log.e("WakeWordManager", "Porcupine not initialized")
            return
        }
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("WakeWordManager", "Record audio permission not granted")
            return
        }
        if (processingThread != null) return

        isListening = true
        processingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            readAudio()
        }
        processingThread?.start()
    }

    fun stop() {
        isListening = false
        try {
            processingThread?.join()
            processingThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: InterruptedException) {
            Log.e("WakeWordManager", "Thread interrupted: ${e.message}", e)
        }
    }

    fun delete() {
        stop()
        porcupine?.delete()
        porcupine = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun readAudio() {
        val bufferSize = AudioRecord.getMinBufferSize(
            porcupine!!.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            porcupine!!.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        try {
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e("WakeWordManager", "Audio recording permission denied", e)
            return
        }

        val pcm = ShortArray(porcupine!!.frameLength)
        while (isListening && porcupine != null) {
            try {
                if (audioRecord?.read(pcm, 0, pcm.size) == pcm.size) {
                    val keywordIndex = porcupine!!.process(pcm)
                    if (keywordIndex >= 0) {
                        Log.d("WakeWordManager", "Wake word detected!")
                        onWakeWordDetected()
                    }
                }
            } catch (e: PorcupineException) {
                Log.e("WakeWordManager", "Porcupine processing error: ${e.message}", e)
            } catch (e: Exception) {
                Log.e("WakeWordManager", "Unexpected error: ${e.message}", e)
            }
        }
    }
}