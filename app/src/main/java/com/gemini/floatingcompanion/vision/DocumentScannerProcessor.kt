package com.gemini.floatingcompanion.vision

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Akilli Belge Tarayici ve PDF Motoru
 *
 * - Belge koselerini otomatik tespit eder ve perspektif duzeltme (Perspective Transform) uygular.
 * - CamScanner "Magic Color" renk korumali filtre:
 *   Renkli fotograflari, mavi/kirmizi imzalari ve resmi muhurleri canli korur, kagit arka planini beyazlatir.
 * - Yuksek cozunurluklu A4 PDF uretir ve sistem paylasimini acar.
 */
class DocumentScannerProcessor(private val context: Context) {

    suspend fun processAndExportPdf(
        imageFile: File,
        applyCamScannerFilter: Boolean = true,
        autoCropEdges: Boolean = true
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val rawBitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                ?: return@withContext Result.failure(Exception("Görsel okunamadı veya bozuk."))

            // 1. EXIF rotasyonunu düzelt
            val orientedBitmap = fixExifOrientation(rawBitmap, imageFile.absolutePath)

            // 2. Masayı kırp / Belge kenarlarını tespit et
            val croppedBitmap = if (autoCropEdges) {
                detectAndCropDocument(orientedBitmap)
            } else {
                orientedBitmap
            }

            // 3. CamScanner "Magic Color" renk korumalı belge iyileştirmesi uygula
            val processedBitmap = if (applyCamScannerFilter) {
                applyMagicColorFilter(croppedBitmap)
            } else {
                croppedBitmap
            }

            // 4. Yüksek kaliteli A4 PDF sayfasına çiz
            // Standart A4 boyutları: 595 x 842 nokta
            val pageWidth = 595
            val pageHeight = 842

            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

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
                isAntiAlias = true
            }

            // Arka plana temiz A4 beyazlığı ver
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(processedBitmap, srcRect, destRect, paint)
            pdfDocument.finishPage(page)

            // 5. PDF dosyasını kaydet
            val docsDir = File(context.cacheDir, "docs").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val pdfFile = File(docsDir, "GeminiScan_$timeStamp.pdf")

            FileOutputStream(pdfFile).use { fos ->
                pdfDocument.writeTo(fos)
            }
            pdfDocument.close()

            // Bellek temizliği
            if (processedBitmap != croppedBitmap && !processedBitmap.isRecycled) processedBitmap.recycle()
            if (croppedBitmap != orientedBitmap && !croppedBitmap.isRecycled) croppedBitmap.recycle()
            if (orientedBitmap != rawBitmap && !orientedBitmap.isRecycled) orientedBitmap.recycle()
            if (!rawBitmap.isRecycled) rawBitmap.recycle()

