package com.gemini.floatingcompanion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.gemini.floatingcompanion.overlay.FloatingBubbleManager

class GeminiAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var activeNode: AccessibilityNodeInfo? = null
    private var baseTextBeforeStreaming = ""
    private var isStreamingActive = false

    private var lastPastedStreamingText = ""

    private val hideDebounceRunnable = Runnable {
        if (!isAnyInputFocused()) {
            FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UncaughtException yakalandı - Servis çökmesi engelleniyor", throwable)
            // Crash loop yerine sadece logla ve servisi canlı tut
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "GeminiAccessibilityService connected")
        FloatingBubbleService.start(this)

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    handler.removeCallbacks(hideDebounceRunnable)
                    val source = event.source
                    if (source != null && isEditableNode(source)) {
                        updateActiveNode(source)
                        FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                    } else if (isAnyInputFocused()) {
                        FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                    } else {
                        checkAndScheduleHide()
                    }
                }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handler.removeCallbacks(hideDebounceRunnable)
                val source = event.source

                // 1. WhatsApp / Telegram Gönder Butonuna tıklandı mı kontrol et
                val desc = event.contentDescription?.toString()?.lowercase(java.util.Locale.ROOT) ?: ""
                val text = event.text.joinToString(" ").lowercase(java.util.Locale.ROOT)
                val resName = source?.viewIdResourceName?.lowercase(java.util.Locale.ROOT) ?: ""

                val isSendButton = desc.contains("gönder") || desc.contains("send") ||
                        text.contains("gönder") || text.contains("send") ||
                        resName.contains("send")

                if (isSendButton) {
                    Log.d(TAG, "Gonder butonuna tiklandi! Transkript hafizasi sifirlaniyor...")
                    lastPastedStreamingText = ""
                    baseTextBeforeStreaming = ""
                    FloatingBubbleManager.getInstance(this)?.resetLiveTranscriptBuffer()
                }

                if (source != null && isEditableNode(source)) {
                    updateActiveNode(source)
                    FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                } else {
                    // Clicking an input layout or container may take a short moment to focus the inner EditText or open keyboard
                    handler.postDelayed({
                        if (isAnyInputFocused()) {
                            FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                        } else {
                            checkAndScheduleHide()
                        }
                    }, 200)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handler.removeCallbacks(hideDebounceRunnable)
                val currentPkg = event.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString() ?: ""
                if (currentPkg.isNotBlank()) {
                    val isVideoApp = isVideoApplication(currentPkg)
                    FloatingBubbleManager.getInstance(this)?.onVideoAppChanged(currentPkg, isVideoApp)

                    if (isVideoApp) {
                        FloatingBubbleManager.getInstance(this)?.showBubbleForVideo()
                    } else if (isAnyInputFocused()) {
                        FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                    } else {
                        checkAndScheduleHide()
                    }
                } else {
                    // event.packageName null ise aktif pencereye veya odak durumuna bak
                    if (isAnyInputFocused()) {
                        FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    updateActiveNode(source)
                    val currentText = source.text?.toString()?.trim() ?: ""
                    if (isStreamingActive && lastPastedStreamingText.isNotEmpty() && (currentText.isEmpty() || isKnownPlaceholder(currentText))) {
                        Log.d(TAG, "Metin kutusu boşaldı (Mesaj gönderildi)! Transkript sıfırlanıyor...")
                        lastPastedStreamingText = ""
                        baseTextBeforeStreaming = ""
                        FloatingBubbleManager.getInstance(this)?.resetLiveTranscriptBuffer()
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Safe error in onAccessibilityEvent", e)
    }
}

    private fun checkAndScheduleHide() {
        handler.removeCallbacks(hideDebounceRunnable)
        handler.postDelayed(hideDebounceRunnable, 1600)
    }

    private fun isAnyInputFocused(): Boolean {
        // 1. Check cached active node if still valid and focused
        try {
            activeNode?.let { node ->
                if (node.refresh() && node.isFocused && isEditableNode(node)) {
                    return true
                }
            }
        } catch (_: Exception) {}

        // 2. Check root in active window
        try {
            rootInActiveWindow?.let { root ->
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableNode(focused)) {
                    updateActiveNode(focused)
                    return true
                }
            }
        } catch (_: Exception) {}

        // 3. Check all interactive windows (including soft keyboard / IME window detection)
        try {
            val windowList = windows
            for (window in windowList) {
                // If soft keyboard (Gboard, SwiftKey, MIUI keyboard etc.) is displayed, keep the bubble visible
                if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableNode(focused)) {
                    updateActiveNode(focused)
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun isVideoApplication(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val videoPackages = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "tv.twitch.android.app"
        )
        if (videoPackages.contains(pkg) || pkg.contains("youtube", ignoreCase = true) || pkg.contains("videoplayer", ignoreCase = true)) {
            return true
        }

        val browserPackages = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.opera.browser"
        )
        if (browserPackages.contains(pkg)) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager?.isMusicActive == true) {
                return true
            }
        }
        return false
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
                className.contains("ComposeInput", ignoreCase = true) ||
                className.contains("TextInput", ignoreCase = true) ||
                className.contains("SearchView", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true)
    }

    private fun updateActiveNode(node: AccessibilityNodeInfo) {
        activeNode = node
    }

    fun startStreamingSession() {
        isStreamingActive = true
        lastPastedStreamingText = ""
        val targetNode = getTargetNode()
        val rawText = targetNode?.text?.toString()?.trim() ?: ""
        val hintText = targetNode?.hintText?.toString()?.trim() ?: ""

        // WhatsApp, Telegram veya arama çubuklarındaki 'Mesaj', 'Ara' gibi placeholder metinleri filtrele
        val isPlaceholder = rawText.isEmpty() ||
                rawText.equals(hintText, ignoreCase = true) ||
                isKnownPlaceholder(rawText)

        baseTextBeforeStreaming = if (isPlaceholder) "" else rawText
        Log.d(TAG, "startStreamingSession: baseText='${baseTextBeforeStreaming}' (raw='$rawText', hint='$hintText')")
    }

    private fun isKnownPlaceholder(text: String): Boolean {
        val lower = text.lowercase(java.util.Locale.ROOT).trim()
        val placeholders = setOf(
            "mesaj", "message", "bir mesaj yazın", "type a message",
            "ara", "search", "metin girin", "sohbete başla", "yazın...",
            "mesaj yazın", "enter a message", "write a message", "yorum ekle",
            "bir mesaj yaz", "mesaj gönder", "send a message",
            "meta ai'a sor veya ara", "meta ai'ye sor veya ara", "meta ai",
            "soru sorun veya arayın", "bir şeyler yazın", "mesaj...", "ara..."
        )
        if (placeholders.contains(lower)) return true
        if (lower.startsWith("mesaj") || lower.startsWith("ara") || lower.contains("meta ai") || lower.startsWith("search")) {
            // Sadece tek kelime veya çok kısa placeholder kalıbıysa
            if (lower.length <= 30 && (lower.contains("ara") || lower.contains("sor") || lower.contains("mesaj") || lower.contains("type"))) {
                return true
            }
        }
        return false
    }

    fun finishStreamingSession() {
        isStreamingActive = false
        baseTextBeforeStreaming = ""
        lastPastedStreamingText = ""
    }

    fun insertText(text: String, isStreaming: Boolean): Boolean {
        var targetNode = getTargetNode()
        if (targetNode == null && !isStreaming) {
            try { Thread.sleep(200) } catch (_: Exception) {}
            targetNode = getTargetNode()
        }
        if (targetNode == null) {
            Log.w(TAG, "No target node found, saving text to clipboard as fallback")
            copyToClipboard(text)
            return false
        }

        try {
            if (isStreaming) {
                val currentBoxText = targetNode.text?.toString()?.trim() ?: ""
                val isBoxEmptyOrPlaceholder = currentBoxText.isEmpty() || isKnownPlaceholder(currentBoxText)

                if (lastPastedStreamingText.isNotEmpty() && isBoxEmptyOrPlaceholder) {
                    Log.d(TAG, "Hedef kutu bosalmis (Mesaj gonderilmis)! Eski transkript kesiliyor...")
                    lastPastedStreamingText = ""
                    baseTextBeforeStreaming = ""
                    FloatingBubbleManager.getInstance(this)?.resetLiveTranscriptBuffer()
                }

                val textToInsert = if (baseTextBeforeStreaming.isEmpty()) {
                    text
                } else {
                    "$baseTextBeforeStreaming $text"
                }

                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInsert)
                }
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (success) {
                    lastPastedStreamingText = text
                } else {
                    val delta = if (text.startsWith(lastPastedStreamingText)) {
                        text.substring(lastPastedStreamingText.length)
                    } else {
                        text
                    }
                    if (delta.isNotEmpty()) {
                        pasteViaClipboard(delta)
                        lastPastedStreamingText = text
                    }
                }
            } else {
                // OCR tabloları ve markdown icin panoya kopyala ve yapistir
                copyToClipboard(text)
                try {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                } catch (_: Exception) {}
                val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!pasteSuccess) {
                    val currentText = targetNode.text?.toString()?.trim() ?: ""
                    val combined = if (currentText.isBlank() || isKnownPlaceholder(currentText)) {
                        text
                    } else {
                        "$currentText\n\n$text"
                    }
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
                    }
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                return true
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting text into node", e)
            copyToClipboard(text)
            return false
        }
    }

    fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Gemini Companion", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to clipboard", e)
        }
    }

    private fun pasteViaClipboard(text: String) {
        try {
            copyToClipboard(text)
            val targetNode = getTargetNode()
            targetNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "Error pasting via clipboard", e)
        }
    }

    private fun getTargetNode(): AccessibilityNodeInfo? {
        try {
            if (activeNode != null && activeNode?.refresh() == true && isEditableNode(activeNode!!)) {
                return activeNode
            }
        } catch (_: Exception) {}

        try {
            val root = rootInActiveWindow
            val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && isEditableNode(focused)) {
                activeNode = focused
                return focused
            }
        } catch (_: Exception) {}

        try {
            for (window in windows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableNode(focused)) {
                    activeNode = focused
                    return focused
                }
            }
        } catch (_: Exception) {}

        // İkincil dayanıklılık: FOCUS_INPUT bayrağı düşmüş olsa bile penceredeki ilk düzenlenebilir metin kutusunu bul
        try {
            val root = rootInActiveWindow
            val editable = findFirstEditable(root)
            if (editable != null) {
                activeNode = editable
                return editable
            }
        } catch (_: Exception) {}

        try {
            for (window in windows) {
                val root = window.root ?: continue
                val editable = findFirstEditable(root)
                if (editable != null) {
                    activeNode = editable
                    return editable
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (isEditableNode(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        Log.w(TAG, "GeminiAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    companion object {
        private const val TAG = "GeminiAccessibility"

        @Volatile
        var instance: GeminiAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }
}
