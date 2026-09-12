package com.gemini.floatingcompanion.live

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioRecorderManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        onAudioChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("Mikrofon izni verilmemiş.")
            return
        }

        if (isRecording) return

        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ).coerceAtLeast(CHUNK_SIZE_BYTES * 2)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord başlatılamadı.")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        val chunk = buffer.copyOf(bytesRead)
                        onAudioChunk(chunk)
                    }
                }
            }
            Log.d(TAG, "Audio recording started (16kHz 16-bit Mono PCM)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            onError("Kayıt başlatılamadı: ${e.localizedMessage}")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
            Log.d(TAG, "Audio recording stopped")
        }
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        // 100ms at 16kHz mono 16-bit = 1600 samples * 2 bytes = 3200 bytes
        const val CHUNK_SIZE_BYTES = 3200
    }
}
