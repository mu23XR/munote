package dev.munote.ocrtool

import java.nio.charset.StandardCharsets

/**
 * Correct glyph-ID -> original OCR Unicode mappings for PDFBox's Identity-H
 * embedded TrueType fonts.
 *
 * PDFBox's default ToUnicode generator uses the *first* Unicode codepoint
 * sharing each font glyph. Chinese CJK fonts frequently put compatibility
 * Kangxi radicals first, e.g. U+2F17 instead of U+5341 (十). That causes
 * searches for normal Chinese characters to fail despite a valid OCR layer.
 *
 * We instead capture the actual OCR codepoints when writing each glyph, then
 * create a ToUnicode CMap with those original codepoints.
 */
object UnicodeCMapBuilder {
    @JvmStatic
    fun build(gidToUnicode: Map<Int, Int>): ByteArray {
        require(gidToUnicode.isNotEmpty())
        val entries = gidToUnicode.entries
            .filter { (gid, cp) -> gid in 1..0xFFFF && Character.isValidCodePoint(cp) }
            .sortedBy { it.key }
        require(entries.isNotEmpty()) { "No valid Unicode glyph mappings" }

        val out = StringBuilder(300 + entries.size * 24)
        out.append("/CIDInit /ProcSet findresource begin\n")
        out.append("12 dict begin\nbegincmap\n")
        out.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        out.append("/CMapName /MuOCR-Identity-UCS def\n/CMapType 2 def\n")
        out.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")

        for (chunk in entries.chunked(100)) {
            out.append(chunk.size).append(" beginbfchar\n")
            for ((gid, cp) in chunk) {
                val code = gid.toString(16).uppercase().padStart(4, '0')
                val utf16 = String(Character.toChars(cp)).toByteArray(Charsets.UTF_16BE)
                    .joinToString("") { b ->
                        (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
                    }
                out.append('<').append(code).append("> <")
                    .append(utf16).append(">\n")
            }
            out.append("endbfchar\n")
        }
        out.append("endcmap\nCMapName currentdict /CMap defineresource pop\n")
        out.append("end\nend\n")
        return out.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }
}
