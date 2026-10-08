package dev.munote.ocrtool

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * PDFBox-Android's saveIncremental() emits the entire original PDF followed
 * by the incremental PDF revision. Store only the revision on disk.
 *
 * The original bytes are NEVER written back into the source during this call.
 * The caller must close PDDocument before appending the completed patch to
 * its private working copy. We do not modify the user's input file.
 *
 * Note: PDFBox-Android uses a ByteArrayOutputStream internally for the revision.
 * The caller MUST bound each batch of page edits to avoid growing that buffer.
 */
internal class IncrementalPdfPatchOutputStream(
    patch: File,
    private val originalLength: Long
) : OutputStream() {
    private val stream = BufferedOutputStream(FileOutputStream(patch), 128 * 1024)
    private var totalSeen = 0L
    private var patchBytes = 0L
    private var closed = false

    init {
        require(originalLength > 0L) { "Invalid PDF source size" }
    }

    override fun write(value: Int) {
        ensureOpen()
        if (totalSeen >= originalLength) {
            stream.write(value)
            patchBytes++
        }
        totalSeen++
    }

    override fun write(data: ByteArray, offset: Int, length: Int) {
        ensureOpen()
        if (offset < 0 || length < 0 || offset > data.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return
        val bytesToDiscard = (originalLength - totalSeen).coerceAtLeast(0L)
            .coerceAtMost(length.toLong()).toInt()
        val tailLength = length - bytesToDiscard
        if (tailLength > 0) {
            stream.write(data, offset + bytesToDiscard, tailLength)
            patchBytes += tailLength.toLong()
        }
        totalSeen += length.toLong()
    }

    fun verify() {
        if (!closed) throw IOException("Incremental PDF patch stream is not closed")
        if (totalSeen < originalLength) {
            throw IOException("PDFBox emitted fewer source bytes than expected")
        }
        if (patchBytes <= 0L) {
            throw IOException("PDFBox did not emit an incremental PDF revision")
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("PDF patch stream is closed")
    }

    override fun flush() = stream.flush()

    override fun close() {
        if (!closed) {
            closed = true
            stream.close()
        }
    }
}
