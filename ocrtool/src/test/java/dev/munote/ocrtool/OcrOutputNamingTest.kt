package dev.munote.ocrtool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OcrOutputNamingTest {
    @Test fun differentSourcesHaveDifferentOutputs() {
        assertEquals("数学教材_OCR.pdf", OcrOutputNaming.choose("数学教材.pdf", emptyList()))
        assertEquals("408_OCR.pdf", OcrOutputNaming.choose("408.pdf", emptyList()))
    }

    @Test fun duplicateNamesProduceUniqueSuffixedFiles() {
        val existing = listOf("book_OCR.pdf", "book_OCR_2.pdf", "other.pdf")
        assertEquals("book_OCR_3.pdf", OcrOutputNaming.choose("book.pdf", existing))
    }

    @Test fun caseInsensitiveCollisionDoesNotOverwrite() {
        val first = OcrOutputNaming.choose("book.PDF", emptyList())
        val second = OcrOutputNaming.choose("book.pdf", listOf(first.uppercase()))
        assertNotEquals(first.lowercase(), second.lowercase())
    }

    @Test fun illegalFilenameCharactersCannotEscapeDirectory() {
        val output = OcrOutputNaming.choose("../含:乱码*的教材.pdf", emptyList())
        assertEquals(".._含_乱码_的教材_OCR.pdf", output)
    }
}