            Log.d(TAG, "Yüksek kaliteli renkli PDF oluşturuldu: ${pdfFile.absolutePath}")
            Result.success(pdfFile)
        } catch (e: Exception) {
            Log.e(TAG, "PDF oluşturma hatası", e)
            Result.failure(e)
        }
    }

    /**
     * Otomatik Belge Koselerini Tespit Etme ve Perspektif Duzeltme (Perspective Warp)
     *
     * Masa veya ekran uzerindeki belgenin 4 kosesini (TL, TR, BR, BL) tespit eder.
     * Android Matrix.setPolyToPoly ile perspektif duzeltme (rectification) uygulayarak
     * yalnizca belge alanini duz bir dikdortgen haline getirir.
     */
    fun detectAndCropDocument(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height

        if (width < 300 || height < 300) return source

        try {
            // Hizli analiz icin olcek (maksimum 480px)
            val targetSize = 480f
            val sampleScale = targetSize / max(width, height)
            val sw = (width * sampleScale).toInt().coerceAtLeast(100)
            val sh = (height * sampleScale).toInt().coerceAtLeast(100)

            val sampleBitmap = Bitmap.createScaledBitmap(source, sw, sh, true)
            val pixels = IntArray(sw * sh)
            sampleBitmap.getPixels(pixels, 0, sw, 0, 0, sw, sh)
            if (sampleBitmap != source) sampleBitmap.recycle()

            // Parlaklik matrisi
            val luma = IntArray(sw * sh)
            var sumLuma = 0L
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                val lum = (299 * r + 587 * g + 114 * b) / 1000
                luma[i] = lum
                sumLuma += lum
            }
            val avgLuma = (sumLuma / pixels.size).toInt()

            // Merkez parlakligi
            var centerSum = 0L
            var centerCount = 0
            val cxStart = (sw * 0.35).toInt()
            val cxEnd = (sw * 0.65).toInt()
            val cyStart = (sh * 0.35).toInt()
            val cyEnd = (sh * 0.65).toInt()
            for (y in cyStart until cyEnd) {
                for (x in cxStart until cxEnd) {
                    centerSum += luma[y * sw + x]
                    centerCount++
                }
            }
            val centerLuma = if (centerCount > 0) (centerSum / centerCount).toInt() else avgLuma

            // Belge kenarlarini aramak icin merkezden disa isin taramasi (Ray Casting)
            val centerX = sw / 2f
            val centerY = sh / 2f
            val rayCount = 48
            val boundaryPoints = mutableListOf<PointF>()

            for (i in 0 until rayCount) {
                val angle = (i.toFloat() / rayCount) * (2 * Math.PI)
                val cosA = Math.cos(angle).toFloat()
                val sinA = Math.sin(angle).toFloat()

                val maxStep = (min(sw, sh) * 0.48f).toInt()
                var hitPoint: PointF? = null

                for (step in 15..maxStep step 2) {
                    val x = (centerX + step * cosA).toInt()
                    val y = (centerY + step * sinA).toInt()

                    if (x < 4 || x >= sw - 4 || y < 4 || y >= sh - 4) {
                        hitPoint = PointF(x.toFloat(), y.toFloat())
                        break
                    }

                    val curLum = luma[y * sw + x]
                    val nextLum = luma[((y + sinA.toInt()).coerceIn(0, sh - 1)) * sw + (x + cosA.toInt()).coerceIn(0, sw - 1)]
                    val diff = abs(curLum - nextLum)
                    val centerDiff = abs(curLum - centerLuma)

                    if (diff > 28 || (centerDiff > 35 && step > 25)) {
                        hitPoint = PointF(x.toFloat(), y.toFloat())
                        break
                    }
                }

                if (hitPoint != null) {
                    boundaryPoints.add(hitPoint)
                }
            }

            if (boundaryPoints.size >= 16) {
                var tlPoint = boundaryPoints[0]
                var trPoint = boundaryPoints[0]
                var brPoint = boundaryPoints[0]
                var blPoint = boundaryPoints[0]

                var minSum = Float.MAX_VALUE
                var maxSum = -Float.MAX_VALUE
                var maxDiff = -Float.MAX_VALUE
                var minDiff = Float.MAX_VALUE

                for (p in boundaryPoints) {
                    val sum = p.x + p.y
                    val diff = p.x - p.y

                    if (sum < minSum) {
                        minSum = sum
                        tlPoint = p
                    }
                    if (sum > maxSum) {
                        maxSum = sum
                        brPoint = p
                    }
                    if (diff > maxDiff) {
                        maxDiff = diff
                        trPoint = p
                    }
                    if (diff < minDiff) {
                        minDiff = diff
                        blPoint = p
                    }
                }

                val isConvex = (trPoint.x > tlPoint.x) && (brPoint.x > blPoint.x) &&
                               (blPoint.y > tlPoint.y) && (brPoint.y > trPoint.y)

                val quadArea = 0.5f * abs(
                    (tlPoint.x * trPoint.y - trPoint.x * tlPoint.y) +
                    (trPoint.x * brPoint.y - brPoint.x * trPoint.y) +
                    (brPoint.x * blPoint.y - blPoint.x * brPoint.y) +
                    (blPoint.x * tlPoint.y - tlPoint.x * blPoint.y)
                )
                val totalArea = sw.toFloat() * sh.toFloat()
                val areaRatio = quadArea / totalArea

                if (isConvex && areaRatio in 0.18f..0.96f) {
                    val tlX = tlPoint.x / sampleScale
                    val tlY = tlPoint.y / sampleScale
                    val trX = trPoint.x / sampleScale
                    val trY = trPoint.y / sampleScale
                    val brX = brPoint.x / sampleScale
                    val brY = brPoint.y / sampleScale
                    val blX = blPoint.x / sampleScale
                    val blY = blPoint.y / sampleScale

                    val widthTop = hypot(trX - tlX, trY - tlY)
                    val widthBottom = hypot(brX - blX, brY - blY)
                    val targetW = max(widthTop, widthBottom).toInt().coerceIn(300, 4000)

                    val heightLeft = hypot(blX - tlX, blY - tlY)
                    val heightRight = hypot(brX - trX, brY - trY)
                    val targetH = max(heightLeft, heightRight).toInt().coerceIn(300, 4000)

                    val srcPoly = floatArrayOf(
                        tlX, tlY,
                        trX, trY,
                        brX, brY,
                        blX, blY
                    )
                    val dstPoly = floatArrayOf(
                        0f, 0f,
                        targetW.toFloat(), 0f,
                        targetW.toFloat(), targetH.toFloat(),
                        0f, targetH.toFloat()
                    )

                    val matrix = Matrix()
                    val polySuccess = matrix.setPolyToPoly(srcPoly, 0, dstPoly, 0, 4)

                    if (polySuccess) {
                        val rectified = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(rectified)
                        canvas.clipRect(0f, 0f, targetW.toFloat(), targetH.toFloat())
                        val paint = Paint().apply {
                            isAntiAlias = true
                            isFilterBitmap = true
                            isDither = true
                        }
                        canvas.drawBitmap(source, matrix, paint)
                        Log.d(TAG, "Belge perspektifi basariyla duzeltildi: ${targetW}x${targetH} (Alan orani: %${(areaRatio * 100).toInt()})")
                        return rectified
                    }
                }
            }

            return fallbackSmartCrop(source, sw, sh, pixels, sampleScale, avgLuma)
        } catch (e: Exception) {
            Log.w(TAG, "Perspektif duzeltmede istisna, orijinal gorsel korundu", e)
        }
        return source
    }

    private fun fallbackSmartCrop(
        source: Bitmap,
        sw: Int,
        sh: Int,
        pixels: IntArray,
        sampleScale: Float,
        avgLuma: Int
    ): Bitmap {
        val width = source.width
        val height = source.height
        val lumaThreshold = 22
        val minEdgePixels = (sh * 0.32f).toInt()

        var cropLeft = 0
        var cropRight = sw - 1
        var cropTop = 0
        var cropBottom = sh - 1

        outerLeft@ for (x in 0 until (sw * 0.35).toInt()) {
            var count = 0
            for (y in (sh * 0.15).toInt() until (sh * 0.85).toInt()) {
                val p = pixels[y * sw + x]
                val lum = ((p ushr 16 and 0xFF) * 299 + (p ushr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (abs(lum - avgLuma) > lumaThreshold) count++
            }
            if (count > minEdgePixels) {
                cropLeft = max(0, x - 2)
                break@outerLeft
            }
        }

        outerRight@ for (x in (sw - 1) downTo (sw * 0.65).toInt()) {
            var count = 0
            for (y in (sh * 0.15).toInt() until (sh * 0.85).toInt()) {
                val p = pixels[y * sw + x]
                val lum = ((p ushr 16 and 0xFF) * 299 + (p ushr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (abs(lum - avgLuma) > lumaThreshold) count++
            }
            if (count > minEdgePixels) {
                cropRight = min(sw - 1, x + 2)
                break@outerRight
            }
        }

        val minHoriz = (sw * 0.32f).toInt()
        outerTop@ for (y in 0 until (sh * 0.35).toInt()) {
            var count = 0
            for (x in (sw * 0.15).toInt() until (sw * 0.85).toInt()) {
                val p = pixels[y * sw + x]
                val lum = ((p ushr 16 and 0xFF) * 299 + (p ushr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (abs(lum - avgLuma) > lumaThreshold) count++
            }
            if (count > minHoriz) {
                cropTop = max(0, y - 2)
                break@outerTop
            }
        }

        outerBottom@ for (y in (sh - 1) downTo (sh * 0.65).toInt()) {
            var count = 0
            for (x in (sw * 0.15).toInt() until (sw * 0.85).toInt()) {
                val p = pixels[y * sw + x]
                val lum = ((p ushr 16 and 0xFF) * 299 + (p ushr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (abs(lum - avgLuma) > lumaThreshold) count++
            }
            if (count > minHoriz) {
                cropBottom = min(sh - 1, y + 2)
                break@outerBottom
            }
        }

        val origCropLeft = (cropLeft / sampleScale).toInt().coerceIn(0, width - 20)
        val origCropTop = (cropTop / sampleScale).toInt().coerceIn(0, height - 20)
        val origCropRight = (cropRight / sampleScale).toInt().coerceIn(origCropLeft + 20, width)
        val origCropBottom = (cropBottom / sampleScale).toInt().coerceIn(origCropTop + 20, height)

        val cropW = origCropRight - origCropLeft
        val cropH = origCropBottom - origCropTop
        val fraction = (cropW.toFloat() * cropH) / (width.toFloat() * height)

        if (fraction in 0.25f..0.97f && (origCropLeft > 10 || origCropTop > 10 || origCropRight < width - 10 || origCropBottom < height - 10)) {
            Log.d(TAG, "Masa kenarlari kirpildi: L=$origCropLeft, T=$origCropTop, W=$cropW, H=$cropH")
            return Bitmap.createBitmap(source, origCropLeft, origCropTop, cropW, cropH)
        }

        return source
    }

    /**
     * CamScanner Magic Color Renk Korumali Belge Iyilestirme Filtresi
     *
     * 1. Kagit arka planini beyazlatir.
     * 2. Fotograflarin, kimlik kartlarinin ve renkli grafiklerin renk tonlarini korur.
     * 3. Mavi imzalari, kirmizi resmi muhurleri ve renkli basliklari canli tutar.
     * 4. Metin kontrastini keskinlestirir.
     */
    fun applyMagicColorFilter(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Görüntünün beyaz ve siyah noktalarını istatistiksel olarak tahmin et
        val sampleStep = max(1, (width * height) / 5000)
        var sumHighLuma = 0L
        var countHigh = 0
        var sumLowLuma = 0L
        var countLow = 0

        for (i in pixels.indices step sampleStep) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val y = (299 * r + 587 * g + 114 * b) / 1000

            if (y > 150) {
                sumHighLuma += y
                countHigh++
            } else if (y < 80) {
                sumLowLuma += y
                countLow++
            }
        }

        // Kağıt beyaz noktası (tipik oda ışığı kağıdı ~175..235 luma)
        val paperWhite = if (countHigh > 0) (sumHighLuma / countHigh).toFloat().coerceIn(170f, 235f) else 200f
        // Koyu mürekkep noktası (~20..65 luma)
        val inkBlack = if (countLow > 0) (sumLowLuma / countLow).toFloat().coerceIn(20f, 65f) else 40f
        val dynamicRange = (paperWhite - inkBlack).coerceAtLeast(60f)

        // 2. Her piksele orantılı kanal kazancı uygula (Kanal oranlarını koruyarak renkleri asla kaybetme)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel ushr 24) and 0xFF
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF

            val luma = (299 * r + 587 * g + 114 * b) / 1000
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val chroma = maxC - minC

            var newR: Int
            var newG: Int
            var newB: Int

            if (luma >= paperWhite * 0.88f && chroma < 18) {
                // Kağıt arka planı: Gölgeyi pürüzsüz sil ve temiz parlak beyaz yap
                val factor = ((luma - paperWhite * 0.88f) / (255f - paperWhite * 0.88f)).coerceIn(0f, 1f)
                val whiteLuma = (242 + factor * 13).toInt() // 242..255 arası temiz beyaz
                newR = whiteLuma
                newG = whiteLuma
                newB = whiteLuma
            } else if (chroma > 12) {
                // Renkli öge (Fotoğraf, İmza, Mühür, Renkli Başlık, Logo vb.)
                // Asla griye binarize etme! Kanalları orantılı ölçekle ve hafif renk canlılığı ver
                val gain = (255f / paperWhite).coerceIn(1.05f, 1.45f)
                val boost = 1.10f

                val scaledR = (r * gain).coerceIn(0f, 255f)
                val scaledG = (g * gain).coerceIn(0f, 255f)
                val scaledB = (b * gain).coerceIn(0f, 255f)

                // Renk doygunluğunu koru ve canlılaştır
                newR = ((scaledR - luma) * boost + luma).toInt().coerceIn(0, 255)
                newG = ((scaledG - luma) * boost + luma).toInt().coerceIn(0, 255)
                newB = ((scaledB - luma) * boost + luma).toInt().coerceIn(0, 255)
            } else {
                // Koyu metin / Mürekkep (Düşük kroma)
                // S-Eğrisi kontrastı: Harfleri daha koyu ve keskin yap, kenar yumuşatmasını koru
                val norm = ((luma - inkBlack) / dynamicRange).coerceIn(0f, 1f)
                val curved = (norm * norm * (3f - 2f * norm)) // Smooth Hermite S-curve
                val targetLuma = (curved * 255f).toInt().coerceIn(0, 255)

                val ratio = if (luma > 0) targetLuma.toFloat() / luma else 1f
                newR = (r * ratio).toInt().coerceIn(0, 255)
                newG = (g * ratio).toInt().coerceIn(0, 255)
                newB = (b * ratio).toInt().coerceIn(0, 255)
            }

            pixels[i] = (a shl 24) or (newR shl 16) or (newG shl 8) or newB
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun fixExifOrientation(rawBitmap: Bitmap, filePath: String): Bitmap {
        return try {
            val exif = android.media.ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated != rawBitmap) rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }
        } catch (_: Exception) {
            rawBitmap
        }
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
                putExtra(Intent.EXTRA_SUBJECT, "PDF Belge")
                putExtra(Intent.EXTRA_TEXT, "Gemini Companion ile olusturulan PDF belgesi.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooser = Intent.createChooser(shareIntent, "PDF Paylaş").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "PDF paylasim hatasi", e)
        }
    }

    companion object {
        private const val TAG = "DocumentScannerProcessor"
    }
}
