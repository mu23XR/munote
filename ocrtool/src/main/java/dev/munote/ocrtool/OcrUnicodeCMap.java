package dev.munote.ocrtool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Exact OCR Unicode text mapping for a PDF Type0 TrueType font.
 *
 * PDFBox-Android's default ToUnicode generator takes the FIRST Unicode
 * code point returned for a glyph. CJK glyphs often map to both ordinary
 * Han characters and visually identical Kangxi radicals (U+2F00..2FDF).
 * As a result a PDF can render correctly but cannot search/copy normal
 * Chinese. The original OCR codepoint is authoritative.
 *
 * Input map: original TrueType glyph ID (used as the original PDF CID) ->
 * codepoint from the exact OCR string displayed with that font.
 */
public final class OcrUnicodeCMap {
    private OcrUnicodeCMap() {}

    private static void appendHex(Writer writer, int unit) throws IOException {
        final char[] HEX = "0123456789ABCDEF".toCharArray();
        for (int shift = 12; shift >= 0; shift -= 4) {
            writer.write(HEX[(unit >>> shift) & 0xf]);
        }
    }

    public static byte[] encode(Map<Integer, Integer> cidToCodePoint) throws IOException {
        TreeMap<Integer, Integer> sorted = new TreeMap<>(cidToCodePoint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(bytes, StandardCharsets.US_ASCII);
        writer.write("/CIDInit /ProcSet findresource begin\n");
        writer.write("12 dict begin\nbegincmap\n");
        writer.write("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        writer.write("/CMapName /MuOCR-ExactUnicode def\n");
        writer.write("/CMapType 2 def\n");
        writer.write("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");

        int written = 0;
        int total = sorted.size();
        for (Map.Entry<Integer, Integer> entry : sorted.entrySet()) {
            int cid = entry.getKey();
            int codePoint = entry.getValue();
            if (cid <= 0 || cid > 0xffff ||
                !Character.isValidCodePoint(codePoint) ||
                (codePoint >= 0xd800 && codePoint <= 0xdfff)) {
                throw new IOException("Invalid OCR ToUnicode mapping CID=" + cid +
                    " codepoint=" + codePoint);
            }
            if (written % 100 == 0) {
                writer.write(Math.min(100, total - written) + " beginbfchar\n");
            }
            writer.write("<");
            appendHex(writer, cid);
            writer.write("> <");
            for (char unit : Character.toChars(codePoint)) {
                appendHex(writer, unit);
            }
            writer.write(">\n");
            written++;
            if (written % 100 == 0 || written == total) {
                writer.write("endbfchar\n");
            }
        }
        writer.write("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        writer.flush();
        return bytes.toByteArray();
    }
}
