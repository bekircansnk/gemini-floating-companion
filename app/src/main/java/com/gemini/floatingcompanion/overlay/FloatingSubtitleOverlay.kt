package com.gemini.floatingcompanion.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private val scrollSubtitleText: ScrollView
    private val viewLiveDot: View
    private val btnToggleVoice: ImageView
    private val btnCloseSubtitle: ImageView
    private val subtitleContainer: LinearLayout
    private val viewResizeHandle: View

    private val displayMetrics get() = context.resources.displayMetrics
    private val minWidthPx: Int get() = (180 * displayMetrics.density).toInt()
    private val minHeightPx: Int get() = (58 * displayMetrics.density).toInt()
    private val maxWidthPx: Int get() = (displayMetrics.widthPixels * 0.95f).toInt()
    private val maxHeightPx: Int get() = (displayMetrics.heightPixels * 0.70f).toInt()

    private val defaultWidthPx: Int
        get() {
            val dm = displayMetrics
            val isLandscape = dm.widthPixels > dm.heightPixels
            val dpTarget = if (isLandscape) 380 else 300
            return minOf((dpTarget * dm.density).toInt(), (dm.widthPixels * 0.85f).toInt())
        }

    var params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        val dm = context.resources.displayMetrics
        val initialW = savedLastWidth.takeIf { it > 0 } ?: minOf((300 * dm.density).toInt(), (dm.widthPixels * 0.85f).toInt())
        width = initialW
        if (savedLastHeight > 0) {
            height = savedLastHeight
        }
        x = savedLastX.takeIf { it >= 0 } ?: ((dm.widthPixels - initialW) / 2).coerceAtLeast(12)
        y = savedLastY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.60f).toInt()

        // Android 9+ (API 28+) Tam ekran video ve kamera çentiği (notch/cutout) alanında daima görünür kalma
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var isResizingCorner = false
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var isVoiceMuted = false
    private var pulseAnimator: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.coerceAtLeast(12)

    private val scaleGestureDetector: ScaleGestureDetector

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        post {
            val dm = context.resources.displayMetrics
            val maxW = (dm.widthPixels * 0.95f).toInt()
            val maxH = (dm.heightPixels * 0.70f).toInt()
            if (params.width > maxW || params.width <= 0) {
                params.width = minOf(defaultWidthPx, maxW)
            }
            if (params.height > maxH) {
                params.height = maxH
            }
            val marginX = (12 * dm.density).toInt()
            val marginY = (24 * dm.density).toInt()
            val maxX = (dm.widthPixels - params.width - marginX).coerceAtLeast(marginX)
            val maxY = (dm.heightPixels - (70 * dm.density).toInt()).coerceAtLeast(marginY)

            params.x = params.x.coerceIn(marginX, maxX)
            params.y = params.y.coerceIn(marginY, maxY)
            savedLastX = params.x
            savedLastY = params.y
            savedLastWidth = params.width
            savedLastHeight = params.height
            updateLayoutSafe()
        }
    }

    companion object {
        private var savedLastX = -1
        private var savedLastY = -1
        private var savedLastWidth = -1
        private var savedLastHeight = -1
    }

    init {
        LayoutInflater.from(this.context).inflate(R.layout.layout_floating_subtitles, this, true)

        tvLiveSubtitleText = findViewById(R.id.tvLiveSubtitleText)
        scrollSubtitleText = findViewById(R.id.scrollSubtitleText)
        viewLiveDot = findViewById(R.id.viewLiveDot)
        btnToggleVoice = findViewById(R.id.btnToggleVoice)
        btnCloseSubtitle = findViewById(R.id.btnCloseSubtitle)
        subtitleContainer = findViewById(R.id.subtitleContainer)
        viewResizeHandle = findViewById(R.id.viewResizeHandle)

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var baseWidth = 0
            private var baseHeight = 0

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                baseWidth = if (params.width > 0) params.width else width.coerceAtLeast(minWidthPx)
                baseHeight = if (params.height > 0) params.height else height.coerceAtLeast(minHeightPx)
                isDragging = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newW = (baseWidth * factor).toInt().coerceIn(minWidthPx, maxWidthPx)
                val newH = (baseHeight * factor).toInt().coerceIn(minHeightPx, maxHeightPx)

                params.width = newW
                params.height = newH
                savedLastWidth = newW
                savedLastHeight = newH

                val dm = context.resources.displayMetrics
                val marginX = (8 * dm.density).toInt()
                val maxX = (dm.widthPixels - newW - marginX).coerceAtLeast(marginX)
                params.x = params.x.coerceIn(marginX, maxX)
                savedLastX = params.x

                updateLayoutSafe()
                return false // Accumulate scale against base dimensions
            }
        })

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

        // Sağ alt köşe tutamacıyla doğrudan dokunarak boyutlandırma
        viewResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isResizingCorner = true
                    resizeStartWidth = if (params.width > 0) params.width else width.coerceAtLeast(minWidthPx)
                    resizeStartHeight = if (params.height > 0) params.height else height.coerceAtLeast(minHeightPx)
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isResizingCorner) {
                        val deltaX = (event.rawX - resizeStartRawX).toInt()
                        val deltaY = (event.rawY - resizeStartRawY).toInt()

                        val newW = (resizeStartWidth + deltaX).coerceIn(minWidthPx, maxWidthPx)
                        val newH = (resizeStartHeight + deltaY).coerceIn(minHeightPx, maxHeightPx)

                        params.width = newW
                        params.height = newH
                        savedLastWidth = newW
                        savedLastHeight = newH

                        val dm = context.resources.displayMetrics
                        val marginX = (8 * dm.density).toInt()
                        val maxX = (dm.widthPixels - newW - marginX).coerceAtLeast(marginX)
                        params.x = params.x.coerceIn(marginX, maxX)
                        savedLastX = params.x

                        updateLayoutSafe()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isResizingCorner = false
                    true
                }
                else -> false
            }
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            return true
        }
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
        scaleGestureDetector.onTouchEvent(event)

        if (event.pointerCount >= 2 || scaleGestureDetector.isInProgress) {
            isDragging = false
            return true
        }

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
                    val marginY = (16 * metrics.density).toInt()
                    val currentWidth = if (params.width > 0) params.width else width.coerceAtLeast(minWidthPx)

                    val maxX = (metrics.widthPixels - currentWidth - marginX).coerceAtLeast(marginX)
                    val maxY = (metrics.heightPixels - (70 * metrics.density).toInt()).coerceAtLeast(marginY)

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
            scrollSubtitleText.post {
                scrollSubtitleText.fullScroll(View.FOCUS_DOWN)
            }
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
