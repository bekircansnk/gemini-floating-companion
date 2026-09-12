package com.gemini.floatingcompanion.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
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

    private val hideDebounceRunnable = Runnable {
        if (!isAnyInputFocused()) {
            FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "GeminiAccessibilityService connected")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
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

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handler.removeCallbacks(hideDebounceRunnable)
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    updateActiveNode(source)
                    FloatingBubbleManager.getInstance(this)?.onInputFocusChanged(true)
                } else {
                    checkAndScheduleHide()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkAndScheduleHide()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source
                if (source != null && isEditableNode(source)) {
                    updateActiveNode(source)
                }
            }
        }
    }

    private fun checkAndScheduleHide() {
        handler.removeCallbacks(hideDebounceRunnable)
        handler.postDelayed(hideDebounceRunnable, 300)
    }

    private fun isAnyInputFocused(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && isEditableNode(focused)) {
            updateActiveNode(focused)
            true
        } else {
            false
        }
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val className = node.className?.toString() ?: ""
        return className.contains("EditText", ignoreCase = true) ||
                className.contains("ComposeInput", ignoreCase = true) ||
                className.contains("TextInput", ignoreCase = true)
    }

    private fun updateActiveNode(node: AccessibilityNodeInfo) {
        activeNode = node
    }

    fun startStreamingSession() {
        isStreamingActive = true
        val targetNode = getTargetNode()
        baseTextBeforeStreaming = targetNode?.text?.toString() ?: ""
    }

    fun finishStreamingSession() {
        isStreamingActive = false
        baseTextBeforeStreaming = ""
    }

    fun insertText(text: String, isStreaming: Boolean) {
        val targetNode = getTargetNode()
        if (targetNode == null) {
            Log.w(TAG, "Cannot insert text: no target editable node found")
            return
        }

        try {
            val textToInsert: String
            if (isStreaming) {
                // If streaming, append incoming transcript piece cleanly to existing text
                textToInsert = if (baseTextBeforeStreaming.isEmpty()) {
                    text
                } else {
                    "$baseTextBeforeStreaming $text"
                }

                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToInsert)
                }
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!success) {
                    pasteViaClipboard(text)
                }
            } else {
                // For OCR tables, formatted markdown or final notes, paste via clipboard for maximum compatibility
                pasteViaClipboard(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting text into node", e)
            pasteViaClipboard(text)
        }
    }

    private fun pasteViaClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GeminiCompanion", text)
            clipboard.setPrimaryClip(clip)

            val targetNode = getTargetNode()
            targetNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "Error pasting via clipboard", e)
        }
    }

    private fun getTargetNode(): AccessibilityNodeInfo? {
        if (activeNode != null && activeNode?.refresh() == true && isEditableNode(activeNode!!)) {
            return activeNode
        }
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditableNode(focused)) {
            activeNode = focused
            return focused
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
