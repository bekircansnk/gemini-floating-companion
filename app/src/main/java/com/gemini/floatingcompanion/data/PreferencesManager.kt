package com.gemini.floatingcompanion.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var liveModel: String
        get() = prefs.getString(KEY_LIVE_MODEL, "models/gemini-3.5-transcribe-live") ?: "models/gemini-3.5-transcribe-live"
        set(value) = prefs.edit().putString(KEY_LIVE_MODEL, value).apply()

    var visionModel: String
        get() = prefs.getString(KEY_VISION_MODEL, "models/gemini-3.8-flash") ?: "models/gemini-3.8-flash"
        set(value) = prefs.edit().putString(KEY_VISION_MODEL, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "tr") ?: "tr"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var isAutoShowOnKeyboard: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHOW, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SHOW, value).apply()

    var isHapticEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC, value).apply()

    companion object {
        private const val PREFS_NAME = "gemini_companion_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LIVE_MODEL = "live_model"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_AUTO_SHOW = "auto_show"
        private const val KEY_HAPTIC = "haptic"

        @Volatile
        private var INSTANCE: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
