package dev.munote.ocrtool

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Low-heap incremental writer: writes only 12 pages per PDDocument lifetime.
 *
 * PDFBox's saveIncremental() copies the original PDF into the given output stream
 * before writing an appended PDF revision. DeltaOnlyOutputStream drops that
 * unchanged prefix and stages only the newly generated revision on disk.
 * We then append that revision to the working source PDF using a durable
 * transaction marker, so a killed process can truncate an incomplete commit.
 *
 * The original PDF objects/images are not rasterized or rewritten.
 */
internal class IncrementalOcrPdfExporter(
    private val context: Context,
    private val workingPdf: File,
    private val pagesDir: File,
    private val scratchDir: File,
    private val pageCount: Int,
    private val report: suspend (String, Int, Int, Int) -> Unit
) {
    private val jobDir: File = workingPdf.parentFile!!
    private val cursorFile = File(jobDir, "incremental_export_progress.json")
    private val rollbackFile = File(jobDir, "incremental_append_transaction.json")
    private val deltaFile = File(jobDir, "incremental_delta.part")

    private data class Checkpoint(val cursor: Int, val size: Long)
    private data class Transaction(val cursor: Int, val end: Int, val oldSize: Long, val newSize: Long)

    suspend fun exportTo(destination: Uri) {
        require(pageCount > 0)
        recover()
        var cp = readCheckpoint() ?: Checkpoint(0, workingPdf.length())
        require(cp.cursor in 0..pageCount) { "增量导出进度损坏" }
        require(cp.size == workingPdf.length()) {
            "PDF 工作文件长度与进度记录不一致，请保留应用数据并反馈"
        }

        while (cp.cursor < pageCount) {
            coroutineContext.ensureActive()
            val begin = cp.cursor
            val end = min(pageCount, begin + BATCH_PAGES)
            report("增量写入文字层", begin, pageCount, progress(begin))

            val before = workingPdf.length()
            check(before == cp.size)
            stageIncrement(begin, end, before)
            coroutineContext.ensureActive()

            val deltaSize = deltaFile.length()
            if (deltaSize <= 0) throw IOException("第 " + (begin + 1) + " 页开始的 PDF 增量为空")
            val txn = Transaction(begin, end, before, before + deltaSize)
            writeJsonAtomically(rollbackFile, JSONObject()
                .put("cursor", begin)
                .put("end", end)
                .put("oldSize", before)
                .put("newSize", txn.newSize)
            )

            appendAndSync(before)
            writeCheckpoint(Checkpoint(end, txn.newSize))
            rollbackFile.delete()
            deltaFile.delete()
            cp = Checkpoint(end, txn.newSize)

            // The previous PDDocument, its COS tree, CJK fonts and scratch
            // buffers are now closed. The next loop opens a fresh document.
            report("已完成文字层批次", end, pageCount, progress(end))
        }

        report("复制完成的 PDF 到目标位置", pageCount, pageCount, 99)
        context.contentResolver.openOutputStream(destination, "wt").use { out ->
            if (out == null) throw IOException("无法打开目标 PDF，请换一个保存位置")
            workingPdf.inputStream().buffered(128 * 1024).use { source ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n > 0) out.write(buffer, 0, n)
                }
                out.flush()
            }
        }
    }

    private fun progress(completed: Int): Int =
        92 + ((completed * 6L) / pageCount).toInt()

    private suspend fun stageIncrement(begin: Int, end: Int, oldSize: Long) {
        if (deltaFile.exists() && !deltaFile.delete()) {
            throw IOException("无法清理上一批残留增量")
        }
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(scratchDir)
        try {
            PDDocument.load(workingPdf, memory).use { doc ->
                if (doc.isEncrypted) throw IOException("暂不支持加密 PDF")
                if (doc.numberOfPages != pageCount) {
                    throw IOException("PDF 页数变化：" + doc.numberOfPages + "，OCR 记录 " + pageCount)
                }

                PdfSystemFontResolver(doc, TAG).use { fonts ->
                    for (index in begin until end) {
                        coroutineContext.ensureActive()
                        val ocr = OcrPageJournal.read(pagesDir, index)
                            ?: throw IOException("缺少第 " + (index + 1) + " 页 OCR 缓存")
                        if (ocr.lines.isNotEmpty()) {
                            val page = doc.getPage(index)
                            appendInvisibleLayer(doc, page, ocr, fonts)
                            markForIncrementalSave(doc, page)
                        }
                    }

                    // PDFBox-Android's saveIncremental() does NOT call
                    // PDDocument.save()'s font.subset() loop. Embedded CJK
                    // fonts must be finalized before incremental serialization.
                    fonts.prepareForIncrementalSave()

                    FileOutputStream(deltaFile).use { fileOut ->
                        val tailOnly = DeltaOnlyOutputStream(fileOut, oldSize)
                        doc.saveIncremental(tailOnly)
                        if (tailOnly.bytesRemaining != 0L) {
                            throw IOException("PDFBox 增量序列化没有写完原文件部分")
                        }
                    }
                }
            }
            if (deltaFile.length() < 10L) {
                throw IOException("PDFBox 未产生有效 PDF 增量")
            }
        } catch (t: Throwable) {
            deltaFile.delete()
            throw t
        } finally {
            // PDFBox ScratchFile has been closed. Never accumulate scratch
            // from one batch into the next.
            scratchDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun markForIncrementalSave(doc: PDDocument, page: PDPage) {
        page.cosObject.setNeedToBeUpdated(true)
        // The /Contents reference or /Resources /Font dictionary was changed.
        // Mark every dictionary on the path back to the document catalog.
        page.resources?.cosObject?.apply {
            setNeedToBeUpdated(true)
            getCOSDictionary(COSName.FONT)?.setNeedToBeUpdated(true)
        }
        var parent: COSDictionary? = page.cosObject.getCOSDictionary(COSName.PARENT)
        var iterations = 0
        while (parent != null && iterations++ < 32) {
            parent.setNeedToBeUpdated(true)
            parent = parent.getCOSDictionary(COSName.PARENT)
        }
        doc.documentCatalog.pages.cosObject.setNeedToBeUpdated(true)
        doc.documentCatalog.cosObject.setNeedToBeUpdated(true)
    }

    private fun appendInvisibleLayer(
        doc: PDDocument,
        page: PDPage,
        ocr: OcrPageJournal.Page,
        fonts: PdfSystemFontResolver
    ) {
        val crop = page.cropBox ?: page.mediaBox ?: return
        val rot = ((page.rotation % 360) + 360) % 360
        val width = if (rot == 90 || rot == 270) crop.height else crop.width
        val height = if (rot == 90 || rot == 270) crop.width else crop.height
        val m = when (rot) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, crop.lowerLeftX + crop.width, crop.lowerLeftY)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f,
                crop.lowerLeftX + crop.width, crop.lowerLeftY + crop.height)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, crop.lowerLeftX, crop.lowerLeftY + crop.height)
            else -> floatArrayOf(1f, 0f, 0f, 1f, crop.lowerLeftX, crop.lowerLeftY)
        }
        val sx = width / ocr.width.toFloat()
        val sy = height / ocr.height.toFloat()

        PDPageContentStream(
            doc, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { out ->
            out.saveGraphicsState()
            try {
                out.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
                for (line in ocr.lines) {
                    val box = line.rect
                    val boxWidth = max(0.5f, box.width() * sx)
                    val boxHeight = max(1f, box.height() * sy)
                    val x = box.left * sx
                    val y = height - box.bottom * sy + boxHeight * 0.12f
                    val font = fonts.resolve(line.text, false)
                    val fontSize = (boxHeight * 0.88f).coerceIn(1f, 96f)
                    val naturalWidth = font.getStringWidth(line.text) * fontSize / 1000f
                    if (naturalWidth <= 0f) continue
                    val horizontal = (boxWidth / naturalWidth * 100f).coerceIn(5f, 1000f)

                    out.beginText()
                    try {
                        out.setRenderingMode(RenderingMode.NEITHER)
                        out.setFont(font, fontSize)
                        out.setHorizontalScaling(horizontal)
                        out.newLineAtOffset(x, y)
                        out.showText(line.text)
                    } finally {
                        out.endText()
                    }
                }
            } finally {
                out.restoreGraphicsState()
            }
        }
    }

    /**
     * saveIncremental always writes the full source PDF as a prefix.
     * Discard that prefix, writing ONLY the new PDF revision to a temporary
     * file. The PDF offsets remain valid because COSWriter counted that prefix.
     */
    private class DeltaOnlyOutputStream(
        private val sink: OutputStream,
        var bytesRemaining: Long
    ) : OutputStream() {
        override fun write(b: Int) {
            if (bytesRemaining > 0) bytesRemaining--
            else sink.write(b)
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            val skip = min(bytesRemaining, len.toLong()).toInt()
            bytesRemaining -= skip
            if (len > skip) sink.write(b, off + skip, len - skip)
        }
        override fun flush() = sink.flush()
        override fun close() = sink.close()
    }

    private fun appendAndSync(expectedLength: Long) {
        val bytes = ByteArray(128 * 1024)
        RandomAccessFile(workingPdf, "rw").use { target ->
            if (target.length() != expectedLength) {
                throw IOException("增量提交前 PDF 长度发生变化")
            }
            target.seek(expectedLength)
            deltaFile.inputStream().buffered().use { delta ->
                while (true) {
                    val n = delta.read(bytes)
                    if (n < 0) break
                    if (n > 0) target.write(bytes, 0, n)
                }
            }
            target.fd.sync()
        }
    }

    /**
     * A write is committed only when both the appended bytes and cursor file
     * are durable. On crash, a transaction marker distinguishes successful
     * commits from partial appends and permits rollback by truncation.
     */
    private fun recover() {
        if (rollbackFile.exists()) {
            val tx = JSONObject(rollbackFile.readText())
            val old = tx.getLong("oldSize")
            val new = tx.getLong("newSize")
            val end = tx.getInt("end")
            val start = tx.getInt("cursor")
            val cp = readCheckpoint()
            if (cp != null && cp.cursor == end && cp.size == new &&
                workingPdf.length() == new
            ) {
                rollbackFile.delete()
            } else {
                RandomAccessFile(workingPdf, "rw").use { raf ->
                    if (raf.length() < old) {
                        throw IOException("PDF 工作文件截断，无法从事务回滚")
                    }
                    raf.setLength(old)
                    raf.fd.sync()
                }
                writeCheckpoint(Checkpoint(start, old))
                rollbackFile.delete()
            }
        }
        deltaFile.delete()
    }

    private fun readCheckpoint(): Checkpoint? {
        if (!cursorFile.isFile) return null
        val data = JSONObject(cursorFile.readText())
        return Checkpoint(data.getInt("page"), data.getLong("length"))
    }

    private fun writeCheckpoint(cp: Checkpoint) {
        writeJsonAtomically(cursorFile, JSONObject()
            .put("page", cp.cursor)
            .put("length", cp.size)
        )
    }

    private fun writeJsonAtomically(file: File, data: JSONObject) {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(data.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!temp.renameTo(file)) {
                // Some Android volumes do not replace existing destinations.
                if (!file.delete() || !temp.renameTo(file)) {
                    throw IOException("无法提交进度文件 " + file.name)
                }
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val TAG = "MuOCRIncremental"
        private const val BATCH_PAGES = 12
    }
}
