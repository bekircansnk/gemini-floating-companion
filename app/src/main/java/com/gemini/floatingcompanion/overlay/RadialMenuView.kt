package com.gemini.floatingcompanion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.gemini.floatingcompanion.R

@SuppressLint("ViewConstructor")
class RadialMenuView(
    context: Context,
    private val onActionSelected: (MenuAction) -> Unit,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    enum class MenuAction {
        VOICE,
        OCR,
        PDF
    }

    var params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 50
        y = 300
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_radial_menu, this, true)

        findViewById<View>(R.id.btnActionVoice).setOnClickListener {
            onActionSelected(MenuAction.VOICE)
        }

        findViewById<View>(R.id.btnActionOcr).setOnClickListener {
            onActionSelected(MenuAction.OCR)
        }

        findViewById<View>(R.id.btnActionScanDoc).setOnClickListener {
            onActionSelected(MenuAction.PDF)
        }

        findViewById<ImageView>(R.id.btnCloseMenu).setOnClickListener {
            onDismiss()
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
            onDismiss()
            return true
        }
        return super.onTouchEvent(event)
    }
}
