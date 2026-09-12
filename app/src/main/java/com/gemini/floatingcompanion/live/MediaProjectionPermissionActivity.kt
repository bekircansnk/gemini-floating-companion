package com.gemini.floatingcompanion.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.gemini.floatingcompanion.overlay.FloatingBubbleManager
import com.gemini.floatingcompanion.service.FloatingBubbleService

/**
 * Dahili Medya / Sistem Sesi Yakalama İzin Diyaloğu (Şeffaf Activity).
 *
 * Kullanıcı ekranda video izlerken baloncuk üzerinden canlı çeviriyi başlattığında
 * MediaProjection izin istemini ekranda gösterir. Onay alındığında AudioPlaybackCapture
 * ile mikrofonu devre dışı bırakıp saf YouTube sesini yakalar. Reddedilirse akıllı
 * gated mikrofon ile sorunsuz devam eder.
 */
class MediaProjectionPermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish()
            return
        }

        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_CODE_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Ekran yakalama izni açılamadı", e)
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "Dahili ses yakalama izni kullanıcı tarafından onaylandı")
                FloatingBubbleService.setMediaProjectionActive(this, true)
                MediaProjectionHolder.setPendingResult(resultCode, data)
                MediaProjectionHolder.getOrCreateMediaProjection(this)
                FloatingBubbleManager.getInstance(this)?.userDeclinedMediaProjection = false
                Toast.makeText(this, "🎧 Dahili Medya Sesi Hazır (0 Yankı)", Toast.LENGTH_SHORT).show()
                FloatingBubbleManager.getInstance(this)?.startLiveVideoTranslation()
            } else {
                Log.w(TAG, "Dahili ses izni reddedildi, mikrofon fallback kullanılacak")
                FloatingBubbleManager.getInstance(this)?.userDeclinedMediaProjection = true
                Toast.makeText(this, "Dahili ses izni verilmedi, mikrofonla devam ediliyor.", Toast.LENGTH_SHORT).show()
                FloatingBubbleManager.getInstance(this)?.startLiveVideoTranslation()
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "MediaProjPermAct"
        private const val REQUEST_CODE_CAPTURE = 1010
    }
}
