package com.gemini.floatingcompanion.live

import android.util.Base64
import android.util.Log
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
    private val apiKey: String,
    private val model: String = "models/gemini-3.5-transcribe-live",
    private val languageCode: String = "tr",
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // infinite for websockets
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isSetupComplete = false
    private val finalizedParts = mutableListOf<String>()
    private var interimPart = ""
    private var finishJob: Job? = null

    var onLiveTranscriptUpdated: ((String) -> Unit)? = null
    var onSessionStateChanged: ((Boolean) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null

    fun connect() {
        if (apiKey.isBlank()) {
            onErrorOccurred?.invoke("Gemini API anahtarı girilmemiş. Lütfen ayarlar ekranından girin.")
            return
        }

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        finalizedParts.clear()
        interimPart = ""
        isSetupComplete = false

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected, sending setup frame...")
                sendSetupFrame(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                scope.launch(Dispatchers.Main) {
                    onSessionStateChanged?.invoke(false)
                    onErrorOccurred?.invoke("Bağlantı hatası: ${t.localizedMessage ?: "Bilinmeyen hata"}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                scope.launch(Dispatchers.Main) {
                    onSessionStateChanged?.invoke(false)
                }
            }
        })
    }

    private fun sendSetupFrame(ws: WebSocket) {
        try {
            val cleanModel = if (model.startsWith("models/")) model else "models/$model"
            val setupJson = JSONObject().apply {
                val setupObj = JSONObject().apply {
                    put("model", cleanModel)
                    put("generationConfig", JSONObject().apply {
                        put("responseModalities", JSONArray().apply { put("TEXT") })
                    })
                    put("inputAudioTranscription", JSONObject().apply {
                        val langArray = if (languageCode.isNotBlank()) {
                            JSONArray().apply { put(languageCode) }
                        } else {
                            JSONArray()
                        }
                        put("languageCodes", langArray)
                    })
                }
                put("setup", setupObj)
            }
            ws.send(setupJson.toString())
            Log.d(TAG, "Setup frame sent for model: $cleanModel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send setup frame", e)
        }
    }

    fun sendAudioChunk(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isSetupComplete) return

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

            // Wait 350ms event-driven timeout for final inputTranscription, then close safely
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
                Log.d(TAG, "Gemini Live Setup Completed successfully!")
                isSetupComplete = true
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

    private fun broadcastCurrentText() {
        val fullText = buildString {
            if (finalizedParts.isNotEmpty()) {
                append(finalizedParts.joinToString(" "))
            }
            if (interimPart.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append(interimPart)
            }
        }.trim()

        scope.launch(Dispatchers.Main) {
            onLiveTranscriptUpdated?.invoke(fullText)
        }
    }

    fun disconnect() {
        try {
            webSocket?.close(1000, "Normal Closure")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        } finally {
            webSocket = null
            isSetupComplete = false
        }
    }

    companion object {
        private const val TAG = "GeminiLiveAudioClient"
    }
}
