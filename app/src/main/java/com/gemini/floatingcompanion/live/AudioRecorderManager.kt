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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorderManager(private val context: Context) {

    private var audioRecord: AudioRecord? = null
    private var aec: android.media.audiofx.AcousticEchoCanceler? = null
    private var ns: android.media.audiofx.NoiseSuppressor? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    @Volatile
    var isUsingPlaybackCapture: Boolean = false
        private set

    @Volatile
    var isStereoCapture: Boolean = false
        private set

    private val pcmOutputStream = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        onAudioChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        isForVideoTranslation: Boolean = false,
        softwareGainFactor: Float = 1.0f,
        mediaProjection: android.media.projection.MediaProjection? = null
    ) {
        if (isRecording) return

        try {
            var record: AudioRecord? = null
            isUsingPlaybackCapture = false

            // 1. Android 10+ (API 29+) Dahili Medya Sesi Yakalama (AudioPlaybackCapture)
            // Mikrofona hiç dokunmadan YouTube / Video akışını doğrudan donanımsal dijital berraklıkla yakalar.
            // excludeUid ile yapay zekanın kendi sesi kernel seviyesinde filtrelenerek 0 yankı garantilenir.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                mediaProjection != null &&
                isForVideoTranslation
            ) {
                try {
                    val playbackConfig = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                        .excludeUid(context.applicationInfo.uid)
                        .build()

                    // Önce standart MONO dene
                    val audioFormatMono = AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()

                    val bufferSizeMono = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AUDIO_FORMAT
                    ).coerceAtLeast(CHUNK_SIZE_BYTES * 2)

                    try {
                        record = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .setAudioFormat(audioFormatMono)
                            .setBufferSizeInBytes(bufferSizeMono)
                            .build()
                        isStereoCapture = false
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioPlaybackCapture MONO yapılandırılamadı: ${e.message}")
                        record = null
                    }

                    // Bazı OEM HAL'leri STEREO capture zorunlu tutar; MONO başlatılamadıysa STEREO dene
                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        record?.release()
                        val audioFormatStereo = AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()

                        val bufferSizeStereo = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_STEREO,
                            AUDIO_FORMAT
                        ).coerceAtLeast(CHUNK_SIZE_BYTES * 4)

                        record = AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(playbackConfig)
                            .setAudioFormat(audioFormatStereo)
                            .setBufferSizeInBytes(bufferSizeStereo)
                            .build()
                        isStereoCapture = true
                    }

                    if (record?.state == AudioRecord.STATE_INITIALIZED) {
                        isUsingPlaybackCapture = true
                        Log.i(TAG, "🎧 Dahili Sistem Sesi Yakalama (AudioPlaybackCapture) devrede! (0 Mikrofon, Saf Dijital Akış, Stereo=$isStereoCapture, AppUidExcluded=${context.applicationInfo.uid})")
                    } else {
                        Log.w(TAG, "AudioPlaybackCapture başlatılamadı, mikrofon fallback devreye giriyor")
                        record?.release()
                        record = null
                        isStereoCapture = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioPlaybackCapture yapılandırılamadı, mikrofon fallback'e geçiliyor", e)
                    record = null
                    isStereoCapture = false
                }
            }

            // 2. Mikrofon Fallback Modu (MediaProjection yoksa veya desteklenmiyorsa)
            if (record == null) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    onError("Mikrofon izni verilmemiş.")
                    return
                }

                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT
                ).coerceAtLeast(CHUNK_SIZE_BYTES * 2)

                // Video ve ortam konuşmalarını net yakalamak için standart MIC kullanılır
                val audioSource = MediaRecorder.AudioSource.MIC
                try {
                    record = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioSource MIC başlatılamadı", e)
                }
                Log.i(TAG, "🎤 Mikrofon yakalama modu devrede (Akustik Gating destekli)")
            }

            audioRecord = record

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord başlatılamadı.")
                return
            }

            val sessionId = audioRecord?.audioSessionId ?: 0
            // Video çevirisinde veya dahili kayıtta harici AEC açılmaz (donanım sesi silmesin)
            if (!isForVideoTranslation && !isUsingPlaybackCapture && sessionId != 0) {
                try {
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.apply {
                            enabled = true
                        }
                        Log.d(TAG, "AcousticEchoCanceler aktif edildi (Yankı önleme)")
                    }
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(sessionId)?.apply {
                            enabled = true
                        }
                        Log.d(TAG, "NoiseSuppressor aktif edildi (Gürültü filtreleme)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ses efektleri başlatılamadı", e)
                }
            } else if (isForVideoTranslation) {
                Log.d(TAG, "Video Çeviri Modu: AEC ve NoiseSuppressor devre dışı (Dahili ses/Hoparlör sesi serbest)")
            }

            synchronized(pcmOutputStream) {
                pcmOutputStream.reset()
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = scope.launch(Dispatchers.IO) {
                val targetBytesPerChunk = if (isStereoCapture) CHUNK_SIZE_BYTES * 2 else CHUNK_SIZE_BYTES
                val buffer = ByteArray(targetBytesPerChunk)
                var accumulatedBytes = 0
                var chunkCounter = 0L

                while (isActive && isRecording) {
                    val currentRecord = audioRecord ?: break
                    val bytesToRead = targetBytesPerChunk - accumulatedBytes
                    val readResult = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        currentRecord.read(buffer, accumulatedBytes, bytesToRead, AudioRecord.READ_BLOCKING)
                    } else {
                        currentRecord.read(buffer, accumulatedBytes, bytesToRead)
                    }

                    if (readResult > 0) {
                        accumulatedBytes += readResult
                        if (accumulatedBytes >= targetBytesPerChunk) {
                            val rawCaptured = buffer.copyOf(targetBytesPerChunk)
                            accumulatedBytes = 0
                            chunkCounter++

                            // Stereo yakalandıysa Gemini'ye 16kHz Mono olarak dönüştür
                            val monoChunk = if (isStereoCapture) {
                                downmixStereoToMono(rawCaptured)
                            } else {
                                rawCaptured
                            }

                            // Dahili ses zaten dijital olarak tam seviyededir; mikrofonda software gain uygulanır
                            val effectiveGain = if (isUsingPlaybackCapture) 1.0f else softwareGainFactor
                            val chunk = if (effectiveGain > 1.0f) {
                                applySoftwareGain(monoChunk, effectiveGain)
                            } else {
                                monoChunk
                            }

                            if (chunkCounter % 20 == 0L) {
                                val rms = calculateRms(chunk)
                                val modeStr = if (isUsingPlaybackCapture) {
                                    if (isStereoCapture) "Dahili Medya (Stereo->Mono)" else "Dahili Medya (Mono)"
                                } else {
                                    "Mikrofon"
                                }
                                Log.d(TAG, "Ses enerjisi ($modeStr RMS): ${rms.toInt()} (Kazanç: ${effectiveGain}x)")
                            }

                            synchronized(pcmOutputStream) {
                                if (pcmOutputStream.size() < 1_300_000) {
                                    pcmOutputStream.write(chunk)
                                }
                            }

                            onAudioChunk(chunk)
                        }
                    } else if (readResult < 0) {
                        Log.w(TAG, "AudioRecord read error: $readResult")
                        break
                    }
                }
            }
            Log.d(TAG, "Audio recording started (16kHz 16-bit Mono PCM, 100ms chunks, isPlaybackCapture=$isUsingPlaybackCapture, videoMode=$isForVideoTranslation)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            onError("Kayıt başlatılamadı: ${e.localizedMessage}")
        }
    }

    private fun downmixStereoToMono(stereoBytes: ByteArray): ByteArray {
        val sampleCount = stereoBytes.size / 4
        val monoBytes = ByteArray(sampleCount * 2)
        val inBuf = ByteBuffer.wrap(stereoBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val left = inBuf.short.toInt()
            val right = inBuf.short.toInt()
            val mono = ((left + right) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.putShort(mono.toShort())
        }
        return monoBytes
    }

    private fun applySoftwareGain(pcmBytes: ByteArray, gain: Float): ByteArray {
        val shortCount = pcmBytes.size / 2
        val output = ByteArray(pcmBytes.size)
        val inBuf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            val sample = inBuf.short.toInt()
            val amplified = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.putShort(amplified.toShort())
        }
        return output
    }

    private fun calculateRms(pcmBytes: ByteArray): Double {
        val count = pcmBytes.size / 2
        if (count == 0) return 0.0
        val buf = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        for (i in 0 until count) {
            val s = buf.short.toDouble()
            sum += s * s
        }
        return Math.sqrt(sum / count)
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            aec?.release()
            ns?.release()
        } catch (_: Exception) {}
        aec = null
        ns = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        } finally {
            audioRecord = null
            Log.d(TAG, "Audio recording stopped, recorded ${pcmOutputStream.size()} PCM bytes")
        }
    }

    fun getRecordedPcm(): ByteArray = synchronized(pcmOutputStream) {
        pcmOutputStream.toByteArray()
    }

    fun getRecordedWav(): ByteArray {
        val pcm = getRecordedPcm()
        if (pcm.isEmpty()) return ByteArray(0)
        return pcmToWav(pcm, SAMPLE_RATE)
    }

    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Short = 1,
        bitsPerSample: Short = 16
    ): ByteArray {
        val totalAudioLen = pcmData.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1) // AudioFormat 1 = PCM
        buffer.putShort(channels)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort()) // block align
        buffer.putShort(bitsPerSample)
        buffer.put("data".toByteArray())
        buffer.putInt(totalAudioLen)

        val out = ByteArrayOutputStream(header.size + pcmData.size)
        out.write(header)
        out.write(pcmData)
        return out.toByteArray()
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
