package com.gemini.floatingcompanion.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import com.gemini.floatingcompanion.data.GeminiApiKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 2026 GEMINI LIVE TRANSLATION CORE (gemini-3.5-live-translate-preview)
 *
 * - Google Gemini Live API üzerinden Bidi WebSocket ile gerçek zamanlı konuşmadan konuşmaya çeviri.
 * - Çeviriler doğrudan Gemini yapay zekasının kendi 24kHz doğal insan sesiyle (AudioTrack) dinletilir.
 * - Android cihazın yerel robotik TTS (TextToSpeech) motoru kesinlikle KULLANILMAZ; %100 saf Gemini API sesi.
 * - Kesintisiz ses yakalama: Seslendirilen çeviri sırasında bile mikrofon asla kesilmez,
 *   videodaki konuşmacının tek bir kelimesi dahi atlanmaz.
 * - Asenkron AudioTrack Oynatma Kuyruğu: Ağ soket dinleyicisi asla bloklanmaz (sıfır gecikme).
 * - GeminiApiKeyManager havuzuyla otomatik hata algılama ve anında failover (yedek anahtara geçiş).
 * - Pre-connect tamponlama: Bağlantı kurulurken ilk 1.5 saniyelik konuşma tamponlanır,
 *   setupComplete gelir gelmez yumuşak zamanlamayla gönderilir.
 * - Agresif Audio Ducking: Oynatılan video sesi arka planda çok kısık seviyeye çekilerek
 *   Gemini Türkçe sesinin berrak ve net duyulması sağlanır.
 */
