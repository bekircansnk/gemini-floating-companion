package com.gemini.floatingcompanion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
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

class GeminiVisionAnalyzer(
    private val apiKey: String,
    private val model: String = "models/gemini-2.5-flash"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeImageForStructuredText(
        imageFile: File,
        customPrompt: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini API anahtarı boş olamaz."))
        }

        try {
            // Compress and scale bitmap if needed to stay within optimal size
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext Result.failure(Exception("Görsel okunamadı."))

            val scaledBitmap = if (bitmap.width > 1600 || bitmap.height > 1600) {
                val ratio = 1600f / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt(),
                    (bitmap.height * ratio).toInt(),
                    true
                )
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val imageBytes = outputStream.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val promptText = customPrompt ?: """
                Sen uzman bir OCR ve veri yapılandırma asistanısın. Bu fotoğraftaki tüm metinleri, tabloları, listeleri ve el yazılarını en yüksek doğrulukla çıkar.
                
                Kurallar:
                1. Eğer bir tablo varsa, kesinlikle ve sadece temiz bir Markdown tablosu (| Sütun 1 | Sütun 2 |) formatında yaz.
                2. Eğer yapılacak işler, görevler veya notlar varsa, bunları temiz maddeli yapılacaklar listesi (- [ ] Görev) veya madde imleri (- Madde) şeklinde yapılandır.
                3. Sayıları, kodları, tutarları ve özel isimleri asla değiştirme veya atlama.
                4. Yalnızca doğrudan yapıştırılmaya hazır yapılandırılmış metni döndür. 'İşte metin:' gibi gereksiz başlangıç veya bitiş cümleleri ekleme.
            """.trimIndent()

            val cleanModelName = model.removePrefix("models/")
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModelName:generateContent?key=$apiKey"

            val requestJson = JSONObject().apply {
                val contents = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val parts = JSONArray().apply {
                            // Text prompt
                            put(JSONObject().apply {
                                put("text", promptText)
                            })
                            // Inline image data
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
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini Vision Error: ${response.code} - $responseBody")
                return@withContext Result.failure(Exception("Gemini API Hatası (${response.code}): $responseBody"))
            }

            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val structuredText = parts.getJSONObject(0).optString("text", "")
                    return@withContext Result.success(structuredText.trim())
                }
            }

            Result.failure(Exception("Gemini yanıtından metin çıkarılamadı."))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during vision analysis", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GeminiVisionAnalyzer"
    }
}
