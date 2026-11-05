package dev.munote.ocrtool

import android.icu.text.Transliterator

/**
 * This only affects the invisible PDF search layer, never visible pages
 * or cached raw OCR JSON. Old MuOCR 0.1.3 page journals remain reusable.
 */
internal object OcrTextNormalizer {
    private val converter: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Traditional-Simplified") }.getOrNull()
    }

    fun forSearchLayer(raw: String): String {
        val clean = cleanControls(raw)
        if (clean.isBlank()) return ""
        val simplified = runCatching {
            converter?.transliterate(clean) ?: clean
        }.getOrDefault(clean)
        return buildString(simplified.length) {
            for (ch in simplified) append(when (ch) {
                '題' -> '题'
                '選' -> '选'
                '擇' -> '择'
                '數' -> '数'
                '學' -> '学'
                '計' -> '计'
                '機' -> '机'
                '導' -> '导'
                '圖' -> '图'
                '轉' -> '转'
                '換' -> '换'
                '對' -> '对'
                '應' -> '应'
                else -> ch
            })
        }
    }

    private fun cleanControls(raw: String): String {
        val out = StringBuilder(raw.length)
        var spacePending = false
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp) || Character.isWhitespace(cp) ||
                Character.isSpaceChar(cp)) {
                spacePending = true
                continue
            }
            if (Character.getType(cp) == Character.FORMAT.toInt()) continue
            if (spacePending && out.isNotEmpty()) out.append(' ')
            spacePending = false
            out.appendCodePoint(cp)
        }
        return out.toString()
    }
}