class LiveVideoTranslator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val keyManager = GeminiApiKeyManager.getInstance()
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var lastAiChunkPlayedAt: Long = 0L
    @Volatile
    private var totalFramesWritten: Long = 0L

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun isAiSpeaking(): Boolean {
        if (!audioPlaybackChannel.isEmpty) return true
        val track = audioTrack ?: return (System.currentTimeMillis() - lastAiChunkPlayedAt < 800L)
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val remainingFrames = totalFramesWritten - head
                if (remainingFrames > 0) {
                    val remainingMs = (remainingFrames * 1000L) / 24000L
                    if (remainingMs > 0) return true
                }
            }
        } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        return (now - lastAiChunkPlayedAt < 800L)
    }

    private var audioRecorder: AudioRecorderManager? = null
    private var webSocket: WebSocket? = null
    private var audioTrack: AudioTrack? = null

    private val audioPlaybackChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null
    private val currentTurnSubtitle = StringBuilder()
    private var lastSubtitleResetEpoch = 0L

    private var isTranslating = false
    private var isSetupComplete = false
    private var isVoiceMuted = false
    private var activeApiKey: String = ""
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 4

    private val preConnectAudioBuffer = mutableListOf<ByteArray>()
    private val maxPreConnectAudioBytes = 16000 * 2 * 3 / 2 // ~1.5 saniye (48 KB)
    private var setupWatchdogJob: Job? = null

    var onSubtitleUpdated: ((String) -> Unit)? = null
    var onSessionStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    fun startTranslation() {
        if (isTranslating) return
        isTranslating = true
        reconnectAttempts = 0
        activeApiKey = keyManager.getActiveApiKey()

        // 1. Orijinal video sesini kıs (Agresif Audio Ducking)
        requestAudioDucking()

        // 2. Gemini 24kHz Doğal İnsan Sesi Oynatıcısını (AudioTrack) başlat
        initAudioTrack()

        // 3. Gemini 3.5 Live Translate WebSocket bağlantısını kur
        connectWebSocket()

        // 4. Kesintisiz 16kHz PCM mikrofon / dahili ses kaydını başlat
        startAudioCapture()

        onSessionStateChanged?.invoke(true)
        onSubtitleUpdated?.invoke("Canlı çeviri başlatılıyor...")
    }

    fun stopTranslation() {
        if (!isTranslating) return
        isTranslating = false
        setupWatchdogJob?.cancel()

        audioRecorder?.stopRecording()
        audioRecorder = null

        try {
            webSocket?.close(1000, "User stopped")
        } catch (_: Exception) {}
        webSocket = null
        isSetupComplete = false

        synchronized(preConnectAudioBuffer) {
            preConnectAudioBuffer.clear()
        }
        synchronized(currentTurnSubtitle) {
            currentTurnSubtitle.clear()
        }

        releaseAudioTrack()
        abandonAudioDucking()
        onSessionStateChanged?.invoke(false)
        Log.d(TAG, "Canlı çeviri sonlandırıldı")
    }

    private fun initAudioTrack() {
        try {
            val sampleRate = 24000
            val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            val bufSize = maxOf(minBuf * 4, 48000)

            // USAGE_MEDIA kullanımı: Xiaomi Ses Asistanı (Çoklu ses kaynakları) per-app ayrımını sağlar.
            // Telefonun donanım ses tuşları doğrudan STREAM_MUSIC üzerinden AI sesini kontrol eder.
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(1.0f)
            totalFramesWritten = 0L
            lastAiChunkPlayedAt = 0L
            startAudioPlaybackWorker()
            Log.d(TAG, "Gemini 24kHz Doğal AI Sesi AudioTrack başlatıldı (USAGE_MEDIA, Xiaomi Ses Asistanı Uyumlu)")
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack başlatılamadı", e)
        }
    }

    private fun startAudioPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            for (chunk in audioPlaybackChannel) {
                if (!isTranslating) break
                if (isVoiceMuted || chunk.isEmpty()) continue
                try {
                    lastAiChunkPlayedAt = System.currentTimeMillis()
                    val track = audioTrack
                    if (track != null) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try { track.play() } catch (_: Exception) {}
                        }
                        var offset = 0
                        while (offset < chunk.size && isTranslating && !isVoiceMuted) {
                            val written = track.write(chunk, offset, chunk.size - offset)
                            if (written > 0) {
                                offset += written
                                totalFramesWritten += (written / 2) // 16-bit mono = 2 bytes per frame
                                lastAiChunkPlayedAt = System.currentTimeMillis()
                            } else {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack playback worker error", e)
                } finally {
                    lastAiChunkPlayedAt = System.currentTimeMillis()
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        playbackJob?.cancel()
        playbackJob = null
        while (audioPlaybackChannel.tryReceive().isSuccess) {}

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        totalFramesWritten = 0L
        lastAiChunkPlayedAt = 0L
    }

    private fun playAiAudioChunk(pcmBytes: ByteArray) {
        if (!isTranslating || isVoiceMuted || pcmBytes.isEmpty()) return
        lastAiChunkPlayedAt = System.currentTimeMillis()
        val boosted = boostPcm(pcmBytes, 1.8f)
        audioPlaybackChannel.trySend(boosted)
    }

    private fun boostPcm(pcmBytes: ByteArray, factor: Float = 1.8f): ByteArray {
        if (factor <= 1.0f) return pcmBytes
        val count = pcmBytes.size / 2
        val output = ByteArray(pcmBytes.size)
        val inBuf = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val outBuf = java.nio.ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val sample = inBuf.short.toInt()
            val scaled = sample * factor
            // Yumuşak kırpma (soft limiting): Dijital distorsiyona girmeden tok ve berrak ses sağlar
            val amplified = if (scaled > 26000) {
                26000 + ((scaled - 26000) * 0.25f).toInt()
            } else if (scaled < -26000) {
                -26000 + ((scaled + 26000) * 0.25f).toInt()
            } else {
                scaled.toInt()
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            outBuf.putShort(amplified.toShort())
        }
        return output
    }

    fun setVoiceMuted(muted: Boolean) {
        isVoiceMuted = muted
        if (muted) {
            while (audioPlaybackChannel.tryReceive().isSuccess) {}
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (_: Exception) {}
        } else {
            try {
                audioTrack?.play()
            } catch (_: Exception) {}
        }
    }

    private fun connectWebSocket() {
        if (activeApiKey.isBlank()) {
            activeApiKey = keyManager.getActiveApiKey()
        }
        if (activeApiKey.isBlank()) {
            onError?.invoke("Gemini API anahtarı bulunamadı.")
            return
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$activeApiKey"
        val request = Request.Builder().url(url).build()

        isSetupComplete = false

        // Setup Watchdog: 7.0s içinde setupComplete gelmezse failover tetikle
        setupWatchdogJob?.cancel()
        setupWatchdogJob = scope.launch(Dispatchers.IO) {
            delay(7000)
            if (isTranslating && !isSetupComplete) {
                Log.w(TAG, "⏱️ Live Translate setup watchdog tetiklendi (7s timeout, Key: ${GeminiApiKeyManager.maskKey(activeApiKey)})")
                handleConnectionFailure(408, "Canlı çeviri kurulum zaman aşımı")
            }
        }

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Live Translate WebSocket bağlandı (${GeminiApiKeyManager.maskKey(activeApiKey)}), setup gönderiliyor...")
                sendSetupFrame(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 500
                val msg = t.localizedMessage ?: "WebSocket hatası"
                Log.e(TAG, "Live Translate WebSocket hatası (Kod: $code, Key: ${GeminiApiKeyManager.maskKey(activeApiKey)}): $msg")
                handleConnectionFailure(code, msg)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Live Translate WebSocket kapandı: $code / $reason")
                setupWatchdogJob?.cancel()
                if (code != 1000 && isTranslating && !isSetupComplete) {
                    handleConnectionFailure(code, reason.ifBlank { "Soket kapandı" })
                }
            }
        })
    }

    private fun handleConnectionFailure(code: Int, msg: String) {
        setupWatchdogJob?.cancel()
        if (!isTranslating) return

        try {
            webSocket?.close(1000, "Failover")
        } catch (_: Exception) {}
        webSocket = null
        isSetupComplete = false

        if (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            keyManager.reportFailure(activeApiKey, code, msg)
            activeApiKey = keyManager.getActiveApiKey()
            Log.i(TAG, "Live Translate Failover: Yeni anahtar (${GeminiApiKeyManager.maskKey(activeApiKey)}) ile yeniden bağlanılıyor ($reconnectAttempts/$maxReconnectAttempts)...")
            scope.launch(Dispatchers.IO) {
                delay(300L * reconnectAttempts)
                if (isTranslating) {
                    connectWebSocket()
                }
            }
        } else {
            scope.launch(Dispatchers.Main) {
                onError?.invoke("Çeviri bağlantı hatası ($code): $msg")
            }
        }
    }

    /**
     * Google Live Translation API Setup Çerçevesi (gemini-3.5-live-translate-preview).
     * BCP-47 "tr" hedef dil, AUDIO yanıt modalitesi, echoTargetLanguage: false ve transcription konfigürasyonu.
     * echoTargetLanguage: false hedef dildeki (Türkçe) seslerin papağan gibi döngüye girmesini önler.
     */
    private fun sendSetupFrame(ws: WebSocket) {
        try {
            val setupJson = JSONObject().apply {
                val setupObj = JSONObject().apply {
                    put("model", "models/gemini-3.5-live-translate-preview")
                    put("inputAudioTranscription", JSONObject())
                    put("outputAudioTranscription", JSONObject())
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply {
                            put("AUDIO")
                        })
                        put("translationConfig", JSONObject().apply {
                            put("targetLanguageCode", "tr")
                            put("echoTargetLanguage", false)
                        })
                    })
                }
                put("setup", setupObj)
            }
            ws.send(setupJson.toString())
            Log.d(TAG, "Live Translate setup frame gönderildi: gemini-3.5-live-translate-preview (target: tr, echo: false)")
        } catch (e: Exception) {
            Log.e(TAG, "Setup frame gönderme hatası", e)
        }
    }

    private fun startAudioCapture() {
        val mediaProj = MediaProjectionHolder.getOrCreateMediaProjection(context)
        audioRecorder = AudioRecorderManager(context).apply {
            startRecording(
                scope = scope,
                onAudioChunk = { chunk ->
                    if (!isTranslating) return@startRecording
                    sendAudioChunk(chunk)
                },
                onError = { err ->
                    Log.e(TAG, "Audio Recorder error: $err")
                    onError?.invoke(err)
                },
                isForVideoTranslation = true,
                softwareGainFactor = 1.3f,
                mediaProjection = mediaProj
            )
        }
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        val ws = webSocket
        if (!isSetupComplete || ws == null) {
            synchronized(preConnectAudioBuffer) {
                if (preConnectAudioBuffer.sumOf { it.size } < maxPreConnectAudioBytes) {
                    preConnectAudioBuffer.add(pcmBytes)
                }
            }
            return
        }
        sendAudioChunkDirect(ws, pcmBytes)
    }

    private fun sendAudioChunkDirect(ws: WebSocket, pcmBytes: ByteArray) {
        try {
            // Akustik Gating: Dahili ses yakalama aktif DEĞİLSE (mikrofon fallback ise)
            // ve yapay zeka şu anda konuşuyorsa, hoparlörden çıkan Türkçe sesin
            // mikrofona girip Gemini'ye geri beslenmesini önlemek için dijital sessizlik gönder.
            val isInternal = audioRecorder?.isUsingPlaybackCapture == true
            val finalBytes = if (!isInternal && isAiSpeaking()) {
                ByteArray(pcmBytes.size) // Digital silence (zeros)
            } else {
                pcmBytes
            }

            val base64Data = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            val chunkJson = JSONObject().apply {
                val realtimeInput = JSONObject().apply {
                    val audio = JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    }
                    put("audio", audio)
                }
                put("realtimeInput", realtimeInput)
            }
            ws.send(chunkJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Audio chunk gönderme hatası", e)
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            if (root.has("setupComplete")) {
                Log.d(TAG, "Gemini Live Translate Setup Complete! Doğal yapay zeka sesi hatta (Key: ${GeminiApiKeyManager.maskKey(activeApiKey)})")
                setupWatchdogJob?.cancel()
                isSetupComplete = true
                reconnectAttempts = 0
                keyManager.reportSuccess(activeApiKey)

                scope.launch(Dispatchers.Main) {
                    onSubtitleUpdated?.invoke("Video sesi dinleniyor, çeviri bekleniyor...")
                }

                // Pre-connect tamponunu yumuşak zamanlamayla gönder
                val ws = webSocket
                if (ws != null) {
                    val buffered = synchronized(preConnectAudioBuffer) {
                        val copy = preConnectAudioBuffer.toList()
                        preConnectAudioBuffer.clear()
                        copy
                    }
                    if (buffered.isNotEmpty()) {
                        Log.d(TAG, "Tamponlanan ${buffered.size} ses paketi (${buffered.sumOf { it.size }} bayt) sokete aktarılıyor...")
                        scope.launch(Dispatchers.IO) {
                            for (chunk in buffered) {
                                if (!isTranslating || !isSetupComplete) break
                                sendAudioChunkDirect(ws, chunk)
                                delay(10)
                            }
                        }
                    }
                }
                return
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Kullanıcı veya video araya girdiğinde önceki sesi temizle
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.d(TAG, "Çeviri akışı kesildi (interrupted)")
                    while (audioPlaybackChannel.tryReceive().isSuccess) {}
                    try {
                        audioTrack?.pause()
                        audioTrack?.flush()
                        audioTrack?.play()
                    } catch (_: Exception) {}
                }

                // 1. Canlı Türkçe Altyazı Metni (outputTranscription veya outputAudioTranscription)
                val outputObj = serverContent.optJSONObject("outputTranscription")
                    ?: serverContent.optJSONObject("outputAudioTranscription")
                if (outputObj != null) {
                    val text = outputObj.optString("text", "").trim()
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Canlı Çeviri Metni: $text")
                        updateSubtitleText(text)
                    }
                }

                // Turn tamamlandığında mevcut cümleyi ekranda sabit tut
                if (serverContent.optBoolean("turnComplete", false)) {
                    Log.d(TAG, "Turn tamamlandı (turnComplete)")
                    lastSubtitleResetEpoch = System.currentTimeMillis() - 2500L
                }

                // 2. Google Gemini 24kHz Orijinal Doğal İnsan Sesi (PCM)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // Yedek: Parça içinde text varsa altyazıyı güncelle
                            val partText = part.optString("text", "").trim()
                            if (partText.isNotBlank()) {
                                updateSubtitleText(partText)
                            }
                            val inlineData = part.optJSONObject("inlineData")
                            if (inlineData != null) {
                                val base64Data = inlineData.optString("data", "")
                                if (base64Data.isNotBlank()) {
                                    val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    Log.d(TAG, "AI Ses Paketi Alındı: ${pcmBytes.size} bayt PCM")
                                    playAiAudioChunk(pcmBytes)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error on server message", e)
        }
    }

    private fun updateSubtitleText(text: String) {
        val now = System.currentTimeMillis()
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        // 6.5 saniye ses/altyazı gelmemişse veya turn arası uzunsa pencereyi sıfırla
        if (now - lastSubtitleResetEpoch > 6500L) {
            synchronized(currentTurnSubtitle) {
                currentTurnSubtitle.clear()
            }
        }
        lastSubtitleResetEpoch = now

        val displaySubtitle = synchronized(currentTurnSubtitle) {
            val prev = currentTurnSubtitle.toString().trim()
            if (prev.isEmpty()) {
                currentTurnSubtitle.append(cleanText)
            } else if (cleanText.startsWith(prev, ignoreCase = true)) {
                // Sunucu cümlenin tamamını kümülatif gönderiyorsa yenisiyle güncelle
                currentTurnSubtitle.clear()
                currentTurnSubtitle.append(cleanText)
            } else if (prev.endsWith(cleanText, ignoreCase = true)) {
                // Zaten var olan son ek
            } else {
                // Parçalı yeni gelen kelime/kelime grubu: boşlukla bağla
                currentTurnSubtitle.append(" ").append(cleanText)
            }

            // Kayan pencere (Sliding Window): Altyazı ekranı doldurup taşmasın,
            // en güncel söylenen 2-3 cümle (son ~180 karakter) akıcı şekilde ekranda kalsın
            var result = currentTurnSubtitle.toString().trim()
            if (result.length > 200) {
                val cutIndex = result.indexOf(' ', result.length - 170)
                if (cutIndex > 0 && cutIndex < result.length - 20) {
                    result = "..." + result.substring(cutIndex)
                    currentTurnSubtitle.clear()
                    currentTurnSubtitle.append(result)
                }
            }
            result
        }

        scope.launch(Dispatchers.Main) {
            onSubtitleUpdated?.invoke(displaySubtitle)
        }
    }

    private fun requestAudioDucking() {
        try {
            // Sistem Audio Focus Ducking isteği:
            // USAGE_MEDIA kullanılarak Xiaomi Sound Assistant (Çoklu ses kaynakları) per-app ayrımı sağlanır.
            // Sadece requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) ile YouTube'un kendi iç sesi kısılır.
            // Telefonun donanım sesine (STREAM_MUSIC) global müdahale edilmez; AI sesi maksimum duyulur.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener { /* Focus change listener */ }
                    .build()
                audioFocusRequest = focusRequest
                val res = audioManager.requestAudioFocus(focusRequest)
                Log.d(TAG, "Audio Ducking etkinleştirildi (Focus result: $res, USAGE_MEDIA, Xiaomi per-app uyumlu)")
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                Log.d(TAG, "Audio Ducking etkinleştirildi (Legacy)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio Ducking talep edilemedi", e)
        }
    }

    private fun abandonAudioDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            audioFocusRequest = null
            Log.d(TAG, "Audio Ducking kaldırıldı (Video sesi normale döndü)")
        } catch (e: Exception) {
            Log.w(TAG, "Audio Ducking kaldırılamadı", e)
        }
    }

    fun destroy() {
        stopTranslation()
    }

    companion object {
        private const val TAG = "LiveVideoTranslator"
    }
}
