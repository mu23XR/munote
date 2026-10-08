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
import androidx.work.ListenableWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSUpdateInfo
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.LinkedHashSet
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Two-pass large-PDF pipeline.
 *
 * Pass 1 only uses PdfRenderer + ML Kit. Each recognized page is journaled to disk.
 * Pass 2 closes both of those engines BEFORE opening PDFBox. A small batch
 * of pages is patched into a separate working PDF with incremental revisions,
 * and PDFBox is closed between batches to bound font and COS object memory.
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
    private var currentTaskId: String? = null
    private var lastDirectivePoll = 0L

    private class TaskPaused : RuntimeException("Task paused")
    private class TaskCancelled : RuntimeException("Task cancelled")


    override suspend fun doWork() = withContext(Dispatchers.IO) {
        if (inputData.getBoolean(KEY_QUEUE_MODE, false)) {
            return@withContext runQueue()
        }
        // Old single-document WorkManager requests remain supported during
        // upgrades from MuOCR v1.0.0.
        val input = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输入 PDF")
        val output = inputData.getString(KEY_OUTPUT_URI)?.let(Uri::parse)
            ?: return@withContext failure("缺少输出 PDF")
        runDocument(input, output, inputData.getInt(KEY_MAX_DIMENSION, 2200), null)
    }

    /**
     * One durable WorkManager supervisor loops serially through task entries.
     * A per-task pause/cancel is a cooperative flag, not a WorkManager
     * cancellation, so no unrelated queued work can be cancelled.
     */
    private suspend fun runQueue(): Result {
        val ledger = OcrQueueScheduler.store(applicationContext)
        ledger.recoverAfterWorkerRestart()
        // A process can be killed after recording SUCCESS but before deleting
        // the private 1GB workcopy; reclaim those finished directories here.
        ledger.snapshot().entries.filter {
            it.status == OcrQueueLedger.Status.DONE
        }.forEach { finished ->
            OcrQueueScheduler.cleanTaskCache(applicationContext, finished.id)
        }
        while (true) {
            coroutineContext.ensureActive()
            val entry = ledger.claimNext() ?: return Result.success()
            currentTaskId = entry.id
            lastForegroundPage = -1
            lastDirectivePoll = 0L

            var output = ""
            try {
                // A process can be killed while exporting. Delete an orphaned
                // partial destination, but preserve the private PDF journal.
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, entry.outputUri)
                val destination = OcrQueueScheduler.createOutputFile(applicationContext, entry)
                output = destination.toString()
                ledger.setOutput(entry.id, output)

                val result = runDocument(
                    Uri.parse(entry.inputUri), destination, entry.quality, entry.id
                )
                when (result) {
                    is ListenableWorker.Result.Success -> ledger.finish(
                        entry.id, OcrQueueLedger.Status.DONE
                    )
                    is ListenableWorker.Result.Failure -> {
                        val error = result.outputData.getString("error") ?: "文件处理失败"
                        ledger.finish(entry.id, OcrQueueLedger.Status.FAILED, error)
                        OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                        ledger.clearOutput(entry.id)
                    }
                    else -> {
                        ledger.finish(entry.id, OcrQueueLedger.Status.FAILED, "请重试")
                        OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                        ledger.clearOutput(entry.id)
                    }
                }
            } catch (_: TaskPaused) {
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.PAUSED)
            } catch (_: TaskCancelled) {
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.CANCELLED)
                OcrQueueScheduler.cleanTaskCache(applicationContext, entry.id)
            } catch (cancelled: CancellationException) {
                // Android stopped the whole worker. Keep all task checkpoints:
                // the next supervisor will re-claim the interrupted task.
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Queue item failed: ${entry.name}", error)
                OcrQueueScheduler.deleteIncompleteOutput(applicationContext, output)
                ledger.clearOutput(entry.id)
                ledger.finish(entry.id, OcrQueueLedger.Status.FAILED,
                    error.message ?: error.javaClass.simpleName)
            } finally {
                currentTaskId = null
            }
        }
    }

    private fun checkTaskInterruption(force: Boolean = false) {
        val id = currentTaskId ?: return
        val now = System.nanoTime()
        if (!force && now - lastDirectivePoll < 150_000_000L) return
        lastDirectivePoll = now
        val state = OcrQueueScheduler.store(applicationContext).stateOf(id)
        when (state) {
            OcrQueueLedger.Status.PAUSING, OcrQueueLedger.Status.PAUSED ->
                throw TaskPaused()
            OcrQueueLedger.Status.CANCELLING, OcrQueueLedger.Status.CANCELLED, null ->
                throw TaskCancelled()
        }
    }

    private suspend fun runDocument(
        input: Uri,
        output: Uri,
        selectedQuality: Int,
        taskId: String?
    ): Result {
        activeStage = "准备文件"
        activePage = 0
        activeTotal = 0
        lastForegroundPage = -1
        if (input == output) return failure("不能覆盖原 PDF，请另存到新文件")

        val maxDimension = selectedQuality.coerceIn(1200, 3200)
        val metadata = sourceMetadata(input)
        val rootDir = applicationContext.getExternalFilesDir("muocr-work")
            ?: File(applicationContext.filesDir, "muocr-work")
        val jobDir = if (taskId == null) File(rootDir, jobId(input, metadata))
            else OcrQueueScheduler.taskDirectory(applicationContext, taskId)
        val pagesDir = File(jobDir, "pages")
        val scratch = File(jobDir, "scratch")
        var completed = false

        return try {
            if (!jobDir.exists() && !jobDir.mkdirs()) {
                throw IOException("无法建立工作目录，检查存储空间")
            }
            if (!pagesDir.isDirectory && !pagesDir.mkdirs()) {
                throw IOException("无法建立 OCR 缓存目录")
            }
            if (!scratch.isDirectory && !scratch.mkdirs()) {
                throw IOException("无法建立 PDF 临时目录")
            }

            if (taskId != null) {
                importLegacyCacheIfPresent(rootDir, input, metadata, jobDir)
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
            writeSearchablePdf(original, pagesDir, scratch, jobDir, pageCount) {
                applicationContext.contentResolver.openOutputStream(output, "wt")
                    ?: throw IOException("无法打开输出文件")
            }

            report("已完成", pageCount, pageCount, 100, true)
            // Persist completion before deleting the task's work directory.
            // Without this, process death between export and queue finish
            // could force a completed 583-page document to OCR from scratch.
            if (taskId != null) {
                OcrQueueScheduler.store(applicationContext).finish(
                    taskId, OcrQueueLedger.Status.DONE
                )
            }
            completed = true
            Result.success(workDataOf("pages" to pageCount))
        } catch (signal: TaskPaused) {
            throw signal
        } catch (signal: TaskCancelled) {
            throw signal
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

    private fun importLegacyCacheIfPresent(
        root: File,
        input: Uri,
        metadata: SourceMetadata,
        job: File
    ) {
        val legacy = File(root, jobId(input, metadata))
        if (!legacy.isDirectory || legacy == job) return

        // Preserve the v1.0.0 page journal without sharing mutable state
        // across independent queue entries.
        val oldPages = File(legacy, "pages")
        val newPages = File(job, "pages")
        if (oldPages.isDirectory && newPages.listFiles().isNullOrEmpty()) {
            oldPages.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }
                ?.forEach { page ->
                    runCatching { page.copyTo(File(newPages, page.name), overwrite = false) }
                }
        }

        // If supported by the filesystem, a hard link reuses the 1GB input
        // bytes without consuming another 1GB. Fallback is the normal SAF copy.
        val original = File(legacy, "original.pdf")
        val linked = File(job, "original.pdf")
        if (original.isFile && !linked.exists()) {
            runCatching { java.nio.file.Files.createLink(linked.toPath(), original.toPath()) }
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
                        checkTaskInterruption()
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

                // Only start ML Kit if any page is missing. With 583 cached
                // OCR pages, resuming export must not load the OCR model.
                var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
                try {
                    for (index in 0 until total) {
                        coroutineContext.ensureActive()
                        checkTaskInterruption()
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

                                val engine = recognizer
                                    ?: TextRecognition.getClient(
                                        ChineseTextRecognizerOptions.Builder().build()
                                    ).also { recognizer = it }
                                val result = Tasks.await(
                                    engine.process(InputImage.fromBitmap(bitmap, 0))
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
                    recognizer?.close()
                }
                return total
            }
        }
    }

    /**
     * Each batch opens PDFBox, appends a limited number of page text streams,
     * saves a small incremental revision and closes PDFBox BEFORE the next batch.
     *
     * In PDFBox-Android the incremental writer internally keeps the revision
     * in a ByteArrayOutputStream; hence we keep every batch small (16 pages).
     * The source and all completed edits are stored on disk rather than heap.
     *
     * The caller's PDF remains untouched; "writing.pdf" is a private workcopy.
     * Checkpoints survive app cancellation or process death.
     */
    private suspend fun writeSearchablePdf(
        original: File,
        pagesDir: File,
        scratch: File,
        jobDir: File,
        expectedPages: Int,
        openOutput: () -> java.io.OutputStream
    ) {
        val working = File(jobDir, "writing.pdf")
        val journal = PdfExportJournal(jobDir)
        var checkpoint = journal.recover(working)
            ?.takeIf {
                it.originalLength == original.length() &&
                    it.pageCount == expectedPages
            }

        if (checkpoint == null) {
            // Crash/inconsistent state: start PDF writing again, not OCR.
            // Existing OcrPageJournal results will still be reused.
            working.delete()
            val temp = File(jobDir, "writing.pdf.part")
            temp.delete()
            try {
                val required = original.length() + max(300L * 1024 * 1024, original.length() / 4)
                if (jobDir.usableSpace < required) {
                    throw IOException("存储空间不足：需要至少 " +
                        (required / 1024 / 1024) + "MB 写入 PDF 工作副本")
                }
                report("准备可恢复的 PDF 导出副本", 0, expectedPages, 92, true)
                FileInputStream(original).use { input ->
                    FileOutputStream(temp).use { output ->
                        copyStreamWithCancellation(input, output)
                        output.fd.sync()
                    }
                }
                if (temp.length() != original.length()) {
                    throw IOException("PDF 工作副本没有完整写入")
                }
                if (!temp.renameTo(working)) {
                    throw IOException("无法提交 PDF 工作副本")
                }
                checkpoint = journal.initialize(original.length(), expectedPages)
            } finally {
                temp.delete()
            }
        } else {
            report("复用已提交的 PDF 文字层（到第 ${checkpoint.nextPage} 页）",
                checkpoint.nextPage, expectedPages,
                92 + ((checkpoint.nextPage * 6L) / expectedPages).toInt(), true)
        }

        var state = checkpoint ?: throw IOException("缺少 PDF 导出进度")
        var batchSize = BATCH_PAGES
        while (state.nextPage < expectedPages) {
            coroutineContext.ensureActive()
            checkTaskInterruption()
            if (isStopped) throw CancellationException("PDF 导出已取消")
            val start = state.nextPage
            val endExclusive = min(start + batchSize, expectedPages)
            val patch = File(scratch, "batch-${start + 1}-$endExclusive.patch")
            patch.delete()

            report("写入文字层（本批 ${start + 1}-$endExclusive 页）",
                start + 1, expectedPages,
                92 + ((start * 6L) / expectedPages).toInt(), true)
            try {
                val changed = writePageBatch(
                    working, pagesDir, scratch, patch,
                    expectedPages, start, endExclusive
                )
                coroutineContext.ensureActive()

                state = if (changed) {
                    journal.append(working, patch, state, endExclusive)
                } else {
                    journal.advanceWithoutChanges(state, endExclusive)
                }
                report("已提交文字层", endExclusive, expectedPages,
                    92 + ((endExclusive * 6L) / expectedPages).toInt(), true)
                patch.delete()
            } catch (oom: OutOfMemoryError) {
                // A heap failure may also happen DURING patch append. Reconcile
                // the on-disk transaction before any retry.
                patch.delete()
                state = journal.recover(working) ?: throw oom
                if (state.nextPage > start) {
                    // Commit succeeded; only the UI/reporting failed.
                    continue
                }
                if (batchSize <= 1) throw oom
                batchSize = max(1, batchSize / 2)
                Log.w(TAG, "PDFBox heap pressure: retrying at $batchSize pages per batch", oom)
                report("减小每批页数至 $batchSize 后重试",
                    start + 1, expectedPages,
                    92 + ((start * 6L) / expectedPages).toInt(), true)
                System.gc()
            } finally {
                patch.delete()
            }
        }

        // A completed work PDF can be re-exported without repeating OCR or
        // text layer generation if a user-selected document provider fails.
        report("将完整可搜索 PDF 写入目标文件", expectedPages, expectedPages, 99, true)
        openOutput().use { target ->
            FileInputStream(working).use { source ->
                copyStreamWithCancellation(source, target)
            }
            target.flush()
        }
    }

    /** PDFBox is fully released after each small batch. */
    private suspend fun writePageBatch(
        working: File,
        pagesDir: File,
        scratch: File,
        patch: File,
        expectedPages: Int,
        start: Int,
        endExclusive: Int
    ): Boolean {
        var changed = false
        val touched = LinkedHashSet<COSDictionary>()
        PDDocument.load(
            working,
            MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)
        ).use { document ->
            if (document.isEncrypted) throw IOException("此版本暂不支持加密 PDF")
            if (document.numberOfPages != expectedPages) {
                throw IOException("PDF 页面数不一致：当前 " + document.numberOfPages +
                    "，期望 " + expectedPages)
            }

            PdfSystemFontResolver(document, TAG, applicationContext).use { fonts ->
                for (index in start until endExclusive) {
                    coroutineContext.ensureActive()
                    checkTaskInterruption()
            if (isStopped) throw CancellationException("PDF 导出已取消")
                    val page = OcrPageJournal.read(pagesDir, index)
                        ?: throw IOException("第 " + (index + 1) + " 页 OCR 缓存丢失")
                    if (page.lines.isEmpty()) continue

                    val pdfPage = document.getPage(index)
                    appendInvisibleLayer(document, pdfPage, page, fonts)
                    markPageForIncrementalSave(document, pdfPage, touched)
                    changed = true
                    report("写入文字层", index + 1, expectedPages,
                        92 + (((index + 1) * 6L) / expectedPages).toInt())
                }
                if (changed) {
                    // PDFBox-Android 2.0.27.0 does not subset fonts when
                    // saveIncremental() is called. Without this the invisible
                    // Unicode layer may be unreadable/search may fail.
                    fonts.subsetFontsForIncrementalSave()
                    val patchWriter = IncrementalPdfPatchOutputStream(
                        patch, working.length()
                    )
                    patchWriter.use { writer ->
                        document.saveIncremental(writer, touched)
                    }
                    patchWriter.verify()
                }
            }
        }
        return changed
    }

    /**
     * PDFBox incremental saves only dictionaries reachable along a changed
     * page-tree path (or dictionaries explicitly forced to be written).
     * Mark the page, its resources /Font map, each /Parent, and the catalog.
     */
    private fun markPageForIncrementalSave(
        document: PDDocument,
        page: PDPage,
        touched: MutableSet<COSDictionary>
    ) {
        fun mark(dict: COSDictionary?) {
            if (dict != null) {
                dict.setNeedToBeUpdated(true)
                touched.add(dict)
            }
        }

        mark(document.documentCatalog.cosObject)
        var node: COSDictionary? = page.cosObject
        val visited = HashSet<COSDictionary>()
        while (node != null && visited.add(node)) {
            mark(node)
            node = node.getCOSDictionary(COSName.PARENT)
        }

        val resources = page.resources?.cosObject
        mark(resources)
        mark(resources?.getCOSDictionary(COSName.FONT))

        // /Contents is sometimes an existing indirect array, not a direct
        // child of the page dictionary. Mark it too so PDFBox writes its
        // new reference to the invisible text content stream.
        val contents = page.cosObject.getDictionaryObject(COSName.CONTENTS)
        if (contents is COSUpdateInfo) {
            contents.setNeedToBeUpdated(true)
        }
    }

    private suspend fun copyStreamWithCancellation(
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            coroutineContext.ensureActive()
            checkTaskInterruption()
            if (isStopped) throw CancellationException("文件复制已取消")
            checkTaskInterruption()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) output.write(buffer, 0, count)
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
        val changedStage = activeStage != stage
        activeStage = stage
        activePage = page
        activeTotal = total
        currentTaskId?.let { task ->
            OcrQueueScheduler.store(applicationContext).progress(
                task, stage, page, total, percent
            )
        }
        checkTaskInterruption(force = true)
        setProgress(
            workDataOf(
                "stage" to stage,
                "page" to page,
                "total" to total,
                "percent" to percent
            )
        )
        if (forceNotification || changedStage || lastForegroundPage < 0 ||
            page - lastForegroundPage >= 5) {
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
        const val KEY_QUEUE_MODE = "queue_mode"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_MAX_DIMENSION = "max_dimension"
        private const val TAG = "MuOCR"
        private const val CHANNEL_ID = "muocr"
        private const val NOTIFICATION_ID = 2301
        private const val MAX_OCR_PIXELS = 4_500_000L
        private const val BATCH_PAGES = 16
    }
}
