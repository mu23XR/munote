package dev.munote.ocrtool

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented acceptance check for the entire incremental output path:
 * >2 batches, actual PDF parser, actual PDF text extraction, and intact
 * original raster page appearance.
 */
@RunWith(AndroidJUnit4::class)
class IncrementalOcrPdfExporterTest {

    @Test
    fun savesAcrossSeveralIncrementalBatchesAndPreservesOriginalPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        val root = File(context.cacheDir, "muocr-incremental-test-" + System.nanoTime())
        val source = File(root, "original.pdf")
        val output = File(root, "output.pdf")
        val journals = File(root, "pages")
        val scratch = File(root, "scratch")
        root.mkdirs()
        journals.mkdirs()
        scratch.mkdirs()

        try {
            PDDocument().use { document ->
                val photo = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                try {
                    photo.eraseColor(Color.rgb(30, 170, 80))
                    val raster = LosslessFactory.createFromImage(document, photo)
                    repeat(26) {
                        val page = PDPage(PDRectangle(300f, 300f))
                        document.addPage(page)
                        PDPageContentStream(document, page).use { stream ->
                            stream.drawImage(raster, 0f, 0f, 300f, 300f)
                        }
                    }
                } finally {
                    photo.recycle()
                }
                document.save(source)
            }

            val pixelBefore = colorAtCenter(source)

            repeat(26) { index ->
                OcrPageJournal.write(journals, index, OcrPageJournal.Page(
                    1000, 1000,
                    listOf(OcrPageJournal.Line(
                        "OCRPAGE" + (index + 1).toString().padStart(3, '0'),
                        Rect(40, 50, 400, 95)
                    ))
                ))
            }
            val exporter = IncrementalOcrPdfExporter(
                context, source, journals, scratch, 26
            ) { _, _, _, _ -> }
            exporter.exportTo(Uri.fromFile(output))

            assertTrue("Output must exist", output.isFile && output.length() > 0)
            PDDocument.load(output).use { result ->
                assertEquals(26, result.numberOfPages)
                val all = PDFTextStripper().getText(result)
                assertTrue("Missing first OCR page", all.contains("OCRPAGE001"))
                assertTrue("Missing middle OCR page", all.contains("OCRPAGE013"))
                assertTrue("Missing final OCR page", all.contains("OCRPAGE026"))
            }
            assertEquals("Scanned visual layer changed after OCR", pixelBefore, colorAtCenter(output))
            val lengthAfterFirstExport = source.length()
            exporter.exportTo(Uri.fromFile(output))
            assertEquals("Resume must not append OCR twice", lengthAfterFirstExport, source.length())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun colorAtCenter(file: File): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
                    try {
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp.getPixel(100, 100)
                    } finally {
                        bmp.recycle()
                    }
                }
            }
        }
    }
}
