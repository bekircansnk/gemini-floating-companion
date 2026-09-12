package com.gemini.floatingcompanion.data

import android.util.Log
import com.gemini.floatingcompanion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MAESTRO MERKEZİ GEMINI API VAULT & OTOMATİK FAILOVER YÖNETİCİSİ
 *
 * Maestro ve global-connections.md anayasasındaki 6 anahtarlı rotasyonlu API havuzunu yönetir.
 * - HTTP 429 (Rate Limit), 403 (Quota), 503 (Unavailable) veya soket hatalarında kesintisiz
 *   bir sonraki sağlıklı anahtara otomatik geçiş (Failover) sağlar.
 * - Cooldown mekanizmasıyla hata alan anahtarları geçici süre (90 sn) dinlendirir.
 * - Thread-safe, coroutine dostu ve canlı durum takiplidir.
 */
data class GeminiKeyInfo(
    val id: String,
    val key: String,
    val name: String,
    val type: String, // "express" | "standard"
    val priority: Int,
    var isHealthy: Boolean = true,
    var failureReason: String? = null,
    var cooldownUntilMs: Long = 0L,
    var successCount: Int = 0,
    var failureCount: Int = 0
)

class GeminiApiKeyManager private constructor() {

    private val keyPool: List<GeminiKeyInfo> = listOfNotNull(
        // Key 2 (Birincil Test Edilmiş Express - Live Audio & Vision tam uyumlu)
        if (BuildConfig.GEMINI_KEY_2.isNotBlank()) {
            GeminiKeyInfo(
                id = "key_2",
                key = BuildConfig.GEMINI_KEY_2,
                name = "Express Key 2 (Birincil Live)",
                type = "express",
                priority = 1
            )
        } else null,
        // Key 4 (Test Edilmiş AI Studio Standart - 3.8 Flash & 2.5 Flash)
        if (BuildConfig.GEMINI_KEY_4.isNotBlank()) {
            GeminiKeyInfo(
                id = "key_4",
                key = BuildConfig.GEMINI_KEY_4,
                name = "AI Studio Key 4 (Yedek 1)",
                type = "standard",
                priority = 2
            )
        } else null,
        // Key 5 (Test Edilmiş AI Studio Standart - 3.8 Flash & 2.5 Flash)
        if (BuildConfig.GEMINI_KEY_5.isNotBlank()) {
            GeminiKeyInfo(
                id = "key_5",
                key = BuildConfig.GEMINI_KEY_5,
                name = "AI Studio Key 5 (Yedek 2)",
                type = "standard",
                priority = 3
            )
        } else null,
        // Key 1 (Express - gemini-flash-latest / 2.0 Flash)
        if (BuildConfig.GEMINI_KEY_1.isNotBlank()) {
            GeminiKeyInfo(
                id = "key_1",
                key = BuildConfig.GEMINI_KEY_1,
                name = "Express Key 1 (Yedek 3)",
                type = "express",
                priority = 4
            )
        } else null,
        // Key 3 (AI Studio Standart - gemini-flash-latest)
        if (BuildConfig.GEMINI_KEY_3.isNotBlank()) {
            GeminiKeyInfo(
                id = "key_3",
                key = BuildConfig.GEMINI_KEY_3,
                name = "AI Studio Key 3 (Yedek 4)",
                type = "standard",
                priority = 5
            )
        } else null
    )

    private val currentIndex = AtomicInteger(0)
    private val statsMap = ConcurrentHashMap<String, Int>()

    fun getActiveKeyInfo(): GeminiKeyInfo {
        cleanupCooldowns()
        val healthy = keyPool.filter { it.isHealthy }
        val poolToUse = if (healthy.isNotEmpty()) healthy else {
            // Tüm anahtarlar cooldown'da ise cooldown sürelerini sıfırla ve baştan başla
            Log.w(TAG, "Tüm API anahtarları beklemede, cooldown sıfırlanıyor...")
            keyPool.forEach { it.isHealthy = true; it.cooldownUntilMs = 0L }
            keyPool
        }
        val idx = currentIndex.get() % poolToUse.size
        return poolToUse[idx.coerceAtLeast(0)]
    }

    fun getActiveApiKey(): String {
        return getActiveKeyInfo().key
    }

    fun getEffectiveApiKey(customKey: String?): String {
        return if (!customKey.isNullOrBlank()) {
            customKey.trim()
        } else {
            getActiveApiKey()
        }
    }

    fun rotateToNextKey(reason: String = "Manual / Failover"): GeminiKeyInfo {
        val old = getActiveKeyInfo()
        old.isHealthy = false
        old.failureReason = reason
        old.cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_DURATION_MS
        old.failureCount++

        val newIdx = currentIndex.incrementAndGet()
        val nextKey = getActiveKeyInfo()
        Log.i(TAG, "API Key rotasyonu yapıldı. Sebep: $reason. Yeni Key: ${nextKey.name} (${maskKey(nextKey.key)})")
        return nextKey
    }

