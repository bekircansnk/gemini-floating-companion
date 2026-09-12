package com.gemini.floatingcompanion.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.gemini.floatingcompanion.data.BubbleState
import com.gemini.floatingcompanion.data.CameraMode
import com.gemini.floatingcompanion.data.PreferencesManager
import com.gemini.floatingcompanion.live.AudioRecorderManager
import com.gemini.floatingcompanion.live.GeminiLiveAudioClient
import com.gemini.floatingcompanion.service.GeminiAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private var isRecording = false
    private var isBubbleVisible = false

    fun onInputFocusChanged(isFocused: Boolean) {
        if (!prefs.isAutoShowOnKeyboard) return

        scope.launch {
            if (isFocused) {
                showBubble()
            } else {
                // If user is currently dictating or camera is open, keep bubble alive
                if (!isRecording && cameraOverlay == null && radialMenuView == null) {
                    hideBubble()
                }
            }
        }
    }

    fun showBubble() {
        if (isBubbleVisible) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show bubble: overlay permission not granted")
            return
        }

        try {
            if (bubbleView == null) {
                bubbleView = FloatingBubbleView(
                    context = context,
                    windowManager = windowManager,
                    onBubbleClick = { toggleLiveDictation() },
                    onBubbleLongClick = { showRadialMenu() }
                )
            }

            if (bubbleView?.isAttachedToWindow == false) {
                windowManager.addView(bubbleView, bubbleView?.params)
                isBubbleVisible = true

                // Smooth fade/scale entrance
                bubbleView?.alpha = 0f
                bubbleView?.scaleX = 0.8f
                bubbleView?.scaleY = 0.8f
                bubbleView?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(200)
                    ?.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bubble view to WindowManager", e)
        }
    }

    fun hideBubble() {
        if (!isBubbleVisible) return
        dismissRadialMenu()

        bubbleView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.8f)
            ?.scaleY(0.8f)
            ?.setDuration(200)
            ?.withEndAction {
                try {
                    if (bubbleView?.isAttachedToWindow == true) {
                        windowManager.removeView(bubbleView)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing bubble view", e)
                } finally {
                    isBubbleVisible = false
                }
            }
            ?.start()
    }

    private fun toggleLiveDictation() {
        if (isRecording) {
            stopLiveDictation()
        } else {
            startLiveDictation()
        }
    }

    private fun startLiveDictation() {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            Toast.makeText(context, "Lütfen Gemini API anahtarınızı ayarlar ekranından girin.", Toast.LENGTH_LONG).show()
            bubbleView?.setState(BubbleState.ERROR, "API Key Yok")
            return
        }

        dismissRadialMenu()
        isRecording = true
        bubbleView?.setState(BubbleState.LISTENING, "Bağlanıyor...")

        // Tell accessibility service we are starting streaming
        GeminiAccessibilityService.instance?.startStreamingSession()

        liveAudioClient = GeminiLiveAudioClient(
            apiKey = apiKey,
            model = prefs.liveModel,
            languageCode = prefs.language,
            scope = scope
        ).apply {
            onSessionStateChanged = { ready ->
                if (ready) {
                    bubbleView?.setState(BubbleState.LISTENING, "Dinliyor...")
                }
            }

            onLiveTranscriptUpdated = { liveText ->
                // Directly write text in real time to the focused EditText!
                GeminiAccessibilityService.instance?.insertText(liveText, isStreaming = true)
            }

            onErrorOccurred = { err ->
                Log.e(TAG, "Live Audio Error: $err")
                stopLiveDictation()
                bubbleView?.setState(BubbleState.ERROR, "Hata")
                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
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

        bubbleView?.setState(BubbleState.PROCESSING, "Tamamlanıyor...")

        audioRecorder?.stopRecording()
        audioRecorder = null

        liveAudioClient?.finishSession()
        liveAudioClient = null

        GeminiAccessibilityService.instance?.finishStreamingSession()

        scope.launch {
            kotlinx.coroutines.delay(500)
            bubbleView?.setState(BubbleState.IDLE)
        }
    }

    private fun showRadialMenu() {
        if (radialMenuView != null) return
        val currentBubble = bubbleView ?: return

        try {
            radialMenuView = RadialMenuView(
                context = context,
                onActionSelected = { action ->
                    dismissRadialMenu()
                    when (action) {
                        RadialMenuView.MenuAction.VOICE -> startLiveDictation()
                        RadialMenuView.MenuAction.OCR -> openCamera(CameraMode.OCR_STRUCTURED)
                        RadialMenuView.MenuAction.PDF -> openCamera(CameraMode.CAM_SCANNER_PDF)
                    }
                },
                onDismiss = { dismissRadialMenu() }
            ).apply {
                // Position above or below bubble
                params.x = (currentBubble.params.x - 40).coerceAtLeast(16)
                params.y = (currentBubble.params.y - 140).coerceAtLeast(80)
            }

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
        try {
            cameraOverlay = FloatingCameraOverlay(
                context = context,
                scope = scope,
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
        stopLiveDictation()
        dismissRadialMenu()
        closeCamera()
        hideBubble()
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
