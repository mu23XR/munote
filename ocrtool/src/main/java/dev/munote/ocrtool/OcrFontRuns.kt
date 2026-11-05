package dev.munote.ocrtool

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import java.io.IOException

/**
 * A single OCR line may contain CJK, Latin and mathematical glyphs which do
 * not coexist in the same font. Split into embedded-font-compatible runs.
 * A missing symbol becomes a space in the invisible layer, never a changed
 * visible page. Keep the instance only for a single PDDocument batch.
 */
internal class OcrFontRuns(private val resolver: PdfSystemFontResolver) {
    data class Run(val font: PDFont, val text: String)

    private val assigned = HashMap<Int, PDFont>()
    private val missing = HashSet<Int>()

    @Throws(IOException::class)
    fun split(line: String): List<Run> {
        val runs = ArrayList<Run>()
        val current = StringBuilder()
        var activeFont: PDFont? = null
        var hanCount = 0
        var resolvedHan = 0

        fun flush() {
            val font = activeFont
            if (font != null && current.isNotEmpty()) {
                runs.add(Run(font, current.toString()))
                current.setLength(0)
            }
        }

        var offset = 0
        while (offset < line.length) {
            val cp = line.codePointAt(offset)
            offset += Character.charCount(cp)
            if (Character.isISOControl(cp) ||
                Character.getType(cp) == Character.FORMAT.toInt()) continue

            val isHan = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
            if (isHan) hanCount++
            val character = String(Character.toChars(cp))
            var font = assigned[cp]
            if (font == null && cp !in missing) {
                try {
                    font = resolver.resolve(character, false)
                    assigned[cp] = font
                } catch (error: IOException) {
                    missing.add(cp)
                    Log.w("MuOCR", "Unsupported OCR glyph U+" +
                        cp.toString(16) + " in invisible text layer", error)
                }
            }
            val supported = font != null
            if (isHan && supported) resolvedHan++
            // Preserve the line boundary and searchability of other glyphs.
            val emitted = if (supported) character else " "
            if (font == null) font = resolver.resolve(" ", false)
            if (activeFont !== font) {
                flush()
                activeFont = font
            }
            current.append(emitted)
        }
        flush()
        if (hanCount > 0 && resolvedHan == 0) {
            throw IOException("中文 OCR 行没有可嵌入字体，请检查内置 Noto Sans SC 字库")
        }
        return runs
    }
}
