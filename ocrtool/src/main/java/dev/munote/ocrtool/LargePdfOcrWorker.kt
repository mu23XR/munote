package dev.munote.ocrtool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class LargePdfOcrWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val input = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输入 PDF")
        val output = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输出 PDF")
        val maxDimension = inputData.getInt(KEY_MAX_DIMENSION, 2200).coerceIn(1400, 3200)

        setForeground(makeForeground("准备 PDF", 0))

        val scratch = File(applicationContext.cacheDir, "muocr-scratch-$id").apply { mkdirs() }
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )

        var rendererPfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var document: PDDocument? = null
        var fonts: PdfSystemFontResolver? = null

        try {
            setStage("打开 PDF", 0, 0)

            rendererPfd = applicationContext.contentResolver.openFileDescriptor(input, "r")
                ?: return@withContext failure("无法打开输入 PDF")
            renderer = PdfRenderer(rendererPfd)

            val pdfInput = applicationContext.contentResolver.openInputStream(input)
                ?: return@withContext failure("无法读取输入 PDF")
            document = pdfInput.use { stream ->
                PDDocument.load(
                    stream,
                    MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
                )
            }

            if (document.isEncrypted) {
                return@withContext failure("暂不支持加密 PDF")
            }

            val total = min(renderer.pageCount, document.numberOfPages)
            if (total <= 0) return@withContext failure("PDF 没有页面")

            fonts = PdfSystemFontResolver(document, "MuOCRFont")
            for (pageIndex in 0 until total) {
                    coroutineContext.ensureActive()
                    if (isStopped) return@withContext failure("任务已取消")

                    val renderPage = renderer.openPage(pageIndex)
                    try {
                        val size = targetBitmapSize(
                            renderPage.width,
                            renderPage.height,
                            maxDimension,
                            MAX_OCR_PIXELS
                        )
                        val bitmap = Bitmap.createBitmap(
                            size.first,
                            size.second,
                            Bitmap.Config.ARGB_8888
                        )
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            renderPage.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            val result = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                            )

                            val lines = result.textBlocks
                                .flatMap { it.lines }
                                .mapNotNull { line ->
                                    val box = line.boundingBox ?: return@mapNotNull null
                                    val text = sanitize(line.text)
                                    if (text.isBlank()) null else OcrLine(text, box)
                                }

                            if (lines.isNotEmpty()) {
                                appendInvisibleLayer(
                                    document,
                                    document.getPage(pageIndex),
                                    lines,
                                    bitmap.width,
                                    bitmap.height,
                                    fonts
                                )
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        renderPage.close()
                    }

                    val done = pageIndex + 1
                    val pct = ((done * 92L) / total).toInt().coerceIn(1, 92)
                    setStage("OCR", done, total, pct)
                }

            coroutineContext.ensureActive()
            setStage("保存 PDF", total, total, 95)

            val out = applicationContext.contentResolver.openOutputStream(output, "wt")
                ?: return@withContext failure("无法创建输出 PDF")
            out.use { stream ->
                document.save(stream)
                stream.flush()
            }

            setStage("完成", total, total, 100)
            return@withContext Result.success(
                workDataOf("pages" to total)
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return@withContext failure(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { fonts?.close() }
            runCatching { document?.close() }
            runCatching { renderer?.close() }
            runCatching { rendererPfd?.close() }
            runCatching { recognizer.close() }
            scratch.deleteRecursively()
        }
    }

    private suspend fun setStage(
        stage: String,
        page: Int,
        total: Int,
        percent: Int = if (total > 0) ((page * 100L) / total).toInt() else 0
    ) {
        setProgress(
            workDataOf(
                "stage" to stage,
                "page" to page,
                "total" to total,
                "percent" to percent
            )
        )
        setForeground(makeForeground(
            if (total > 0) "$stage · $page/$total" else stage,
            percent
        ))
    }

    private fun appendInvisibleLayer(
        document: PDDocument,
        page: PDPage,
        lines: List<OcrLine>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        fonts: PdfSystemFontResolver
    ) {
        val crop = page.cropBox ?: page.mediaBox ?: return
        val rotation = normalizeRotation(page.rotation)
        val (displayW, displayH) = displaySize(rotation, crop.width, crop.height)
        val matrix = displayToUser(
            rotation,
            crop.lowerLeftX,
            crop.lowerLeftY,
            crop.width,
            crop.height
        )
        val sx = displayW / bitmapWidth.toFloat()
        val sy = displayH / bitmapHeight.toFloat()

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { out ->
            out.saveGraphicsState()
            out.transform(Matrix(
                matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]
            ))

            for (line in lines) {
                val box = line.box
                val boxW = max(0.5f, box.width() * sx)
                val boxH = max(1f, box.height() * sy)
                val x = box.left * sx
                val bottom = displayH - box.bottom * sy
                try {
                    val font = fonts.resolve(line.text, false)
                    val fontSize = (boxH * 0.88f).coerceIn(1f, 96f)
                    val naturalWidth = font.getStringWidth(line.text) / 1000f * fontSize
                    if (naturalWidth <= 0f) continue
                    val horizontalScale = (boxW / naturalWidth * 100f).coerceIn(5f, 1000f)

                    out.beginText()
                    out.setRenderingMode(RenderingMode.NEITHER)
                    out.setFont(font, fontSize)
                    out.setHorizontalScaling(horizontalScale)
                    out.newLineAtOffset(x, bottom + boxH * 0.12f)
                    out.showText(line.text)
                    out.endText()
                } catch (_: Throwable) {
                    runCatching { out.endText() }
                }
            }

            out.restoreGraphicsState()
        }
    }

    private fun targetBitmapSize(
        width: Int,
        height: Int,
        maxDimension: Int,
        maxPixels: Long
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        var scale = maxDimension.toFloat() / max(width, height).toFloat()
        scale = max(scale, 1f)

        val pixelScale = sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong()).toDouble())
            .toFloat()
        scale = min(scale, pixelScale)
        if (scale <= 0f) scale = 1f

        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    private fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private fun displaySize(rotation: Int, width: Float, height: Float): Pair<Float, Float> =
        if (normalizeRotation(rotation) % 180 == 0) width to height else height to width

    private fun displayToUser(
        rotation: Int,
        llx: Float,
        lly: Float,
        width: Float,
        height: Float
    ): FloatArray = when (normalizeRotation(rotation)) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + width, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + width, lly + height)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + height)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }

    private fun makeForeground(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "MuOCR processing",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("MuOCR")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf("error" to message.take(500)))

    data class OcrLine(val text: String, val box: Rect)

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_MAX_DIMENSION = "max_dimension"
        private const val CHANNEL_ID = "muocr"
        private const val NOTIFICATION_ID = 2301
        private const val MAX_OCR_PIXELS = 4_500_000L
    }
}
