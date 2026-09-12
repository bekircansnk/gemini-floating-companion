package com.gemini.floatingcompanion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.gemini.floatingcompanion.data.GeminiApiKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GEMINI 3.8 FLASH BIRINCIL & 2.5 FLASH YEDEK GORSEL ANALIZORU
 *
 * - models/gemini-3.8-flash: Derin muhakeme ve yuksek hassasiyetli OCR.
 * - models/gemini-2.5-flash: Hizli yedek.
 * - 429 (Rate Limit), 404 veya 503 durumlarinda sirasiyla:
 *   gemini-3.8-flash -> gemini-2.5-flash -> gemini-3.5-flash-lite -> gemini-flash-latest
 *   zincirinde kesintisiz otomatik failover calisir.
 */
class GeminiVisionAnalyzer(
    private val apiKey: String? = null,
    private val model: String = "models/gemini-3.8-flash"
) {
    private val keyManager = GeminiApiKeyManager.getInstance()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeImageForStructuredText(
        imageFile: File,
        customPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val rawBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext Result.failure(Exception("Görsel dosyası açılamadı veya bozuk."))

            // EXIF yönünü düzelt
            val uprightBitmap = try {
                val exif = android.media.ExifInterface(imageFile.absolutePath)
                val orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                val degrees = when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees != 0f) {
                    val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
                    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                    if (rotated != rawBitmap) rawBitmap.recycle()
                    rotated
                } else {
                    rawBitmap
                }
            } catch (_: Exception) {
                rawBitmap
            }

            // Görseli optimize et (Gemini 3.5 Flash-Lite için 1600px idealdir)
            val scaledBitmap = if (uprightBitmap.width > 1600 || uprightBitmap.height > 1600) {
                val ratio = 1600f / maxOf(uprightBitmap.width, uprightBitmap.height)
                Bitmap.createScaledBitmap(
                    uprightBitmap,
                    (uprightBitmap.width * ratio).toInt(),
                    (uprightBitmap.height * ratio).toInt(),
                    true
                ).also {
                    if (it != uprightBitmap) uprightBitmap.recycle()
                }
            } else {
                uprightBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val promptText = customPrompt ?: """
                Sen uzman bir OCR, belge okuma ve veri yapılandırma asistanısın. Bu fotoğraftaki tüm metinleri, tabloları, listeleri ve el yazılarını en yüksek doğrulukla ve eksiksiz çıkar.
                
                KESİN KURALLAR:
                1. TABLOLAR VE ÇİZELGELER: Görselde herhangi bir tablo, ızgara, sütunlu veri, durum matrisi, proje planı veya karşılaştırma varsa, KESİNLİKLE eksiksiz bir Markdown tablosu (| Başlık 1 | Başlık 2 |) formatında yaz. Sütunları asla düz metne çevirme, satırları birleştirme. Tablodaki her sütunu ve satırı eksiksiz aktar.
                2. LİSTELER VE GÖREVLER: Yapılacak işler, maddeler veya notlar varsa, bunları temiz maddeli liste (- [ ] Görev veya - Madde) şeklinde yapılandır.
                3. VERİ KORUMA: Sayıları, kodları, tutarları, para birimlerini, tarihleri, saatleri ve özel isimleri asla değiştirme veya atlama.
                4. TEMİZ ÇIKTI: Yalnızca doğrudan yapıştırılmaya hazır metni döndür. 'İşte metin:' veya 'Görselde şunlar var:' gibi gereksiz açıklama cümleleri KESİNLİKLE ekleme.
            """.trimIndent()

            // Failover ile cagri yurut (Hem Key Rotasyonu hem Model Kademesi)
            keyManager.executeWithFailover(
                tag = "VisionOCR",
                initialCustomKey = apiKey,
                maxRetries = 4
            ) { keyToUse ->
                executeVisionRequest(
                    apiKey = keyToUse,
                    base64Image = base64Image,
                    promptText = promptText
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during vision analysis", e)
            Result.failure(e)
        }
    }

    private fun executeVisionRequest(
        apiKey: String,
        base64Image: String,
        promptText: String
    ): Result<String> {
        val cleanModelName = model.removePrefix("models/")
        // Model zinciri: gemini-3.8-flash birincil, ardindan 2.5-flash, ardindan 3.5-flash-lite
        val modelCandidates = listOf(
            cleanModelName,
            "gemini-3.8-flash",
            "gemini-2.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest"
        ).distinct()

        val requestJson = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                        put(JSONObject().apply {
                            val inlineData = JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            }
                            put("inlineData", inlineData)
                        })
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        var lastException: Exception = Exception("İstek başlatılamadı")

        for (m in modelCandidates) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.w(TAG, "Model $m denendi, hata: ${response.code} ($responseBody)")
                    // 429 (Rate limit), 404 (model bulunamadı), 503/500 (yoğunluk): Sıradaki modele geç
                    if (response.code in listOf(429, 404, 500, 502, 503, 504) && m != modelCandidates.last()) {
                        Log.i(TAG, "Model $m kotası/durumu (${response.code}), sıradaki yedek modele geçiliyor...")
                        continue
                    }
                    return Result.failure(Exception("Gemini API Hatası (${response.code}): $responseBody"))
                }

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val content = firstCandidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val structuredText = parts.getJSONObject(0).optString("text", "")
                        Log.i(TAG, "OCR Analizi Basarili! Model: $m, Metin uzunlugu: ${structuredText.length}")
                        return Result.success(structuredText.trim())
                    }
                }
                return Result.failure(Exception("Gemini yanıtından metin çıkarılamadı."))
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Model $m isteğinde istisna: ${e.message}")
            }
        }
        return Result.failure(lastException)
    }

    companion object {
        private const val TAG = "GeminiVisionAnalyzer"
    }
}
