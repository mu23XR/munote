package dev.munote.ocrtool

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.LinkedHashSet

/**
 * Integration test with desktop PDFBox. The Android code uses PDFBox-Android
 * (same incremental writer design) with a 16-page bounded batch.
 * This tests the actual PDF format: original visible content remains identical,
 * each new text line is extractable, and 583 pages span multiple revisions.
 */
class PdfIncrementalRevisionIntegrationTest {
    @Test
    fun severalBatchesRemainSearchableAndDoNotChangeVisiblePages() {
        val dir = Files.createTempDirectory("muocr-pdf-real").toFile()
        try {
            val initial = File(dir, "initial.pdf")
            PDDocument().use { document ->
                repeat(6) { index ->
                    val page = PDPage(PDRectangle.A4)
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        stream.setNonStrokingColor(180, 190, 210)
                        stream.addRect(10f + index, 20f, 100f, 80f)
                        stream.fill()
                    }
                }
                document.save(initial)
            }
            val work = File(dir, "work.pdf")
            initial.copyTo(work)
            val initialBytes = initial.readBytes()
            val originalPixels = renderPage(initial, 2)

            var start = 0
            while (start < 6) {
                val stop = (start + 2).coerceAtMost(6)
                val patch = File(dir, "batch-$start.patch")
                PDDocument.load(work).use { document ->
                    val forced = LinkedHashSet<COSDictionary>()
                    for (index in start until stop) {
                        val page = document.getPage(index)
                        PDPageContentStream(
                            document, page,
                            PDPageContentStream.AppendMode.APPEND, true, true
                        ).use { stream ->
                            stream.beginText()
                            stream.setRenderingMode(RenderingMode.NEITHER)
                            stream.setFont(PDType1Font.HELVETICA, 12f)
                            stream.newLineAtOffset(40f, 200f)
                            stream.showText("PAGE-INDEX-$index")
                            stream.endText()
                        }

                        page.cosObject.setNeedToBeUpdated(true)
                        forced.add(page.cosObject)
                        page.resources.cosObject.setNeedToBeUpdated(true)
                        forced.add(page.resources.cosObject)
                        document.documentCatalog.pages.cosObject.setNeedToBeUpdated(true)
                        forced.add(document.documentCatalog.pages.cosObject)
                        document.documentCatalog.cosObject.setNeedToBeUpdated(true)
                        forced.add(document.documentCatalog.cosObject)
                    }

                    val writer = IncrementalPdfPatchOutputStream(patch, work.length())
                    writer.use { document.saveIncremental(it, forced) }
                    writer.verify()
                }
                FileOutputStream(work, true).use { output ->
                    FileInputStream(patch).use { it.copyTo(output) }
                }
                start = stop
            }

            assertTrue(work.length() > initial.length())
            assertArrayEquals(initialBytes, work.inputStream().use { it.readNBytes(initialBytes.size) })
            assertArrayEquals(originalPixels, renderPage(work, 2))

            PDDocument.load(work).use { document ->
                assertEquals(6, document.numberOfPages)
                for (i in 0 until 6) {
                    val extractor = PDFTextStripper()
                    extractor.startPage = i + 1
                    extractor.endPage = i + 1
                    assertTrue("OCR text missing on page $i",
                        extractor.getText(document).contains("PAGE-INDEX-$i"))
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fiveHundredEightyThreePagesCanCommitInSmallBatches() {
        val dir = Files.createTempDirectory("muocr-583").toFile()
        try {
            val original = File(dir, "original.pdf")
            PDDocument().use { document ->
                repeat(583) { document.addPage(PDPage(PDRectangle.A4)) }
                document.save(original)
            }
            val working = File(dir, "working.pdf")
            original.copyTo(working)
            val journal = PdfExportJournal(dir)
            var state = journal.initialize(original.length(), 583)

            for (start in 0 until 583 step 16) {
                val end = (start + 16).coerceAtMost(583)
                val patch = File(dir, "small-revision.patch")
                PDDocument.load(working).use { document ->
                    val forced = LinkedHashSet<COSDictionary>()
                    for (i in start until end) {
                        val page = document.getPage(i)
                        PDPageContentStream(document, page).use { stream ->
                            stream.beginText()
                            stream.setRenderingMode(RenderingMode.NEITHER)
                            stream.setFont(PDType1Font.HELVETICA, 10f)
                            stream.newLineAtOffset(5f, 5f)
                            stream.showText("OCRPAGE-$i")
                            stream.endText()
                        }
                        page.cosObject.setNeedToBeUpdated(true)
                        forced.add(page.cosObject)
                    }
                    document.documentCatalog.pages.cosObject.setNeedToBeUpdated(true)
                    forced.add(document.documentCatalog.pages.cosObject)
                    document.documentCatalog.cosObject.setNeedToBeUpdated(true)
                    forced.add(document.documentCatalog.cosObject)
                    val out = IncrementalPdfPatchOutputStream(patch, working.length())
                    out.use { document.saveIncremental(it, forced) }
                    out.verify()
                }
                state = journal.append(working, patch, state, end)
                patch.delete()
            }

            assertEquals(583, journal.recover(working)!!.nextPage)
            PDDocument.load(working).use { document ->
                assertEquals(583, document.numberOfPages)
                for (i in intArrayOf(0, 302, 582)) {
                    val search = PDFTextStripper()
                    search.startPage = i + 1
                    search.endPage = i + 1
                    assertTrue(
                        "No searchable OCR on page $i",
                        search.getText(document).contains("OCRPAGE-$i")
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun renderPage(pdf: File, pageIndex: Int): IntArray =
        PDDocument.load(pdf).use { document ->
            val bitmap = PDFRenderer(document).renderImage(pageIndex, 0.45f)
            bitmap.getRGB(0, 0, bitmap.width, bitmap.height,
                IntArray(bitmap.width * bitmap.height), 0, bitmap.width)
        }
}
