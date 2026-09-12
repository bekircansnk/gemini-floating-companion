package com.gemini.floatingcompanion.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gemini.floatingcompanion.R
import kotlin.math.abs

@SuppressLint("ViewConstructor")
class FloatingSubtitleOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onToggleVoice: (isMuted: Boolean) -> Unit,
    private val onClose: () -> Unit
) : FrameLayout(android.view.ContextThemeWrapper(context, R.style.Theme_GeminiCompanion)) {

    private val tvLiveSubtitleText: TextView
    private val viewLiveDot: View
    private val btnToggleVoice: ImageView
    private val btnCloseSubtitle: ImageView
    private val subtitleContainer: LinearLayout

    private val displayMetrics = context.resources.displayMetrics
    // Kompakt, modern genişlik (~290dp): Kullanıcının kartı ekranın sağına, soluna, köşesine serbestçe taşıyabilmesi için
    private val cardWidthPx = minOf((290 * displayMetrics.density).toInt(), (displayMetrics.widthPixels * 0.82f).toInt())

    var params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        cardWidthPx,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = savedLastX.takeIf { it >= 0 } ?: ((displayMetrics.widthPixels - cardWidthPx) / 2).coerceAtLeast(12)
        y = savedLastY.takeIf { it >= 0 } ?: (displayMetrics.heightPixels * 0.60f).toInt()
    }

    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var isVoiceMuted = false
    private var pulseAnimator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(12)

    companion object {
        private var savedLastX = -1
        private var savedLastY = -1
    }

    init {
        LayoutInflater.from(this.context).inflate(R.layout.layout_floating_subtitles, this, true)

        tvLiveSubtitleText = findViewById(R.id.tvLiveSubtitleText)
        viewLiveDot = findViewById(R.id.viewLiveDot)
        btnToggleVoice = findViewById(R.id.btnToggleVoice)
        btnCloseSubtitle = findViewById(R.id.btnCloseSubtitle)
        subtitleContainer = findViewById(R.id.subtitleContainer)

        setupListeners()
        startLiveDotPulsing()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        btnToggleVoice.setOnClickListener {
            isVoiceMuted = !isVoiceMuted
            if (isVoiceMuted) {
                btnToggleVoice.setImageResource(R.drawable.ic_volume_off)
                btnToggleVoice.alpha = 0.5f
                btnToggleVoice.setColorFilter(0xFF94A3B8.toInt())
            } else {
                btnToggleVoice.setImageResource(R.drawable.ic_volume_up)
                btnToggleVoice.alpha = 1.0f
                btnToggleVoice.setColorFilter(0xFF38BDF8.toInt())
            }
            onToggleVoice(isVoiceMuted)
        }

        btnCloseSubtitle.setOnClickListener {
            stopLiveDotPulsing()
            onClose()
        }

    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.rawX - touchStartX)
                val dy = abs(event.rawY - touchStartY)
                if (dx > touchSlop || dy > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - touchStartX).toInt()
                val dy = (event.rawY - touchStartY).toInt()
                if (abs(dx) > touchSlop || abs(dy) > touchSlop || isDragging) {
                    isDragging = true
                    val metrics = context.resources.displayMetrics
                    val marginX = (8 * metrics.density).toInt()
                    val marginY = (24 * metrics.density).toInt()

                    val maxX = (metrics.widthPixels - cardWidthPx - marginX).coerceAtLeast(marginX)
                    val maxY = (metrics.heightPixels - (80 * metrics.density).toInt()).coerceAtLeast(marginY)

                    params.x = (initialX + dx).coerceIn(marginX, maxX)
                    params.y = (initialY + dy).coerceIn(marginY, maxY)
                    savedLastX = params.x
                    savedLastY = params.y
                    updateLayoutSafe()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun updateSubtitle(text: String) {
        post {
            tvLiveSubtitleText.text = text
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLiveDotPulsing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLiveDotPulsing()
    }

    fun startLiveDotPulsing() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 1.0f).apply {
            duration = 750
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                viewLiveDot.alpha = anim.animatedValue as Float
            }
            start()
        }
    }

    fun stopLiveDotPulsing() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private fun updateLayoutSafe() {
        try {
            if (isAttachedToWindow) {
                windowManager.updateViewLayout(this, params)
            }
        } catch (_: Exception) {}
    }

    fun dismiss() {
        stopLiveDotPulsing()
        try {
            if (isAttachedToWindow) {
                windowManager.removeView(this)
            }
        } catch (_: Exception) {}
    }
}
