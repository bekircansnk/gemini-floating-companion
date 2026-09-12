package com.gemini.floatingcompanion.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.gemini.floatingcompanion.R
import com.gemini.floatingcompanion.data.BubbleState
import com.gemini.floatingcompanion.data.CameraMode
import com.gemini.floatingcompanion.data.GeminiApiKeyManager
import com.gemini.floatingcompanion.data.PreferencesManager
import com.gemini.floatingcompanion.live.AudioRecorderManager
import com.gemini.floatingcompanion.live.GeminiLiveAudioClient
import com.gemini.floatingcompanion.service.FloatingBubbleService
import com.gemini.floatingcompanion.service.GeminiAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class FloatingBubbleManager private constructor(private val context: Context) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = PreferencesManager.getInstance(context)

    private var bubbleView: FloatingBubbleView? = null
    private var radialMenuView: RadialMenuView? = null
    private var cameraOverlay: FloatingCameraOverlay? = null

    private var audioRecorder: AudioRecorderManager? = null
    private var liveAudioClient: GeminiLiveAudioClient? = null
    private var liveVideoTranslator: com.gemini.floatingcompanion.live.LiveVideoTranslator? = null
    private var subtitleOverlay: FloatingSubtitleOverlay? = null
    private var closeTargetView: FloatingCloseTargetView? = null

    private var isRecording = false
    private var isTranslatingVideo = false
    private var isVideoAppForeground = false
    private var isBubbleVisible = false
    private var isSubtitleVisible = false
    private var latestSubtitleText: String = ""
    var userDeclinedMediaProjection = false

    // Kullanıcı baloncuğu kapatma çarpısına sürüklediğinde (Explicit Dismiss)
    // arka plandaki klavye/video eventlerinin baloncuğu anında pırpır ettirip yeniden açmasını engellemek için
    // 30 dakikalık geçici susturma (snooze) mekanizması
    private var userDismissedUntilEpoch = 0L
    private val SNOOZE_DURATION_MS = 30 * 60 * 1000L // 30 dakika

    fun onInputFocusChanged(isFocused: Boolean) {
        if (!prefs.isAutoShowOnKeyboard) return

        scope.launch {
            if (isFocused) {
                // Kullanıcı manuel kapattıysa klavye açılsa bile otomatik açma
                if (System.currentTimeMillis() < userDismissedUntilEpoch) return@launch
                showBubble()
            } else {
                // If user is currently dictating or camera/radial menu/video translation is open, keep bubble alive
                if (!isRecording && !isTranslatingVideo && !isVideoAppForeground && cameraOverlay == null && radialMenuView == null) {
                    hideBubble()
                }
            }
        }
    }

    fun onVideoAppChanged(packageName: String, isVideo: Boolean) {
        isVideoAppForeground = isVideo
        scope.launch {
            if (isVideo) {
                // Kullanıcı manuel kapattıysa video uygulamasına geçilse bile otomatik açma
                if (System.currentTimeMillis() < userDismissedUntilEpoch) return@launch
                showBubbleForVideo()
            } else {
                if (isTranslatingVideo) {
                    // Videodan çıkılsa bile çeviri isteğe göre devam edebilir
                } else if (!isRecording && cameraOverlay == null && radialMenuView == null) {
                    hideBubble()
                }
            }
        }
    }

    fun showBubbleForVideo() {
        if (System.currentTimeMillis() < userDismissedUntilEpoch) return
        showBubble()
        if (!isTranslatingVideo && !isRecording) {
            bubbleView?.setState(BubbleState.VIDEO_DETECTED, "Çevir")
        }
    }

    fun showBubble(force: Boolean = false) {
        if (!force && System.currentTimeMillis() < userDismissedUntilEpoch) {
            Log.d(TAG, "showBubble atlandı: Kullanıcı tarafından kapatıldı (Snooze aktif)")
            return
        }
        // Manuel bildirimden veya zorunlu olarak çağrıldıysa snooze sıfırlanır
        if (force) {
            userDismissedUntilEpoch = 0L
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show bubble: overlay permission not granted")
            return
        }

        try {
            // Sürükleyip kapatma hedef görünümünü hazırla
            if (closeTargetView == null) {
                closeTargetView = FloatingCloseTargetView(context, windowManager)
                try {
                    windowManager.addView(closeTargetView, closeTargetView?.params)
                } catch (e: Exception) {
                    Log.w(TAG, "Close target view add exception", e)
                }
            }

            if (bubbleView == null) {
                bubbleView = FloatingBubbleView(
                    context = context,
                    windowManager = windowManager,
                    onBubbleClick = { handleBubbleClick() },
                    onBubbleLongClick = {
                        if (isTranslatingVideo) {
                            toggleSubtitleOverlay()
                        } else {
                            showRadialMenu()
                        }
                    }
                ).apply {
                    onDragStart = {
                        closeTargetView?.show()
                    }
                    onDragMove = { cx, cy ->
                        closeTargetView?.checkHover(cx, cy) ?: false
                    }
                    onDragEnd = { isOverCloseTarget ->
                        closeTargetView?.hide()
                        if (isOverCloseTarget) {
                            Log.d(TAG, "Baloncuk çarpıya sürüklendi ve başarıyla kapatıldı (30 dk snooze)")
                            userDismissedUntilEpoch = System.currentTimeMillis() + SNOOZE_DURATION_MS
                            Toast.makeText(context, context.getString(R.string.bubble_snoozed_toast), Toast.LENGTH_SHORT).show()
                            if (isTranslatingVideo) {
                                stopLiveVideoTranslation()
                            }
                            if (isRecording) {
                                stopLiveDictation()
                            }
                            hideBubble()
                        }
                    }
                }
            }

            val view = bubbleView ?: return

            // If already visible and attached, avoid re-triggering entrance animation (prevents flickering)
            if (isBubbleVisible && view.isAttachedToWindow && view.visibility == View.VISIBLE && view.alpha >= 0.9f) {
                return
            }

            if (!view.isAttachedToWindow) {
                try {
                    windowManager.addView(view, view.params)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Bubble already added to window manager", e)
                }
            }

            view.visibility = View.VISIBLE
            view.animate()?.cancel()
            view.alpha = 0f
            view.scaleX = 0.8f
            view.scaleY = 0.8f
            view.animate()
                ?.alpha(1f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(200)
                ?.start()

            isBubbleVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubble view to WindowManager", e)
        }
    }

    fun hideBubble() {
        if (!isBubbleVisible) return
        dismissRadialMenu()

        val view = bubbleView ?: return
        isBubbleVisible = false

        view.animate()?.cancel()
        view.animate()
            ?.alpha(0f)
            ?.scaleX(0.8f)
            ?.scaleY(0.8f)
            ?.setDuration(200)
            ?.withEndAction {
                view.visibility = View.GONE
            }
            ?.start()
    }

    private fun handleBubbleClick() {
        if (isTranslatingVideo) {
            stopLiveVideoTranslation()
        } else if (isVideoAppForeground) {
            startLiveVideoTranslation()
        } else {
            toggleLiveDictation()
        }
    }

    fun toggleLiveVideoTranslation() {
        if (isTranslatingVideo) {
            stopLiveVideoTranslation()
        } else {
            startLiveVideoTranslation()
        }
    }

    fun toggleSubtitleOverlay() {
        if (isSubtitleVisible) {
            hideSubtitleOverlay()
            Toast.makeText(context, "Altyazı gizlendi", Toast.LENGTH_SHORT).show()
        } else {
            showSubtitleOverlay()
            Toast.makeText(context, "Altyazı açıldı", Toast.LENGTH_SHORT).show()
        }
    }

    fun showSubtitleOverlay() {
        if (isSubtitleVisible) return
        if (subtitleOverlay == null) {
            subtitleOverlay = FloatingSubtitleOverlay(
                context = context,
                windowManager = windowManager,
                onToggleVoice = { isMuted ->
                    liveVideoTranslator?.setVoiceMuted(isMuted)
                },
                onClose = {
                    hideSubtitleOverlay()
                    Toast.makeText(context, "Altyazı gizlendi", Toast.LENGTH_SHORT).show()
                }
            )
        }
        subtitleOverlay?.let { overlay ->
            try {
                if (!overlay.isAttachedToWindow) {
                    windowManager.addView(overlay, overlay.params)
                }
                overlay.visibility = View.VISIBLE
                overlay.alpha = 0f
                overlay.animate()?.alpha(1f)?.setDuration(180)?.start()
                overlay.startLiveDotPulsing()
                if (latestSubtitleText.isNotBlank()) {
                    overlay.updateSubtitle(latestSubtitleText)
                }
                isSubtitleVisible = true
            } catch (e: Exception) {
                Log.e(TAG, "Error showing subtitle overlay", e)
            }
        }
    }

    fun hideSubtitleOverlay() {
        if (!isSubtitleVisible) return
        subtitleOverlay?.let { overlay ->
            try {
                overlay.stopLiveDotPulsing()
                overlay.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
                    try {
                        if (overlay.isAttachedToWindow) {
                            windowManager.removeView(overlay)
                        }
                    } catch (_: Exception) {}
                }?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing subtitle overlay", e)
            }
        }
        isSubtitleVisible = false
    }

    fun startLiveVideoTranslation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Mikrofon izni gerekli! Lütfen ana ekrandan izin verin.", Toast.LENGTH_LONG).show()
            return
        }

        // Android 10+ için Dahili Sistem Sesi İzni Kontrolü
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            !com.gemini.floatingcompanion.live.MediaProjectionHolder.isProjectionActive() &&
            !userDeclinedMediaProjection
        ) {
            try {
                val intent = Intent(context, com.gemini.floatingcompanion.live.MediaProjectionPermissionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "MediaProjectionPermissionActivity başlatılamadı, mikrofon fallback ile devam ediliyor", e)
            }
        }

        val apiKey = prefs.apiKey.ifBlank { com.gemini.floatingcompanion.data.GeminiApiKeyManager.getInstance().getActiveApiKey() }
        if (apiKey.isBlank()) {
            Toast.makeText(context, "Lütfen Gemini API anahtarınızı ayarlar ekranından girin.", Toast.LENGTH_LONG).show()
            bubbleView?.setState(BubbleState.ERROR, "API Key Yok")
            return
        }

        if (isRecording) {
            stopLiveDictation()
        }

        dismissRadialMenu()
        isTranslatingVideo = true
        bubbleView?.setState(BubbleState.TRANSLATING, "Çeviriyor...")

        // Foreground service'e mikrofon ve gerekiyorsa medya projeksiyonu bildir
        FloatingBubbleService.setMicrophoneActive(context, true)
        if (com.gemini.floatingcompanion.live.MediaProjectionHolder.isProjectionActive()) {
            FloatingBubbleService.setMediaProjectionActive(context, true)
        }

        // Altyazı kartını doğrudan görünür başlat ve kullanıcıya anlık geribildirim ver
        latestSubtitleText = ""
        showSubtitleOverlay()
        subtitleOverlay?.updateSubtitle("Video sesi dinleniyor, çeviri bekleniyor...")

        liveVideoTranslator = com.gemini.floatingcompanion.live.LiveVideoTranslator(context, scope).apply {
            onSubtitleUpdated = { turkishText ->
                latestSubtitleText = turkishText
                if (isSubtitleVisible) {
                    subtitleOverlay?.updateSubtitle(turkishText)
                }
            }
            onSessionStateChanged = { active ->
                if (!active && isTranslatingVideo) {
                    stopLiveVideoTranslation()
                }
            }
            onError = { err ->
                Log.e(TAG, "Live Video Translate error: $err")
                Toast.makeText(context, "Çeviri: $err", Toast.LENGTH_SHORT).show()
            }
            startTranslation()
        }
        Toast.makeText(context, "Canlı Çeviri başladı", Toast.LENGTH_SHORT).show()
    }

    fun stopLiveVideoTranslation() {
        if (!isTranslatingVideo) return
        isTranslatingVideo = false

        liveVideoTranslator?.destroy()
        liveVideoTranslator = null

        hideSubtitleOverlay()
        subtitleOverlay?.dismiss()
        subtitleOverlay = null
        latestSubtitleText = ""

        FloatingBubbleService.setMicrophoneActive(context, false)
        // MediaProjection aktifse foreground service tipini koru; böylece oturumlar arası MediaProjection kopmaz.
        if (!com.gemini.floatingcompanion.live.MediaProjectionHolder.isProjectionActive()) {
            FloatingBubbleService.setMediaProjectionActive(context, false)
        }

        if (isVideoAppForeground) {
            bubbleView?.setState(BubbleState.VIDEO_DETECTED, "Çevir")
        } else {
            bubbleView?.setState(BubbleState.IDLE)
        }
        Toast.makeText(context, "Canlı çeviri durduruldu.", Toast.LENGTH_SHORT).show()
    }

    private fun toggleLiveDictation() {
        if (isRecording) {
            stopLiveDictation()
        } else {
            startLiveDictation()
        }
    }

    private fun startLiveDictation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Mikrofon izni gerekli! Lütfen ana ekrandan izin verin.", Toast.LENGTH_LONG).show()
            return
        }

        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(context, "Lütfen Gemini API anahtarınızı ayarlar ekranından girin.", Toast.LENGTH_LONG).show()
            bubbleView?.setState(BubbleState.ERROR, "API Key Yok")
            return
        }

        dismissRadialMenu()
        isRecording = true
        // Instant active listening: no waiting or blocking on 'Bağlanıyor...'
        bubbleView?.setState(BubbleState.LISTENING, "Dinliyor...")

        // Update foreground service type to include microphone
        FloatingBubbleService.setMicrophoneActive(context, true)

        // Tell accessibility service we are starting streaming
        GeminiAccessibilityService.instance?.startStreamingSession()

        liveAudioClient = GeminiLiveAudioClient(
            apiKey = apiKey,
            model = prefs.liveModel,
            languageCode = prefs.language,
            scope = scope
        ).apply {
            onSessionStateChanged = { ready ->
                if (ready && isRecording) {
                    bubbleView?.setState(BubbleState.LISTENING, "Dinliyor...")
                }
            }

            onLiveTranscriptUpdated = { liveText ->
                // Doğrudan odaklı metin kutusuna anlık canlı akış yap
                GeminiAccessibilityService.instance?.insertText(liveText, isStreaming = true)
                // Baloncuk üzerinde konuştuklarımızı gösterme, baloncuk her zaman minimal dairesel kalsın!
            }

            onErrorOccurred = { err ->
                Log.w(TAG, "Live Audio Notice: $err")
                if (isRecording) {
                    bubbleView?.setState(BubbleState.LISTENING)
                }
            }

            connect()
        }

        audioRecorder = AudioRecorderManager(context).apply {
            startRecording(
                scope = scope,
                onAudioChunk = { chunk ->
                    liveAudioClient?.sendAudioChunk(chunk)
                },
                onError = { err ->
                    Log.e(TAG, "Audio Recorder Error: $err")
                    stopLiveDictation()
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun stopLiveDictation() {
        if (!isRecording) return
        isRecording = false

        // Kullanıcı durdur tuşuna bastığı an beklemeden anında normal duruma (IDLE) dön
        bubbleView?.setState(BubbleState.IDLE)

        val recordedWav = audioRecorder?.getRecordedWav() ?: ByteArray(0)
        audioRecorder?.stopRecording()
        audioRecorder = null

        val client = liveAudioClient
        val liveText = client?.getCurrentTranscript() ?: ""
        client?.finishSession()
        liveAudioClient = null

        // Mikrofon ön plan servisi tipini normale döndür
        FloatingBubbleService.setMicrophoneActive(context, false)

        scope.launch {
            if (liveText.isNotBlank()) {
                // KRİTİK: Canlı dikte zaten anlık olarak metin kutusuna yazıldı!
                // Burada tekrar insertText ÇAĞRILMAZ! Çağrılırsa aynı metin mükerrer olarak 2. kez yapışır.
                // Sadece emniyet için panoya (clipboard) kopyala ve akış oturumunu temizle.
                GeminiAccessibilityService.instance?.copyToClipboard(liveText)
                GeminiAccessibilityService.instance?.finishStreamingSession()
            } else if (recordedWav.size > 44) {
                // SIFIR GECİKMELİ YEDEK (Sadece canlı soketten hiç metin gelmediyse):
                val fallbackText = transcribeAudioWithRest(recordedWav)
                if (fallbackText.isNotBlank()) {
                    GeminiAccessibilityService.instance?.insertText(fallbackText, isStreaming = false)
                }
                GeminiAccessibilityService.instance?.finishStreamingSession()
            } else {
                GeminiAccessibilityService.instance?.finishStreamingSession()
            }
        }
    }

    /**
     * Kullanıcı WhatsApp veya Telegram'da mesajı gönderdiğinde transkript tamponunu sıfırlar.
     * Böylece mikrofon kapatılmadan peş peşe mesaj yazılabilir.
     */
    fun resetLiveTranscriptBuffer() {
        liveAudioClient?.resetTranscript()
        Log.d(TAG, "FloatingBubbleManager: resetLiveTranscriptBuffer called")
    }

    private suspend fun transcribeAudioWithRest(wavBytes: ByteArray): String = withContext(Dispatchers.IO) {
        if (wavBytes.size <= 44) return@withContext ""
        val keyManager = GeminiApiKeyManager.getInstance()
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val result = keyManager.executeWithFailover<String>(tag = "AudioRestFallback", maxRetries = 4) { activeKey: String ->
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$activeKey"

                val json = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", "Lütfen bu ses kaydını Türkçe olarak harfi harfine metne dök. Başka hiçbir açıklama veya yorum ekleme, sadece konuşulan metni yaz. Sessizlik ise hiçbir şey yazma.")
                                })
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "audio/wav")
                                        put("data", base64Audio)
                                    })
                                })
                            })
                        })
                    })
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody(mediaType))
                    .build()

                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(12, TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(request).execute()
                val respBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val root = JSONObject(respBody)
                    val candidates = root.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null && parts.length() > 0) {
                            val text = parts.getJSONObject(0).optString("text", "").trim()
                            if (text.isNotBlank() && text != "EMPTY") {
                                Log.d(TAG, "Audio fallback transcription succeeded: $text")
                                return@executeWithFailover Result.success(text)
                            }
                        }
                    }
                    Result.success("")
                } else {
                    Result.failure(Exception("HTTP ${response.code}: $respBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        result.getOrDefault("")
    }

    private fun showRadialMenu() {
        if (radialMenuView != null) return
        val currentBubble = bubbleView ?: return

        try {
            radialMenuView = RadialMenuView(
                context = context,
                bubbleX = currentBubble.params.x,
                bubbleY = currentBubble.params.y,
                bubbleWidth = currentBubble.width.coerceAtLeast(60),
                bubbleHeight = currentBubble.height.coerceAtLeast(60),
                onActionSelected = { action ->
                    dismissRadialMenu()
                    when (action) {
                        RadialMenuView.MenuAction.VOICE -> startLiveDictation()
                        RadialMenuView.MenuAction.OCR -> openCamera(CameraMode.OCR_STRUCTURED)
                        RadialMenuView.MenuAction.PDF -> openCamera(CameraMode.CAM_SCANNER_PDF)
                        RadialMenuView.MenuAction.TRANSLATE_VIDEO -> toggleLiveVideoTranslation()
                    }
                },
                onDismiss = { dismissRadialMenu() }
            )

            windowManager.addView(radialMenuView, radialMenuView?.params)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing radial menu", e)
        }
    }

    private fun dismissRadialMenu() {
        radialMenuView?.let { menu ->
            try {
                if (menu.isAttachedToWindow) {
                    windowManager.removeView(menu)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing radial menu", e)
            } finally {
                radialMenuView = null
            }
        }
    }

    private fun openCamera(mode: CameraMode) {
        if (cameraOverlay != null) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Kamera izni verilmemiş. Lütfen ana ekrandan izin verin.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            cameraOverlay = FloatingCameraOverlay(
                context = context,
                scope = scope,
                initialMode = mode,
                onClose = { closeCamera() }
            )
            windowManager.addView(cameraOverlay, cameraOverlay?.params)
        } catch (e: Exception) {
            Log.e(TAG, "Error showing camera overlay", e)
        }
    }

    private fun closeCamera() {
        cameraOverlay?.let { overlay ->
            try {
                if (overlay.isAttachedToWindow) {
                    windowManager.removeView(overlay)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing camera overlay", e)
            } finally {
                cameraOverlay = null
            }
        }
    }

    fun destroy() {
        stopLiveVideoTranslation()
        stopLiveDictation()
        dismissRadialMenu()
        closeCamera()
        hideBubble()
        closeTargetView?.let {
            try {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        closeTargetView = null
        scope.cancel()
        INSTANCE = null
    }

    companion object {
        private const val TAG = "FloatingBubbleManager"

        @Volatile
        private var INSTANCE: FloatingBubbleManager? = null

        fun getInstance(context: Context): FloatingBubbleManager? {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FloatingBubbleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
