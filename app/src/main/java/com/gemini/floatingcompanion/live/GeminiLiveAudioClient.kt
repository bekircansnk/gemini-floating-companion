package com.gemini.floatingcompanion.live

import android.util.Base64
import android.util.Log
import com.gemini.floatingcompanion.data.GeminiApiKeyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class GeminiLiveAudioClient(
    private val apiKey: String? = null,
    private val model: String = "models/gemini-3.5-transcribe-live",
    private val languageCode: String = "tr",
    private val scope: CoroutineScope
) {
    private val keyManager = GeminiApiKeyManager.getInstance()
    private var currentApiKey: String = if (!apiKey.isNullOrBlank()) apiKey else keyManager.getActiveApiKey()
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var finishJob: Job? = null
    private var isSetupComplete = false
    private var lastResetEpochMs: Long = 0L

    private val finalizedParts = mutableListOf<String>()
    private var interimPart: String = ""
    private var webSocket: WebSocket? = null
    private var setupWatchdogJob: Job? = null
    private val preConnectAudioBuffer = mutableListOf<ByteArray>()
    private val maxPreConnectAudioBytes = 16000 * 2 * 4 // ~4 seconds buffer (128 KB)

    var onLiveTranscriptUpdated: ((String) -> Unit)? = null
    var onSessionStateChanged: ((Boolean) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null

    fun connect() {
        reconnectAttempts = 0
        connectInternal()
    }

    private fun connectInternal() {
        if (currentApiKey.isBlank()) {
            currentApiKey = keyManager.getActiveApiKey()
        }
        if (currentApiKey.isBlank()) {
            onErrorOccurred?.invoke("Gemini API anahtarı havuzda veya ayarlarda bulunamadı.")
            return
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        finalizedParts.clear()
        interimPart = ""
        isSetupComplete = false

        // 2.5-second setup watchdog: if setupComplete is not received in 2.5s, trigger failover
        setupWatchdogJob?.cancel()
        setupWatchdogJob = scope.launch(Dispatchers.IO) {
            delay(2500)
            if (!isSetupComplete) {
                Log.w(TAG, "⏱️ Gemini Live setup timeout (2.5s) on key: ${GeminiApiKeyManager.maskKey(currentApiKey)}")
                handleConnectionFailure(408, "Canlı bağlantı zaman aşımı (2.5s)")
            }
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket bağlandı (${GeminiApiKeyManager.maskKey(currentApiKey)}), kurulum çerçevesi gönderiliyor...")
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
                val msg = t.localizedMessage ?: "Bilinmeyen soket hatası"
                Log.e(TAG, "WebSocket bağlantı hatası (Kod: $code, Key: ${GeminiApiKeyManager.maskKey(currentApiKey)}): $msg", t)
                handleConnectionFailure(code, msg)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket kapandı: $code / $reason")
                setupWatchdogJob?.cancel()

                if (code != 1000 && !isSetupComplete) {
                    val msg = reason.ifBlank { "Soket kapatıldı (Kod: $code)" }
                    handleConnectionFailure(code, msg)
                } else {
                    scope.launch(Dispatchers.Main) {
                        onSessionStateChanged?.invoke(false)
                    }
                }
            }
        })
    }

    private fun handleConnectionFailure(code: Int, msg: String) {
        setupWatchdogJob?.cancel()
        disconnect()

        if (!isSetupComplete && reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++
            keyManager.reportFailure(currentApiKey, code, msg)
            currentApiKey = keyManager.getActiveApiKey()
            Log.i(TAG, "Failover devrede: Sıradaki anahtarla (${GeminiApiKeyManager.maskKey(currentApiKey)}) tekrar bağlanılıyor (Deneme $reconnectAttempts/$maxReconnectAttempts)...")
            scope.launch(Dispatchers.IO) {
                delay(350L * reconnectAttempts)
                connectInternal()
            }
        } else {
            scope.launch(Dispatchers.Main) {
                onSessionStateChanged?.invoke(false)
                onErrorOccurred?.invoke("Bağlantı hatası: $msg")
            }
        }
    }

    private fun sendSetupFrame(ws: WebSocket) {
        try {
            // Google Bidi Live API strictly requires models/gemini-3.5-transcribe-live for audio transcription
            val liveModel = "models/gemini-3.5-transcribe-live"

            val targetLangCode = when (languageCode.lowercase().trim()) {
                "tr" -> "tr-TR"
                "en" -> "en-US"
                "de" -> "de-DE"
                "fr" -> "fr-FR"
                "es" -> "es-ES"
                else -> if (languageCode.isNotBlank()) languageCode else "tr-TR"
            }

            val setupJson = JSONObject().apply {
                val setupObj = JSONObject().apply {
                    put("model", liveModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("TEXT") })
                    })
                    put("inputAudioTranscription", JSONObject().apply {
                        put("languageCodes", JSONArray().apply { put(targetLangCode) })
                        put("mode", "SMART")
                    })
                }
                put("setup", setupObj)
            }
            ws.send(setupJson.toString())
            Log.d(TAG, "Setup frame sent for model: $liveModel, languageCodes: [$targetLangCode], mode: SMART")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending setup frame", e)
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
            val base64Data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
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
            Log.e(TAG, "Failed to send audio chunk", e)
        }
    }

    fun finishSession() {
        setupWatchdogJob?.cancel()
        val ws = webSocket ?: return
        try {
            val finishJson = JSONObject().apply {
                val realtimeInput = JSONObject().apply {
                    put("audioStreamEnd", true)
                }
                put("realtimeInput", realtimeInput)
            }
            ws.send(finishJson.toString())
            Log.d(TAG, "audioStreamEnd sent")

            // Wait 350ms for final server transcription frames, then close safely
            finishJob?.cancel()
            finishJob = scope.launch(Dispatchers.IO) {
                delay(350)
                disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finishing session", e)
            disconnect()
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            if (root.has("setupComplete")) {
                Log.d(TAG, "Gemini Live Setup Complete on key: ${GeminiApiKeyManager.maskKey(currentApiKey)}")
                setupWatchdogJob?.cancel()
                isSetupComplete = true
                keyManager.reportSuccess(currentApiKey)

                // Instantly flush pre-connect audio buffer so speech from millisecond 0 is delivered!
                val ws = webSocket
                if (ws != null) {
                    val buffered = synchronized(preConnectAudioBuffer) {
                        val copy = preConnectAudioBuffer.toList()
                        preConnectAudioBuffer.clear()
                        copy
                    }
                    if (buffered.isNotEmpty()) {
                        Log.d(TAG, "Flushing ${buffered.size} pre-connect audio chunks (${buffered.sumOf { it.size }} bytes) to live websocket...")
                        for (chunk in buffered) {
                            sendAudioChunkDirect(ws, chunk)
                        }
                    }
                }

                scope.launch(Dispatchers.Main) {
                    onSessionStateChanged?.invoke(true)
                }
                return
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                if (serverContent.has("inputTranscription")) {
                    val inputObj = serverContent.getJSONObject("inputTranscription")
                    val piece = inputObj.optString("text", "").trim()
                    if (piece.isNotEmpty()) {
                        if (finalizedParts.isEmpty() || finalizedParts.last() != piece) {
                            finalizedParts.add(piece)
                        }
                        interimPart = ""
                        broadcastCurrentText()
                    }
                } else if (serverContent.has("interimInputTranscription")) {
                    val interimObj = serverContent.getJSONObject("interimInputTranscription")
                    val piece = interimObj.optString("text", "").trim()
                    if (piece.isNotEmpty()) {
                        interimPart = piece
                        broadcastCurrentText()
                    }
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val modelText = parts.getJSONObject(0).optString("text", "")
                        if (modelText.isNotBlank()) {
                            finalizedParts.add(modelText.trim())
                            interimPart = ""
                            broadcastCurrentText()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: $jsonString", e)
        }
    }

    fun hasTranscribedText(): Boolean = finalizedParts.isNotEmpty() || interimPart.isNotBlank()

    /**
     * Mesaj gönderildiğinde veya kutu temizlendiğinde geçmiş metin hafızasını sıfırlar.
     * Böylece kullanıcı mikrofonu kapatmadan seri mesajlaştığında eski cümleler yeni mesaja sızmaz.
     */
    fun resetTranscript() {
        lastResetEpochMs = System.currentTimeMillis()
        finalizedParts.clear()
        interimPart = ""
        Log.d(TAG, "Transkript tamponu sıfırlandı (Yeni mesaj turu başladı, epoch: $lastResetEpochMs)")
    }

    fun getCurrentTranscript(): String = buildString {
        if (finalizedParts.isNotEmpty()) {
            append(finalizedParts.joinToString(" "))
        }
        if (interimPart.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(interimPart)
        }
    }.trim()

    private fun broadcastCurrentText() {
        val fullText = getCurrentTranscript()
        scope.launch(Dispatchers.Main) {
            onLiveTranscriptUpdated?.invoke(fullText)
        }
    }

    fun disconnect() {
        setupWatchdogJob?.cancel()
        finishJob?.cancel()
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        } finally {
            webSocket = null
            isSetupComplete = false
            synchronized(preConnectAudioBuffer) {
                preConnectAudioBuffer.clear()
            }
        }
    }

    companion object {
        private const val TAG = "GeminiLiveAudioClient"
    }
}
