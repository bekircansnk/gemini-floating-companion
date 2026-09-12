package com.gemini.floatingcompanion.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DocumentScannerProcessor(private val context: Context) {

    suspend fun processAndExportPdf(
        imageFile: File,
        applyCamScannerFilter: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val originalBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext Result.failure(Exception("Görsel okunamadı."))

            val processedBitmap = if (applyCamScannerFilter) {
                applyDocumentEnhanceFilter(originalBitmap)
            } else {
                originalBitmap
            }

            // Standard A4 dimensions in PostScript points: 595 x 842
            val pageWidth = 595
            val pageHeight = 842

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            val canvas: Canvas = page.canvas

            // Calculate scaled bounds to fit within page keeping aspect ratio with margin
            val margin = 20
            val availableWidth = pageWidth - (margin * 2)
            val availableHeight = pageHeight - (margin * 2)

            val imgWidth = processedBitmap.width.toFloat()
            val imgHeight = processedBitmap.height.toFloat()
            val scale = minOf(availableWidth / imgWidth, availableHeight / imgHeight)

            val destWidth = (imgWidth * scale).toInt()
            val destHeight = (imgHeight * scale).toInt()

            val left = margin + (availableWidth - destWidth) / 2
            val top = margin + (availableHeight - destHeight) / 2

            val destRect = Rect(left, top, left + destWidth, top + destHeight)
            val srcRect = Rect(0, 0, processedBitmap.width, processedBitmap.height)

            val paint = Paint().apply {
                isFilterBitmap = true
                isDither = true
            }

            canvas.drawBitmap(processedBitmap, srcRect, destRect, paint)
            pdfDocument.finishPage(page)

            // Save PDF to cache docs folder
            val docsDir = File(context.cacheDir, "docs").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFile = File(docsDir, "GeminiScan_$timeStamp.pdf")

            FileOutputStream(pdfFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            Log.d(TAG, "PDF generated successfully: ${pdfFile.absolutePath}")
            Result.success(pdfFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error generating PDF", e)
            Result.failure(e)
        }
    }

    private fun applyDocumentEnhanceFilter(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // CamScanner style High-Contrast Document enhancement matrix
        // Converts to grayscale and enhances contrast/luminance so paper is bright white and text is crisp dark
        val colorMatrix = ColorMatrix().apply {
            // Saturation 0 (Grayscale)
            setSaturation(0f)
        }

        // Contrast boost
        val contrast = 1.4f
        val brightness = 15f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        colorMatrix.postConcat(contrastMatrix)

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    fun sharePdf(pdfFile: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pdfFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Gemini Taranan Belge (${pdfFile.name})")
                putExtra(Intent.EXTRA_TEXT, "Gemini Companion ile taranmış PDF belgesi.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "PDF Belgesini Paylaş").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching share intent for PDF", e)
        }
    }

    companion object {
        private const val TAG = "DocumentScannerProcessor"
    }
}
