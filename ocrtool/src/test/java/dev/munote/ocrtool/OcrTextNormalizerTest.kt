package dev.munote.ocrtool

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextNormalizerTest {
    @Test fun cachedTraditionalTitleBecomesSimplified() {
        assertEquals("选择题", OcrTextNormalizer.forSearchLayer("选择題\r"))
        assertEquals("选择题", OcrTextNormalizer.forSearchLayer("選擇題"))
    }

    @Test fun removesZeroWidthAndControlCharacters() {
        assertEquals("选择题 ABC", OcrTextNormalizer.forSearchLayer(
            "\u200B選擇題\r\n  ABC\u0000\u200D"
        ))
    }

    @Test fun keepsMathematicalExpressions() {
        assertEquals("求导 x² + y = 3",
            OcrTextNormalizer.forSearchLayer("求導 x² + y = 3"))
    }
}
