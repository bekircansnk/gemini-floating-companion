package com.gemini.floatingcompanion.overlay

import android.animation.TimeInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.gemini.floatingcompanion.R

@SuppressLint("ViewConstructor")
class RadialMenuView(
    context: Context,
    private val bubbleX: Int,
    private val bubbleY: Int,
    private val bubbleWidth: Int,
    private val bubbleHeight: Int,
    private val onActionSelected: (MenuAction) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(android.view.ContextThemeWrapper(context, R.style.Theme_GeminiCompanion)) {

    enum class MenuAction {
        VOICE,
        OCR,
        PDF,
        TRANSLATE_VIDEO
    }

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val radialCard: View
    private val radialMenuRoot: View
    private val openedAtMs = System.currentTimeMillis()
    private var isClosing = false

    init {
        LayoutInflater.from(this.context).inflate(R.layout.layout_radial_menu, this, true)

        radialMenuRoot = findViewById(R.id.radialMenuRoot)
        radialCard = findViewById(R.id.radialCard)

        // Prevent touches on radialCard or its padding from dismissing the menu
        radialCard.setOnTouchListener { _, _ ->
            // Card itself handles/consumes clicks so background scrim does not dismiss
            false
        }

        // Only dismiss if user touches the background scrim after at least 600ms
        radialMenuRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (System.currentTimeMillis() - openedAtMs > 600L && !isClosing) {
                    dismissWithAnimation()
                    return@setOnTouchListener true
                }
            }
            false
        }

        findViewById<View>(R.id.btnActionVoice).setOnClickListener {
            if (!isClosing) {
                triggerHaptic()
                dismissWithAnimation { onActionSelected(MenuAction.VOICE) }
            }
        }

        findViewById<View>(R.id.btnActionOcr).setOnClickListener {
            if (!isClosing) {
                triggerHaptic()
                dismissWithAnimation { onActionSelected(MenuAction.OCR) }
            }
        }

        findViewById<View>(R.id.btnActionScanDoc).setOnClickListener {
            if (!isClosing) {
                triggerHaptic()
                dismissWithAnimation { onActionSelected(MenuAction.PDF) }
            }
        }

        findViewById<View>(R.id.btnActionTranslateVideo).setOnClickListener {
            if (!isClosing) {
                triggerHaptic()
                dismissWithAnimation { onActionSelected(MenuAction.TRANSLATE_VIDEO) }
            }
        }

        findViewById<ImageView>(R.id.btnCloseMenu).setOnClickListener {
            if (!isClosing) {
                triggerHaptic()
                dismissWithAnimation()
            }
        }

        // Initially hide until positioned
        radialCard.alpha = 0f

        post {
            positionAndAnimateCard()
        }
    }

    private fun positionAndAnimateCard() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        radialCard.measure(
            MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.AT_MOST)
        )

        val cardWidth = if (radialCard.measuredWidth > 0) radialCard.measuredWidth else (300 * density).toInt()
        val cardHeight = if (radialCard.measuredHeight > 0) radialCard.measuredHeight else (80 * density).toInt()

        val margin = (16 * density).toInt()

        // Horizontal positioning: align around bubble center, clamped to screen edges
        val bubbleCenterX = bubbleX + (bubbleWidth / 2)
        var targetX = bubbleCenterX - (cardWidth / 2)
        try {
            val minX = margin
            val maxX = maxOf(minX, screenWidth - cardWidth - margin)
            targetX = targetX.coerceIn(minX, maxX)

            // Vertical positioning: show above bubble if room, otherwise below
            val spaceAbove = bubbleY
            val minY = (24 * density).toInt()
            val maxY = maxOf(minY, screenHeight - cardHeight - (32 * density).toInt())

            val targetY = if (spaceAbove > cardHeight + (24 * density).toInt()) {
                bubbleY - cardHeight - (8 * density).toInt()
            } else {
                bubbleY + bubbleHeight + (8 * density).toInt()
            }.coerceIn(minY, maxY)

            radialCard.x = targetX.toFloat()
            radialCard.y = targetY.toFloat()

            // Pop & Bounce Entrance
            radialCard.scaleX = 0.55f
            radialCard.scaleY = 0.55f
            radialCard.alpha = 0f
            radialCard.animate()
                .alpha(1f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.25f))
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Safe layout fallback in RadialMenuView", e)
            radialCard.x = margin.toFloat()
            radialCard.y = (bubbleY.coerceAtLeast(100)).toFloat()
            radialCard.alpha = 1f
            radialCard.scaleX = 1f
            radialCard.scaleY = 1f
        }

        radialMenuRoot.alpha = 0f
        radialMenuRoot.animate()
            .alpha(1f)
            .setDuration(180)
            .start()
    }

    fun dismissWithAnimation(onFinished: (() -> Unit)? = null) {
        if (isClosing) return
        isClosing = true

        radialCard.animate()
            .alpha(0f)
            .scaleX(0.7f)
            .scaleY(0.7f)
            .setDuration(150)
            .start()

        radialMenuRoot.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                onFinished?.invoke() ?: onDismiss()
            }
            .start()
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

    companion object {
        private const val TAG = "RadialMenuView"
    }
}
