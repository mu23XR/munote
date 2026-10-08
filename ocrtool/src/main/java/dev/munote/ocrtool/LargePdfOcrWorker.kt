package dev.munote.ocrtool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-pass large-PDF pipeline.
 *
 * Pass 1 only uses PdfRenderer + ML Kit. Each recognized page is journaled to disk.
 * Pass 2 closes both of those engines BEFORE opening PDFBox. PDFBox loads from a
 * real file with a disk-backed ScratchFile and adds invisible Unicode text streams.
 *
 * In particular, a 1GB PDF is never read into a single ByteArray or Bitmap, nor
 * do the PDF rendering and PDF editing engines remain open at the same time.
 */
class LargePdfOcrWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private var activeStage = "等待开始"
    private var activePage = 0
    private var activeTotal = 0
    private var lastForegroundPage = -1

    override suspend fun doWork() = withContext(Dispatchers.IO) {
        val input = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输入 PDF")
        val output = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输出 PDF")
        if (input == output) {
            return@withContext failure("不能覆盖原 PDF，请另存到新文件")
        }

        val maxDimension = inputData.getInt(KEY_MAX_DIMENSION, 2200).coerceIn(1200, 3200)
        val metadata = sourceMetadata(input)
        val rootDir = applicationContext.getExternalFilesDir("muocr-work")
            ?: File(applicationContext.filesDir, "muocr-work")
        val jobDir = File(rootDir, jobId(input, metadata))
        val pagesDir = File(jobDir, "pages")
        val scratch = File(jobDir, "scratch")
        var completed = false

        try {
            if (!jobDir.exists() && !jobDir.mkdirs()) {
                throw IOException("无法建立工作目录，检查存储空间")
            }
            if (!pagesDir.isDirectory && !pagesDir.mkdirs()) {
                throw IOException("无法建立 OCR 缓存目录")
            }
            if (!scratch.isDirectory && !scratch.mkdirs()) {
                throw IOException("无法建立 PDF 临时目录")
            }

            report("准备文件", 0, 0, 0, true)
            val original = File(jobDir, "original.pdf")
            ensureSourceCopy(input, original, metadata.size)

            // Only a file descriptor and the recognizer are alive during the OCR pass.
            val pageCount = recognizePages(original, pagesDir, maxDimension)
            coroutineContext.ensureActive()

            // Release ML Kit, PdfRenderer and all of its bitmap resources before PDFBox.
            // An explicit GC here (once, not on each page) helps the constrained
            // Android Java heap before reading the PDF object structure.
            System.gc()

            report("载入原始 PDF 以写入文字层", 0, pageCount, 92, true)
            writeSearchablePdf(original, pagesDir, scratch, pageCount) {
                applicationContext.contentResolver.openOutputStream(output, "wt")
                    ?: throw IOException("无法打开输出文件")
            }

            report("已完成", pageCount, pageCount, 100, true)
            completed = true
            Result.success(workDataOf("pages" to pageCount))
        } catch (cancelled: CancellationException) {
            // The page journal is deliberately kept so a new run may resume.
            throw cancelled
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "MuOCR heap exhausted at $activeStage page $activePage/$activeTotal", oom)
            failure("内存不足（阶段：$activeStage，第 $activePage/$activeTotal 页）。本次已完成的 OCR 页已缓存，可重新运行继续。")
        } catch (error: Exception) {
            Log.e(TAG, "MuOCR failed at $activeStage page $activePage/$activeTotal", error)
            failure("阶段：$activeStage，第 $activePage/$activeTotal 页；" +
                (error.message ?: error.javaClass.simpleName))
        } finally {
            if (!completed) {
                // Incomplete PDF output is not a usable document. Keep OCR cache,
                // but remove the partial output when the provider supports it.
                runCatching {
                    DocumentsContract.deleteDocument(applicationContext.contentResolver, output)
                }
            }
            scratch.deleteRecursively()
            if (completed) {
                // Success means neither the 1GB source copy nor OCR journals remain
                // in app storage.
                jobDir.deleteRecursively()
            }
        }
    }

    private fun sourceMetadata(uri: Uri): SourceMetadata {
        var size = -1L
        var modified = 0L
        runCatching {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                    if (modIndex >= 0 && !cursor.isNull(modIndex)) {
                        modified = cursor.getLong(modIndex)
                    }
                }
            }
        }
        return SourceMetadata(size, modified)
    }

    private fun jobId(uri: Uri, metadata: SourceMetadata): String {
        val raw = (uri.toString() + "|" + metadata.size + "|" + metadata.modified)
            .toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.take(16).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private suspend fun ensureSourceCopy(uri: Uri, sourceFile: File, expectedSize: Long) {
        if (sourceFile.isFile && sourceFile.length() > 0 &&
            (expectedSize <= 0L || sourceFile.length() == expectedSize)
        ) {
            report("复用上次的 PDF 工作副本", 0, 0, 5, true)
            return
        }
        val temp = File(sourceFile.parentFile, "original.pdf.part")
        temp.delete()

        if (expectedSize > 0L) {
            // Reserve additional room for PDFBox scratch and output. If the PDF
            // is already on a different volume, this check is conservative.
            val reserve = max(256L * 1024 * 1024, expectedSize / 5)
            if (sourceFile.parentFile!!.usableSpace < expectedSize + reserve) {
                throw IOException("可用存储空间不足：原 PDF 需要约 " +
                    (expectedSize / 1024 / 1024) + "MB 的工作副本，建议释放至少 2GB。")
            }
        }

        try {
            val stream = applicationContext.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取 PDF 数据")
            stream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var copied = 0L
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        copied += count
                        if (copied - lastReport >= 32L * 1024 * 1024) {
                            lastReport = copied
                            val pct = if (expectedSize > 0) {
                                ((copied * 5L) / expectedSize).toInt().coerceIn(0, 5)
                            } else 0
                            report("复制 PDF 工作副本（" +
                                (copied / 1024 / 1024) + "MB）", 0, 0, pct)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (expectedSize > 0 && temp.length() != expectedSize) {
                throw IOException("源文件拷贝不完整，预期 $expectedSize 字节，实际 " + temp.length())
            }
            if (temp.length() <= 0) throw IOException("原 PDF 文件为空")
            sourceFile.delete()
            if (!temp.renameTo(sourceFile)) throw IOException("无法提交 PDF 工作副本")
            report("PDF 工作副本就绪", 0, 0, 5, true)
        } finally {
            temp.delete()
        }
    }

    private suspend fun recognizePages(
        source: File,
        pagesDir: File,
        maxDimension: Int
    ): Int {
        val descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                val total = renderer.pageCount
                if (total <= 0) throw IOException("PDF 没有有效页面")

                val recognizer = TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build()
                )
                try {
                    for (index in 0 until total) {
                        coroutineContext.ensureActive()
                        if (isStopped) throw CancellationException("OCR 已取消")

                        val previous = OcrPageJournal.read(pagesDir, index)
                        if (previous != null) {
                            report("复用已识别的页面", index + 1, total,
                                5 + (((index + 1) * 87L) / total).toInt())
                            continue
                        }

                        report("正在识别", index + 1, total,
                            5 + ((index * 87L) / total).toInt())

                        renderer.openPage(index).use { page ->
                            val (width, height) = targetBitmapSize(
                                page.width, page.height, maxDimension, MAX_OCR_PIXELS
                            )
                            val bitmap = Bitmap.createBitmap(
                                width, height, Bitmap.Config.ARGB_8888
                            )
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(
                                    bitmap, null, null,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )

                                val result = Tasks.await(
                                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                                )
                                val lines = result.textBlocks.flatMap { it.lines }
                                    .mapNotNull { line ->
                                        val rect = line.boundingBox ?: return@mapNotNull null
                                        val text = sanitize(line.text)
                                        if (text.isBlank() || rect.isEmpty) null
                                        else OcrPageJournal.Line(text, rect)
                                    }
                                OcrPageJournal.write(
                                    pagesDir, index, OcrPageJournal.Page(width, height, lines)
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        report("已识别", index + 1, total,
                            5 + (((index + 1) * 87L) / total).toInt())
                    }
                } finally {
                    recognizer.close()
                }
                return total
            }
        }
    }

    /**
     * PDFBox is opened only after OCR resources have been closed.
     * Loading from a File avoids an extra input-stream scratch copy and
     * lets PDFBox use a buffered random-access file reader.
     */
    private suspend fun writeSearchablePdf(
        original: File,
        pagesDir: File,
        scratch: File,
        expectedPages: Int,
        openOutput: () -> java.io.OutputStream
    ) {
        PDDocument.load(
            original,
            MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
        ).use { document ->
            if (document.isEncrypted) throw IOException("此版本暂不支持加密 PDF")
            if (document.numberOfPages != expectedPages) {
                throw IOException("PDF 页面数量不一致：渲染 $expectedPages 页，编辑 " +
                    document.numberOfPages + " 页")
            }

            PdfSystemFontResolver(document, TAG).use { fonts ->
                for (index in 0 until expectedPages) {
                    coroutineContext.ensureActive()
                    if (isStopped) throw CancellationException("任务已取消")
                    val page = OcrPageJournal.read(pagesDir, index)
                        ?: throw IOException("第 " + (index + 1) + " 页的 OCR 记录缺失")
                    if (page.lines.isNotEmpty()) {
                        appendInvisibleLayer(
                            document, document.getPage(index), page, fonts
                        )
                    }
                    val pct = 92 + (((index + 1) * 5L) / expectedPages).toInt()
                    report("写入文字层", index + 1, expectedPages, pct)
                }

                coroutineContext.ensureActive()
                report("保存完整 PDF（可能需要数分钟）", expectedPages, expectedPages, 98, true)
                openOutput().use { stream ->
                    document.save(stream)
                    stream.flush()
                }
            }
        }
    }

    private fun appendInvisibleLayer(
        document: PDDocument,
        page: PDPage,
        ocrPage: OcrPageJournal.Page,
        fonts: PdfSystemFontResolver
    ) {
        val crop = page.cropBox ?: page.mediaBox ?: return
        val rotation = normalizeRotation(page.rotation)
        val (displayW, displayH) = displaySize(rotation, crop.width, crop.height)
        val m = displayToUser(
            rotation, crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height
        )
        val sx = displayW / ocrPage.width.toFloat()
        val sy = displayH / ocrPage.height.toFloat()

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { out ->
            out.saveGraphicsState()
            try {
                out.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                for (line in ocrPage.lines) {
                    val bounds = line.rect
                    val boxW = max(0.5f, bounds.width() * sx)
                    val boxH = max(1f, bounds.height() * sy)
                    val x = bounds.left * sx
                    val y = displayH - bounds.bottom * sy + boxH * 0.12f
                    val font = fonts.resolve(line.text, false)
                    val fontSize = (boxH * 0.88f).coerceIn(1f, 96f)
                    val naturalWidth = font.getStringWidth(line.text) / 1000f * fontSize
                    if (naturalWidth <= 0f) continue
                    val horizontalScale = (boxW / naturalWidth * 100f).coerceIn(5f, 1000f)

                    var beganText = false
                    try {
                        out.beginText()
                        beganText = true
                        out.setRenderingMode(RenderingMode.NEITHER)
                        out.setFont(font, fontSize)
                        out.setHorizontalScaling(horizontalScale)
                        out.newLineAtOffset(x, y)
                        out.showText(line.text)
                        out.endText()
                        beganText = false
                    } finally {
                        if (beganText) runCatching { out.endText() }
                    }
                }
            } finally {
                out.restoreGraphicsState()
            }
        }
    }

    private fun targetBitmapSize(
        width: Int, height: Int, maxDimension: Int, maxPixels: Long
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val byDimension = maxDimension.toDouble() / max(width, height).toDouble()
        val byPixels = sqrt(maxPixels.toDouble() / (width.toLong() * height.toLong()).toDouble())
        val scale = min(byDimension, byPixels).coerceIn(0.001, 4.0)
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    private fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

    private fun normalizeRotation(rotation: Int): Int = ((rotation % 360) + 360) % 360

    private fun displaySize(rotation: Int, width: Float, height: Float): Pair<Float, Float> =
        if (normalizeRotation(rotation) % 180 == 0) width to height else height to width

    private fun displayToUser(
        rotation: Int, llx: Float, lly: Float, width: Float, height: Float
    ): FloatArray = when (normalizeRotation(rotation)) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, llx + width, lly)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, llx + width, lly + height)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, llx, lly + height)
        else -> floatArrayOf(1f, 0f, 0f, 1f, llx, lly)
    }

    private suspend fun report(
        stage: String,
        page: Int,
        total: Int,
        percent: Int,
        forceNotification: Boolean = false
    ) {
        activeStage = stage
        activePage = page
        activeTotal = total
        setProgress(
            workDataOf(
                "stage" to stage,
                "page" to page,
                "total" to total,
                "percent" to percent
            )
        )
        if (forceNotification || lastForegroundPage < 0 || page - lastForegroundPage >= 5) {
            lastForegroundPage = page
            setForeground(makeForeground(
                if (total > 0) "$stage · $page/$total" else stage,
                percent
            ))
        }
    }

    private fun makeForeground(text: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MuOCR processing",
                    NotificationManager.IMPORTANCE_LOW)
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
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun failure(message: String): Result =
        Result.failure(workDataOf(
            "error" to message.take(600),
            "stage" to activeStage,
            "page" to activePage,
            "total" to activeTotal
        ))

    private data class SourceMetadata(val size: Long, val modified: Long)

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_MAX_DIMENSION = "max_dimension"
        private const val TAG = "MuOCR"
        private const val CHANNEL_ID = "muocr"
        private const val NOTIFICATION_ID = 2301
        private const val MAX_OCR_PIXELS = 4_500_000L
    }
}
