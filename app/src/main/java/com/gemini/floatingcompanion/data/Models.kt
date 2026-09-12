package com.gemini.floatingcompanion.data

data class AppPreferences(
    val apiKey: String = "",
    val liveModel: String = "models/gemini-3.5-transcribe-live",
    val visionModel: String = "models/gemini-3.8-flash",
    val language: String = "tr",
    val isAutoShowOnKeyboard: Boolean = true,
    val isHapticEnabled: Boolean = true
)

enum class BubbleState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR,
    VIDEO_DETECTED,
    TRANSLATING
}

enum class CameraMode {
    OCR_STRUCTURED,
    CAM_SCANNER_PDF
}

data class TranscriptionPiece(
    val text: String,
    val isFinal: Boolean
)
