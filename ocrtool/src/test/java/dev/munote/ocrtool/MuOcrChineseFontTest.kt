package dev.munote.ocrtool

import org.apache.fontbox.ttf.TTFParser
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.LinkedHashSet

/**
 * Validates the same pinning and CJK glyph-subsetting path used by MuOCR.
 * The fallback font is prepared by Gradle before this test runs.
 */
class MuOcrChineseFontTest {
    private fun bundledFont(): File {
        val path = File("build/generated/muocrFonts/fonts/MuOCR-NotoSansSC.ttf")
        check(path.isFile) { "Missing bundled CJK font: ${path.absolutePath}" }
        assertEquals(17_772_300L, path.length())
        return path
    }

    @Test
    fun pinnedCjkFontHasTrueTypeGlyfAndLocaOutlines() {
        TTFParser().parse(bundledFont()).use { font ->
            assertTrue("Expected TrueType glyf outlines", font.tableMap.containsKey("glyf"))
            assertTrue("Expected TrueType loca table", font.tableMap.containsKey("loca"))
            assertTrue("CFF outlines are unsupported", !font.tableMap.containsKey("CFF "))
        }
    }

    @Test
    fun invisibleChineseGlyphLayerIsSearchableAfterIncrementalSave() {
        val dir = Files.createTempDirectory("muocr-cjk").toFile()
        val phrase = "高等数学基础三十讲中文文字层测试"
        try {
            val base = File(dir, "original.pdf")
            PDDocument().use { document ->
                document.addPage(PDPage())
                document.save(base)
            }
            val patch = File(dir, "patch.pdfdelta")
            val output = File(dir, "final.pdf")
            PDDocument.load(base).use { document ->
                val font = PDType0Font.load(document, bundledFont(), true)
                val page = document.getPage(0)
                PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.beginText()
                    stream.setRenderingMode(RenderingMode.NEITHER)
                    stream.setFont(font, 12f)
                    stream.newLineAtOffset(24f, 600f)
                    stream.showText(phrase)
                    stream.endText()
                }
                // PDFBox does not automatically subset embedded fonts when
                // saving incrementally. The Android resolver does this too.
                font.subset()
                val touched = LinkedHashSet<COSDictionary>()
                page.cosObject.setNeedToBeUpdated(true)
                touched.add(page.cosObject)
                page.resources.cosObject.setNeedToBeUpdated(true)
                touched.add(page.resources.cosObject)
                document.documentCatalog.cosObject.setNeedToBeUpdated(true)
                touched.add(document.documentCatalog.cosObject)
                document.documentCatalog.pages.cosObject.setNeedToBeUpdated(true)
                touched.add(document.documentCatalog.pages.cosObject)
                val writer = IncrementalPdfPatchOutputStream(patch, base.length())
                writer.use { document.saveIncremental(it, touched) }
                writer.verify()
            }
            base.copyTo(output)
            output.appendBytes(patch.readBytes())
            assertEquals(true, output.readBytes().copyOfRange(0, base.length().toInt())
                .contentEquals(base.readBytes()))
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                assertTrue("Chinese Unicode was lost after font subsetting",
                    PDFTextStripper().getText(document).contains(phrase))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
