package dev.munote.ocrtool

import java.util.Locale

/** Pure naming helper to avoid overwriting any previously exported PDF. */
internal object OcrOutputNaming {
    fun choose(sourceName: String, existingNames: Collection<String>): String {
        val base = sourceName.substringBeforeLast('.', sourceName)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim().take(85).ifBlank { "document" }

        val occupied = existingNames.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        var attempt = 0
        while (attempt < 100_000) {
            val candidate = if (attempt == 0) "${base}_OCR.pdf"
                else "${base}_OCR_${attempt + 1}.pdf"
            if (candidate.lowercase(Locale.ROOT) !in occupied) return candidate
            attempt++
        }
        throw IllegalStateException("输出目录含有过多同名文件")
    }
}