    fun reportSuccess(key: String) {
        val info = keyPool.find { it.key == key } ?: return
        info.isHealthy = true
        info.failureReason = null
        info.cooldownUntilMs = 0L
        info.successCount++
        statsMap.compute(info.id) { _, count -> (count ?: 0) + 1 }
    }

    fun reportFailure(key: String, code: Int, errorMsg: String) {
        val info = keyPool.find { it.key == key } ?: return
        info.failureCount++
        val isRateLimit = code == 429 || errorMsg.contains("429", ignoreCase = true) ||
                errorMsg.contains("quota", ignoreCase = true) ||
                errorMsg.contains("resource_exhausted", ignoreCase = true)
        val isAuthError = code == 403 || code == 401 || errorMsg.contains("Permission denied", ignoreCase = true)

        val reason = when {
            isRateLimit -> "429 Rate Limit Aşımı"
            isAuthError -> "403 İzin/Yetki Hatası ($code)"
            code == 503 -> "503 Servis Geçici Olarak Kapalı"
            else -> "Hata ($code): ${errorMsg.take(40)}" // SCOPE-OK: UI hata mesaji gosterimi
        }

        info.isHealthy = false
        info.failureReason = reason
        info.cooldownUntilMs = System.currentTimeMillis() + if (isRateLimit) COOLDOWN_DURATION_MS else COOLDOWN_DURATION_MS * 2

        Log.w(TAG, "API Key hatası bildirildi: ${info.name}, Kod: $code, Sebep: $reason")
        // Otomatik sonraki anahtara geç
        currentIndex.incrementAndGet()
    }

    /**
     * Otomatik Failover / Yeniden Deneme Sarmalayıcısı (Wrap & Retry)
     * İşlem 429 veya sunucu hatası verirse, sıradaki key'e geçerek işlemi tekrar dener.
     */
    suspend fun <T> executeWithFailover(
        tag: String = "GeminiOp",
        initialCustomKey: String? = null,
        maxRetries: Int = 3,
        block: suspend (apiKey: String) -> Result<T>
    ): Result<T> = withContext(Dispatchers.IO) {
        var lastError: Throwable = Exception("Bilinmeyen hata")

        for (attempt in 1..maxRetries) {
            val keyInfo = getActiveKeyInfo()
            val currentKey = if (attempt == 1 && !initialCustomKey.isNullOrBlank()) {
                initialCustomKey.trim()
            } else {
                keyInfo.key
            }

            val keyName = if (attempt == 1 && !initialCustomKey.isNullOrBlank()) "Kullanıcı / Özel Key" else keyInfo.name
            Log.d(TAG, "[$tag] Deneme $attempt/$maxRetries: $keyName (${maskKey(currentKey)})")
            val result = block(currentKey)

            if (result.isSuccess) {
                reportSuccess(currentKey)
                return@withContext result
            } else {
                val exception = result.exceptionOrNull() ?: Exception("İşlem başarısız")
                lastError = exception
                val msg = exception.localizedMessage ?: exception.message ?: ""

                val isRetryable = msg.contains("429") ||
                        msg.contains("quota", ignoreCase = true) ||
                        msg.contains("resource_exhausted", ignoreCase = true) ||
                        msg.contains("503") ||
                        msg.contains("403") ||
                        msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("reset", ignoreCase = true)

                if (isRetryable && attempt < maxRetries) {
                    reportFailure(currentKey, if (msg.contains("429")) 429 else 500, msg)
                    kotlinx.coroutines.delay(400L * attempt)
                } else {
                    break
                }
            }
        }
        Result.failure(lastError)
    }

    fun getAllKeysStatus(): List<GeminiKeyInfo> {
        cleanupCooldowns()
        return keyPool.toList()
    }

    private fun cleanupCooldowns() {
        val now = System.currentTimeMillis()
        keyPool.forEach { key ->
            if (!key.isHealthy && key.cooldownUntilMs > 0L && now >= key.cooldownUntilMs) {
                key.isHealthy = true
                key.failureReason = null
                key.cooldownUntilMs = 0L
                Log.d(TAG, "⏳ Key cooldown süresi doldu, tekrar havuza alındı: ${key.name}")
            }
        }
    }

    companion object {
        private const val TAG = "GeminiApiKeyManager"
        private const val COOLDOWN_DURATION_MS = 90_000L // 90 saniye cooldown

        @Volatile
        private var INSTANCE: GeminiApiKeyManager? = null

        fun getInstance(): GeminiApiKeyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeminiApiKeyManager().also { INSTANCE = it }
            }
        }

        fun maskKey(key: String): String {
            return if (key.length > 10) {
                "${key.take(7)}...${key.takeLast(4)}" // SCOPE-OK: API anahtar maskeleme
            } else {
                "***"
            }
        }
    }
}
