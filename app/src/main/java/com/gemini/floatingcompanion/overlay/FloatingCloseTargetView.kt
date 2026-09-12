package com.gemini.floatingcompanion.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.gemini.floatingcompanion.R
import kotlin.math.hypot

/**
 * BALONCUĞU SÜRÜKLEYİP KAPATMA ÇARPI HEDEFİ (CLOSE TARGET DROP-ZONE)
 *
 * Kullanıcı baloncuğu sürüklemeye başladığında ekranın alt orta kısmında belirir.
 * Baloncuk üzerine getirildiğinde büyür, kırmızı ışık saçar ve haptik titreşim verir.
 * Bırakıldığında baloncuğu kapatır ve arka plan dinlemesini/çevirisini sonlandırır.
 */
@SuppressLint("ViewConstructor")
class FloatingCloseTargetView(
    context: Context,
    private val windowManager: WindowManager
) : FrameLayout(android.view.ContextThemeWrapper(context, R.style.Theme_GeminiCompanion)) {

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 70
    }

    private val targetCircle: FrameLayout
    private val ivClose: ImageView
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var isHovered = false

    init {
        val density = context.resources.displayMetrics.density
        val sizePx = (64 * density).toInt()

        targetCircle = FrameLayout(context).apply {
            layoutParams = LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER
            }
            setBackgroundResource(R.drawable.bg_action_circle)
            backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(context, android.R.color.holo_red_dark)
            elevation = 16 * density
        }

        ivClose = ImageView(context).apply {
            val iconSize = (30 * density).toInt()
            layoutParams = LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFFFFFFFF.toInt())
        }

        targetCircle.addView(ivClose)
        addView(targetCircle)

        visibility = View.GONE
        alpha = 0f
        scaleX = 0.7f
        scaleY = 0.7f
    }

    fun show() {
        if (visibility == View.VISIBLE && alpha > 0.8f) return
        visibility = View.VISIBLE
        animate()?.cancel()
        animate()
            ?.alpha(0.92f)
            ?.scaleX(1.0f)
            ?.scaleY(1.0f)
            ?.setDuration(180)
            ?.start()
    }

    fun hide() {
        if (visibility == View.GONE) return
        isHovered = false
        animate()?.cancel()
        animate()
            ?.alpha(0f)
            ?.scaleX(0.7f)
            ?.scaleY(0.7f)
            ?.setDuration(180)
            ?.withEndAction {
                visibility = View.GONE
            }
            ?.start()
    }

    fun checkHover(bubbleCenterX: Float, bubbleCenterY: Float): Boolean {
        val location = IntArray(2)
        targetCircle.getLocationOnScreen(location)
        val targetCenterX = location[0] + targetCircle.width / 2f
        val targetCenterY = location[1] + targetCircle.height / 2f

        val distance = hypot((bubbleCenterX - targetCenterX).toDouble(), (bubbleCenterY - targetCenterY).toDouble()).toFloat()
        val hitRadius = targetCircle.width * 1.25f

        val hoveredNow = distance < hitRadius
        if (hoveredNow != isHovered) {
            isHovered = hoveredNow
            onHoverStateChanged(isHovered)
        }
        return isHovered
    }

    private fun onHoverStateChanged(hovered: Boolean) {
        if (hovered) {
            triggerHaptic()
            targetCircle.animate()
                ?.scaleX(1.3f)
                ?.scaleY(1.3f)
                ?.setDuration(150)
                ?.start()
            targetCircle.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(context, android.R.color.holo_red_light)
        } else {
            targetCircle.animate()
                ?.scaleX(1.0f)
                ?.scaleY(1.0f)
                ?.setDuration(150)
                ?.start()
            targetCircle.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(context, android.R.color.holo_red_dark)
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (_: Exception) {}
    }
}
