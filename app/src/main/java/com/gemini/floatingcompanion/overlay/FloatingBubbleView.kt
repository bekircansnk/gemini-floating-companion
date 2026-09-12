package com.gemini.floatingcompanion.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.gemini.floatingcompanion.R
import com.gemini.floatingcompanion.data.BubbleState
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class FloatingBubbleView(
    context: Context,
    private val windowManager: WindowManager,
    private val onBubbleClick: () -> Unit,
    private val onBubbleLongClick: () -> Unit
) : FrameLayout(context) {

    private val ivIcon: ImageView
    private val tvStatus: TextView
    private val pbLoading: ProgressBar
    private val viewGlow: View
    private val bubbleContainer: View
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    var params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 400
    }

    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var isLongPressed = false

    private val longPressRunnable = Runnable {
        isLongPressed = true
        triggerHaptic()
        onBubbleLongClick()
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_floating_bubble, this, true)
        ivIcon = findViewById(R.id.ivBubbleIcon)
        tvStatus = findViewById(R.id.tvBubbleStatus)
        pbLoading = findViewById(R.id.pbLoading)
        viewGlow = findViewById(R.id.viewGlow)
        bubbleContainer = findViewById(R.id.bubbleContainer)

        setupTouchHandling()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling() {
        bubbleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    isLongPressed = false
                    handler.postDelayed(longPressRunnable, 450)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        if (!isDragging) {
                            isDragging = true
                            handler.removeCallbacks(longPressRunnable)
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        updateLayoutSafe()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!isDragging && !isLongPressed) {
                        triggerHaptic()
                        onBubbleClick()
                    } else if (isDragging) {
                        snapToEdge()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val targetX = if (params.x + width / 2 < screenWidth / 2) 20 else screenWidth - width - 20

        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            params.x = anim.animatedValue as Int
            updateLayoutSafe()
        }
        animator.start()
    }

    private fun updateLayoutSafe() {
        try {
            if (isAttachedToWindow) {
                windowManager.updateViewLayout(this, params)
            }
        } catch (e: Exception) {
            // view might be detached
        }
    }

    fun setState(state: BubbleState, message: String? = null) {
        when (state) {
            BubbleState.IDLE -> {
                ivIcon.setImageResource(R.drawable.ic_gemini_sparkle)
                viewGlow.visibility = View.GONE
                pbLoading.visibility = View.GONE
                tvStatus.visibility = View.GONE
            }

            BubbleState.LISTENING -> {
                ivIcon.setImageResource(R.drawable.ic_mic)
                viewGlow.visibility = View.VISIBLE
                viewGlow.backgroundTintList = context.getColorStateList(R.color.recording_glow)
                pbLoading.visibility = View.GONE
                tvStatus.text = message ?: "Dinliyor..."
                tvStatus.visibility = View.VISIBLE
            }

            BubbleState.PROCESSING -> {
                ivIcon.setImageResource(R.drawable.ic_gemini_sparkle)
                viewGlow.visibility = View.GONE
                pbLoading.visibility = View.VISIBLE
                tvStatus.text = message ?: "İşleniyor..."
                tvStatus.visibility = View.VISIBLE
            }

            BubbleState.ERROR -> {
                ivIcon.setImageResource(R.drawable.ic_close)
                viewGlow.visibility = View.GONE
                pbLoading.visibility = View.GONE
                tvStatus.text = message ?: "Hata!"
                tvStatus.visibility = View.VISIBLE
            }
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }
}
