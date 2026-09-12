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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
    private var currentState: BubbleState = BubbleState.IDLE

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(24)

    private val longPressRunnable = Runnable {
        isLongPressed = true
        triggerHaptic()
        onBubbleLongClick()
    }

    var onDragStart: (() -> Unit)? = null
    var onDragMove: ((bubbleCenterX: Float, bubbleCenterY: Float) -> Boolean)? = null
    var onDragEnd: ((isOverCloseTarget: Boolean) -> Unit)? = null

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
        this.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    isLongPressed = false

                    mainHandler.postDelayed(longPressRunnable, 450)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isLongPressed) return@setOnTouchListener true
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (!isDragging) {
                            isDragging = true
                            mainHandler.removeCallbacks(longPressRunnable)
                            onDragStart?.invoke()
                        }
                        params.x = initialX + dx
                        params.y = initialY + dy
                        updateLayoutSafe()

                        val bubbleCenterX = event.rawX
                        val bubbleCenterY = event.rawY
                        onDragMove?.invoke(bubbleCenterX, bubbleCenterY)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)

                    if (!isDragging && !isLongPressed) {
                        triggerHaptic()
                        onBubbleClick()
                    } else if (isDragging) {
                        val isOverClose = onDragMove?.invoke(event.rawX, event.rawY) ?: false
                        onDragEnd?.invoke(isOverClose)
                        if (!isOverClose) {
                            snapToEdge()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        onDragEnd?.invoke(false)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val bubbleWidth = width.takeIf { it > 0 } ?: 54
        val targetX = if (params.x + bubbleWidth / 2 < screenWidth / 2) 16 else screenWidth - bubbleWidth - 16

        val animator = ValueAnimator.ofInt(params.x, targetX)
        animator.duration = 200
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
        } catch (_: Exception) {}
    }

    fun setState(state: BubbleState, message: String? = null) {
        currentState = state
        tvStatus.visibility = View.GONE
        viewGlow.visibility = View.GONE

        when (state) {
            BubbleState.IDLE -> {
                ivIcon.setImageResource(R.drawable.ic_gemini_sparkle)
                pbLoading.visibility = View.GONE
                bubbleContainer.alpha = 1.0f
            }

            BubbleState.LISTENING -> {
                // Sabit, göz yormayan, pırpır etmeyen dairesel mikrofon ikonu
                ivIcon.setImageResource(R.drawable.ic_mic)
                pbLoading.visibility = View.GONE
                bubbleContainer.alpha = 1.0f
            }

            BubbleState.PROCESSING -> {
                ivIcon.setImageResource(R.drawable.ic_gemini_sparkle)
                pbLoading.visibility = View.VISIBLE
                bubbleContainer.alpha = 1.0f
            }

            BubbleState.ERROR -> {
                ivIcon.setImageResource(R.drawable.ic_close)
                pbLoading.visibility = View.GONE
                bubbleContainer.alpha = 1.0f
            }

            BubbleState.VIDEO_DETECTED -> {
                ivIcon.setImageResource(R.drawable.ic_translate)
                pbLoading.visibility = View.GONE
                bubbleContainer.alpha = 1.0f
            }

            BubbleState.TRANSLATING -> {
                ivIcon.setImageResource(R.drawable.ic_translate)
                pbLoading.visibility = View.GONE
                bubbleContainer.alpha = 0.70f
            }
        }
        post { updateLayoutSafe() }
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
