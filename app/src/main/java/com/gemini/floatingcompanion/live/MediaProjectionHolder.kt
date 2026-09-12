package com.gemini.floatingcompanion.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.gemini.floatingcompanion.service.FloatingBubbleService

/**
 * Android 10+ (API 29+) MediaProjection Yönetici ve Yaşam Döngüsü Tutucusu.
 *
 * Dahili sistem / medya sesi yakalama (AudioPlaybackCapture) için gereken
 * MediaProjection nesnesini güvenle saklar, yeniden kullanımını yönetir ve
 * Android 14+ FGS gereksinimleriyle uyumlu olarak başlatır.
 */
object MediaProjectionHolder {
    private const val TAG = "MediaProjectionHolder"

    @Volatile
    private var activeMediaProjection: MediaProjection? = null

    @Volatile
    private var pendingResultCode: Int = 0

    @Volatile
    private var pendingResultData: Intent? = null

    fun setPendingResult(resultCode: Int, data: Intent) {
        pendingResultCode = resultCode
        pendingResultData = data
    }

    fun hasPendingResult(): Boolean =
        pendingResultCode == Activity.RESULT_OK && pendingResultData != null

    fun isProjectionActive(): Boolean = activeMediaProjection != null

    @Synchronized
    fun setMediaProjection(projection: MediaProjection?, context: Context? = null) {
        activeMediaProjection = projection
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                Log.d(TAG, "MediaProjection sistem tarafından durduruldu (onStop)")
                activeMediaProjection = null
                pendingResultData = null
                pendingResultCode = 0
                context?.let { ctx ->
                    FloatingBubbleService.setMediaProjectionActive(ctx, false)
                }
            }
        }, null)
    }

    @Synchronized
    fun getOrCreateMediaProjection(context: Context): MediaProjection? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        activeMediaProjection?.let { return it }

        val data = pendingResultData ?: return null
        val code = pendingResultCode
        if (code != Activity.RESULT_OK) return null

        return try {
            // Android 14+ kuralı: MediaProjection alınmadan önce FGS MEDIA_PROJECTION tipinde olmalıdır
            FloatingBubbleService.setMediaProjectionActive(context, true)

            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val proj = mpManager?.getMediaProjection(code, data.clone() as Intent)
            // Android 14 single-use consent token consumed
            pendingResultData = null
            pendingResultCode = 0
            setMediaProjection(proj, context)
            Log.i(TAG, "Yeni MediaProjection oturumu oluşturuldu")
            proj
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection oluşturulamadı", e)
            pendingResultData = null
            pendingResultCode = 0
            null
        }
    }

    @Synchronized
    fun release() {
        try {
            activeMediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection serbest bırakılırken hata", e)
        }
        activeMediaProjection = null
        pendingResultData = null
        pendingResultCode = 0
    }
}
