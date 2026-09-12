package com.gemini.floatingcompanion.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
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
import androidx.core.content.FileProvider
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
    private val initialMode: CameraMode = CameraMode.OCR_STRUCTURED,
    private val onClose: () -> Unit
) : FrameLayout(android.view.ContextThemeWrapper(context, R.style.Theme_GeminiCompanion)), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val previewView: PreviewView
    private val layoutProcessing: LinearLayout
    private val tvProcessingStatus: TextView
    private val tvProcessingSub: TextView
    private val btnCapture: Button
    private val tabOcr: LinearLayout
    private val tabPdf: LinearLayout
    private val ivTabOcrIcon: ImageView
    private val tvTabOcrTitle: TextView
    private val ivTabPdfIcon: ImageView
    private val tvTabPdfTitle: TextView
    private val btnClose: ImageView

    private val cameraOverlayRoot: FrameLayout
    private val cameraCard: View

    private var currentMode: CameraMode = CameraMode.OCR_STRUCTURED
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    val params: WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        LayoutInflater.from(this.context).inflate(R.layout.layout_camera_overlay, this, true)

        cameraOverlayRoot = findViewById(R.id.cameraOverlayRoot)
        cameraCard = findViewById(R.id.cameraCard)
        previewView = findViewById(R.id.previewView)
        layoutProcessing = findViewById(R.id.layoutProcessing)
        tvProcessingStatus = findViewById(R.id.tvProcessingStatus)
        tvProcessingSub = findViewById(R.id.tvProcessingSub)
        btnCapture = findViewById(R.id.btnCapture)
        tabOcr = findViewById(R.id.tabOcr)
        tabPdf = findViewById(R.id.tabPdf)
        ivTabOcrIcon = findViewById(R.id.ivTabOcrIcon)
        tvTabOcrTitle = findViewById(R.id.tvTabOcrTitle)
        ivTabPdfIcon = findViewById(R.id.ivTabPdfIcon)
        tvTabPdfTitle = findViewById(R.id.tvTabPdfTitle)
        btnClose = findViewById(R.id.btnCameraClose)

        setupListeners()
        switchMode(initialMode)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startCamera()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun setupListeners() {
        cameraOverlayRoot.setOnClickListener {
            closeCamera()
        }

        cameraCard.setOnClickListener {
            // Kart alanına tıklandığında arka plan kapatma tetiklenmesin
        }

        tabOcr.setOnClickListener {
            switchMode(CameraMode.OCR_STRUCTURED)
        }

        tabPdf.setOnClickListener {
            switchMode(CameraMode.CAM_SCANNER_PDF)
        }

        btnClose.setOnClickListener {
            closeCamera()
        }

        btnCapture.setOnClickListener {
            captureAndProcess()
        }
    }

    private fun closeCamera() {
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onClose()
    }

    private fun switchMode(mode: CameraMode) {
        currentMode = mode

        if (mode == CameraMode.OCR_STRUCTURED) {
            // Tab 1 Active
            tabOcr.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_blue)
            ivTabOcrIcon.imageTintList = ContextCompat.getColorStateList(context, android.R.color.white)
            tvTabOcrTitle.setTextColor(0xFFFFFFFF.toInt())

            // Tab 2 Inactive
            tabPdf.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.transparent)
            ivTabPdfIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
            tvTabPdfTitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            btnCapture.text = "Metni Tara ve Yapıştır"
            btnCapture.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_blue)
        } else {
            // Tab 2 Active
            tabPdf.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_blue)
            ivTabPdfIcon.imageTintList = ContextCompat.getColorStateList(context, android.R.color.white)
            tvTabPdfTitle.setTextColor(0xFFFFFFFF.toInt())

            // Tab 1 Inactive
            tabOcr.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.transparent)
            ivTabOcrIcon.imageTintList = ContextCompat.getColorStateList(context, R.color.text_secondary)
            tvTabOcrTitle.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))

            btnCapture.text = "PDF Paylaş"
            btnCapture.backgroundTintList = ContextCompat.getColorStateList(context, R.color.gemini_purple)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(
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
            tvProcessingStatus.text = "Görsel Analiz Ediliyor..."
            tvProcessingSub.text = "Metinler ve tablolar okunup alana aktarılıyor..."
        } else {
            tvProcessingStatus.text = "PDF Hazırlanıyor..."
            tvProcessingSub.text = "Belge köşeleri tespit edilip PDF oluşturuluyor..."
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
        val selectedMode = currentMode

        // Kamera penceresini hemen kapat (kullanici arka plandaki alana aninda doner)
        closeCamera()

        scope.launch(Dispatchers.IO) {
            val prefs = PreferencesManager.getInstance(appContext)

            if (selectedMode == CameraMode.OCR_STRUCTURED) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Görsel analiz ediliyor...", Toast.LENGTH_SHORT).show()
                }

                val analyzer = GeminiVisionAnalyzer(prefs.apiKey, prefs.visionModel)
                val result = analyzer.analyzeImageForStructuredText(file)
                file.delete()

                withContext(Dispatchers.Main) {
                    result.onSuccess { structuredText ->
                        // 1. Panoya kopyala
                        try {
                            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Gemini Companion", structuredText)
                            clipboard.setPrimaryClip(clip)
                        } catch (e: Exception) {
                            Log.w(TAG, "Panoya kopyalama hatasi", e)
                        }

                        // 2. Aktif metin alanina yapistir
                        val inserted = GeminiAccessibilityService.instance?.insertText(structuredText, isStreaming = false) ?: false

                        if (inserted) {
                            Toast.makeText(appContext, "Metin panoya kopyalandı ve yapıştırıldı", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(appContext, "Metin panoya kopyalandı", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { error ->
                        Toast.makeText(appContext, "OCR Hatası: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "PDF hazırlanıyor...", Toast.LENGTH_SHORT).show()
                }

                val processor = DocumentScannerProcessor(appContext)
                val result = processor.processAndExportPdf(
                    imageFile = file,
                    applyEnhancementFilter = true,
                    autoCropEdges = true
                )
                file.delete()

                withContext(Dispatchers.Main) {
                    result.onSuccess { pdfFile ->
                        processor.sharePdf(pdfFile)
                        Toast.makeText(appContext, "PDF Paylaşılıyor", Toast.LENGTH_SHORT).show()
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
