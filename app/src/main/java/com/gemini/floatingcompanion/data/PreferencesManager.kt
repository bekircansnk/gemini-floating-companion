package com.gemini.floatingcompanion.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyManager = GeminiApiKeyManager.getInstance()

    init {
        val savedKeys = customApiKeys
        if (savedKeys.isNotEmpty()) {
            keyManager.updateUserKeys(savedKeys)
        }
    }

    var customApiKey: String
        get() {
            val list = customApiKeys
            return if (list.isNotEmpty()) list.first() else prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
        }
        set(value) {
            val trimmed = value.trim()
            prefs.edit().putString(KEY_CUSTOM_API_KEY, trimmed).apply()
            if (trimmed.isNotBlank()) {
                val current = customApiKeys.toMutableList()
                if (!current.contains(trimmed)) {
                    current.add(0, trimmed)
                    saveCustomApiKeys(current)
                }
            }
        }

    var customApiKeys: List<String>
        get() {
            val raw = prefs.getString(KEY_CUSTOM_API_KEYS_JSON, null)
            if (raw.isNullOrBlank()) {
                val single = prefs.getString(KEY_CUSTOM_API_KEY, "") ?: ""
                return if (single.isNotBlank()) listOf(single) else emptyList()
            }
            return try {
                val jsonArr = org.json.JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArr.length()) {
                    val key = jsonArr.optString(i, "").trim()
                    if (key.isNotBlank()) {
                        list.add(key)
                    }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
        set(value) {
            saveCustomApiKeys(value)
        }

    fun saveCustomApiKeys(keys: List<String>) {
        val cleanList = keys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val jsonArr = org.json.JSONArray()
        cleanList.forEach { jsonArr.put(it) }
        prefs.edit()
            .putString(KEY_CUSTOM_API_KEYS_JSON, jsonArr.toString())
            .putString(KEY_CUSTOM_API_KEY, cleanList.firstOrNull() ?: "")
            .apply()
        keyManager.updateUserKeys(cleanList)
    }

    var useVaultPool: Boolean
        get() = prefs.getBoolean(KEY_USE_VAULT_POOL, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_VAULT_POOL, value).apply()

    var apiKey: String
        get() = keyManager.getActiveApiKey()
        set(value) {
            customApiKey = value
        }

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
        private const val KEY_CUSTOM_API_KEY = "custom_api_key" // SCOPE-OK: SharedPreferences anahtar adi
        private const val KEY_CUSTOM_API_KEYS_JSON = "custom_api_keys_json"
        private const val KEY_USE_VAULT_POOL = "use_vault_pool"
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
