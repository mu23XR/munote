package dev.munote.ocrtool

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.util.Matrix
import java.io.IOException
import java.util.Locale
import kotlin.math.max

/**
 * Synthetic Identity-H OCR font. Invisible OCR text has no need for glyph
 * outlines: the PDF text layer consists of 16-bit CIDs and an explicit
 * ToUnicode CMap, which maps those CIDs to arbitrary Unicode code points.
 *
 * The /FontDescriptor is non-embedded: PDF viewers may substitute its outlines
 * when drawing, but Tr=3 (invisible text) means no glyph is painted.
 *
 * This completely avoids loading or subsetting Android OEM fonts, many of
 * which are OpenType/CFF and trigger "OTF fonts do not have a glyf table"
 * in PDFBox-Android's TrueType subsetting implementation.
 */
internal class InvisibleUnicodeTextLayer(
    private val document: PDDocument,
    pages: Collection<OcrPageJournal.Page>,
    batchFirstPage: Int
) {
    private val codeByUnicode = LinkedHashMap<Int, Int>()
    private val resourceName = COSName.getPDFName("MuOCR" + batchFirstPage)
    private val font: COSDictionary

    init {
        for (page in pages) {
            for (line in page.lines) {
                for (cp in line.text.codePoints().toArray()) {
                    if (!codeByUnicode.containsKey(cp)) {
                        val next = codeByUnicode.size + 1
                        if (next > 65534) throw IOException("本批次文字种类超过 65534")
                        codeByUnicode[cp] = next
                    }
                }
            }
        }
        font = createFont()
    }

    fun append(page: PDPage, ocr: OcrPageJournal.Page) {
        val crop = page.cropBox ?: page.mediaBox ?: return
        val rot = ((page.rotation % 360) + 360) % 360
        val width = if (rot == 90 || rot == 270) crop.height else crop.width
        val height = if (rot == 90 || rot == 270) crop.width else crop.height
        val matrix = when (rot) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f,
                crop.lowerLeftX + crop.width, crop.lowerLeftY)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f,
                crop.lowerLeftX + crop.width, crop.lowerLeftY + crop.height)
            270 -> floatArrayOf(0f, -1f, 1f, 0f,
                crop.lowerLeftX, crop.lowerLeftY + crop.height)
            else -> floatArrayOf(1f, 0f, 0f, 1f,
                crop.lowerLeftX, crop.lowerLeftY)
        }
        val sx = width / ocr.width.toFloat()
        val sy = height / ocr.height.toFloat()

        val resources = page.resources ?: PDResources().also { page.resources = it }
        val fontResources = resources.cosObject.getCOSDictionary(COSName.FONT)
            ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
        fontResources.setItem(resourceName, font)

        PDPageContentStream(
            document, page, PDPageContentStream.AppendMode.APPEND, true, true
        ).use { out ->
            out.saveGraphicsState()
            try {
                out.transform(Matrix(
                    matrix[0], matrix[1], matrix[2], matrix[3], matrix[4], matrix[5]
                ))

                for (line in ocr.lines) {
                    val box = line.rect
                    val codePointCount = line.text.codePointCount(0, line.text.length)
                    if (codePointCount == 0) continue
                    val boxWidth = max(0.5f, box.width() * sx)
                    val boxHeight = max(1f, box.height() * sy)
                    val x = box.left * sx
                    val y = height - box.bottom * sy + boxHeight * 0.12f
                    val fontSize = (boxHeight * 0.88f).coerceIn(1f, 96f)

                    // /DW is 1000 font units, i.e. one fontSize per CID.
                    val naturalWidth = fontSize * codePointCount
                    val horizontal = (boxWidth / naturalWidth * 100f).coerceIn(5f, 1000f)

                    out.beginText()
                    try {
                        // Use raw text operators here because PDFBox's normal
                        // PDFont.encode() insists on real glyphs, which are
                        // unnecessary for invisible OCR text.
                        @Suppress("DEPRECATION")
                        out.appendRawCommands("/" + resourceName.name + " " +
                            number(fontSize) + " Tf\n")
                        @Suppress("DEPRECATION")
                        out.appendRawCommands("3 Tr\n" +
                            number(horizontal) + " Tz\n" +
                            "1 0 0 1 " + number(x) + " " + number(y) + " Tm\n" +
                            "<" + toCidHex(line.text) + "> Tj\n")
                    } finally {
                        out.endText()
                    }
                }
            } finally {
                out.restoreGraphicsState()
            }
        }
        resources.cosObject.setNeedToBeUpdated(true)
        fontResources.setNeedToBeUpdated(true)
    }

    private fun toCidHex(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            val id = codeByUnicode[cp]
                ?: throw IOException("Unknown OCR character codepoint: " + cp)
            append(hex4(id))
        }
    }

    private fun createFont(): COSDictionary {
        val descriptor = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.getPDFName("FontDescriptor"))
            setName(COSName.FONT_NAME, "MuOCRInvisible")
            setInt(COSName.FLAGS, 4)
            setItem(COSName.FONT_BBOX, cosArray(0, -200, 1000, 900))
            setInt(COSName.ITALIC_ANGLE, 0)
            setInt(COSName.ASCENT, 800)
            setInt(COSName.DESCENT, -200)
            setInt(COSName.CAP_HEIGHT, 700)
            setInt(COSName.STEM_V, 80)
        }

        val info = COSDictionary().apply {
            setItem(COSName.REGISTRY, COSString("Adobe"))
            setItem(COSName.ORDERING, COSString("Identity"))
            setInt(COSName.SUPPLEMENT, 0)
        }

        val cidFont = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType2"))
            setName(COSName.BASE_FONT, "MuOCRInvisible")
            setItem(COSName.getPDFName("CIDSystemInfo"), info)
            setInt(COSName.DW, 1000)
            setItem(COSName.FONT_DESC, descriptor)
            setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY)
        }

        val map = document.document.createCOSStream()
        map.createOutputStream().use { it.write(createToUnicode().toByteArray(Charsets.US_ASCII)) }

        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Type0"))
            setName(COSName.BASE_FONT, "MuOCRInvisible")
            setItem(COSName.ENCODING, COSName.getPDFName("Identity-H"))
            setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(cidFont) })
            setItem(COSName.TO_UNICODE, map)
        }
    }

    private fun createToUnicode(): String = buildString {
        append("/CIDInit /ProcSet findresource begin\n")
        append("12 dict begin\nbegincmap\n")
        append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        append("/CMapName /MuOCRUnicode def\n/CMapType 2 def\n")
        append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")

        codeByUnicode.entries.toList().chunked(100).forEach { entries ->
            append(entries.size).append(" beginbfchar\n")
            for ((cp, code) in entries) {
                append("<").append(hex4(code)).append("> <")
                for (byte in String(Character.toChars(cp)).toByteArray(Charsets.UTF_16BE)) {
                    append(hex2(byte.toInt() and 0xFF))
                }
                append(">\n")
            }
            append("endbfchar\n")
        }
        append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
    }

    private fun cosArray(vararg values: Int) = COSArray().apply {
        for (n in values) add(COSInteger.get(n.toLong()))
    }

    private fun hex4(n: Int) = n.toString(16).uppercase(Locale.US).padStart(4, '0')
    private fun hex2(n: Int) = n.toString(16).uppercase(Locale.US).padStart(2, '0')
    private fun number(f: Float) = String.format(Locale.US, "%.4f", f)
}
