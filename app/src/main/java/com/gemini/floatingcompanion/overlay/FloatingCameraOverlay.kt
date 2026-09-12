package com.gemini.floatingcompanion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.gemini.floatingcompanion.R
import com.gemini.floatingcompanion.data.CameraMode
import com.gemini.floatingcompanion.data.PreferencesManager
import com.gemini.floatingcompanion.service.GeminiAccessibilityService
import com.gemini.floatingcompanion.vision.DocumentScannerProcessor
import com.gemini.floatingcompanion.vision.GeminiVisionAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@SuppressLint("ViewConstructor")
class FloatingCameraOverlay(
    context: Context,
    private val scope: CoroutineScope,
    private val onClose: () -> Unit
) : FrameLayout(context), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val previewView: PreviewView
    private val layoutProcessing: LinearLayout
    private val tvProcessingStatus: TextView
    private val btnCapture: Button
    private val tabOcr: TextView
    private val tabPdf: TextView
    private val btnClose: ImageView

    private var currentMode: CameraMode = CameraMode.OCR_STRUCTURED
    private var imageCapture: ImageCapture? = null

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        LayoutInflater.from(context).inflate(R.layout.layout_camera_overlay, this, true)

        previewView = findViewById(R.id.previewView)
        layoutProcessing = findViewById(R.id.layoutProcessing)
        tvProcessingStatus = findViewById(R.id.tvProcessingStatus)
        btnCapture = findViewById(R.id.btnCapture)
        tabOcr = findViewById(R.id.tabOcr)
        tabPdf = findViewById(R.id.tabPdf)
        btnClose = findViewById(R.id.btnCameraClose)

        setupListeners()
        startCamera()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun setupListeners() {
        tabOcr.setOnClickListener {
            switchMode(CameraMode.OCR_STRUCTURED)
        }

        tabPdf.setOnClickListener {
            switchMode(CameraMode.CAM_SCANNER_PDF)
        }

        btnClose.setOnClickListener {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            onClose()
        }

        btnCapture.setOnClickListener {
            captureAndProcess()
        }
    }

    private fun switchMode(mode: CameraMode) {
        currentMode = mode
        if (mode == CameraMode.OCR_STRUCTURED) {
            tabOcr.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_blue)
            tabOcr.setTextColor(0xFFFFFFFF.toInt())

            tabPdf.backgroundTintList = null
            tabPdf.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            btnCapture.text = "📸 Fotoğraf Çek & Metni Yapıştır"
        } else {
            tabPdf.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_blue)
            tabPdf.setTextColor(0xFFFFFFFF.toInt())

            tabOcr.backgroundTintList = null
            tabOcr.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            btnCapture.text = "📄 Belgeyi Tara & PDF Olarak Paylaş"
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "CameraX initialization failed", e)
                Toast.makeText(context, "Kamera başlatılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun captureAndProcess() {
        val capture = imageCapture ?: return

        val tempFile = File(context.cacheDir, "temp_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        layoutProcessing.visibility = View.VISIBLE
        btnCapture.isEnabled = false

        if (currentMode == CameraMode.OCR_STRUCTURED) {
            tvProcessingStatus.text = "Fotoğraf Çekildi, Gemini 3.8 Flash Analiz Ediyor..."
        } else {
            tvProcessingStatus.text = "Belge Taranıyor, CamScanner Efekti ve PDF Oluşturuluyor..."
        }

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processCapturedFile(tempFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    layoutProcessing.visibility = View.GONE
                    btnCapture.isEnabled = true
                    Toast.makeText(context, "Fotoğraf çekilemedi: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun processCapturedFile(file: File) {
        val appContext = context.applicationContext
        scope.launch {
            val prefs = PreferencesManager.getInstance(appContext)

            if (currentMode == CameraMode.OCR_STRUCTURED) {
                val analyzer = GeminiVisionAnalyzer(prefs.apiKey, prefs.visionModel)
                val result = analyzer.analyzeImageForStructuredText(file)

                withContext(Dispatchers.Main) {
                    layoutProcessing.visibility = View.GONE
                    btnCapture.isEnabled = true
                    file.delete()

                    result.onSuccess { structuredText ->
                        GeminiAccessibilityService.instance?.insertText(structuredText, isStreaming = false)
                        Toast.makeText(appContext, "Metin/Tablo doğrudan alana yapıştırıldı! ✨", Toast.LENGTH_SHORT).show()
                        onClose()
                    }.onFailure { error ->
                        Toast.makeText(appContext, "OCR Hatası: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                val processor = DocumentScannerProcessor(appContext)
                val result = processor.processAndExportPdf(file, applyCamScannerFilter = true)

                withContext(Dispatchers.Main) {
                    layoutProcessing.visibility = View.GONE
                    btnCapture.isEnabled = true
                    file.delete()

                    result.onSuccess { pdfFile ->
                        processor.sharePdf(pdfFile)
                        Toast.makeText(appContext, "PDF oluşturuldu, paylaşım menüsü açıldı! 📄", Toast.LENGTH_SHORT).show()
                        onClose()
                    }.onFailure { error ->
                        Toast.makeText(appContext, "PDF Hatası: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "FloatingCameraOverlay"
    }
}
