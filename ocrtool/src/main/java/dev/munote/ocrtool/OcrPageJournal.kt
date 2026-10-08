package dev.munote.ocrtool

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * One OCR result per page, persisted before moving on to the next page.
 * This keeps the whole document's OCR data off the Java heap, and enables
 * retrying a killed or failed job without repeating completed OCR pages.
 */
internal object OcrPageJournal {
    internal data class Line(val text: String, val rect: Rect)
    internal data class Page(
        val width: Int,
        val height: Int,
        val lines: List<Line>
    )

    private fun pageFile(directory: File, index: Int) =
        File(directory, "page_" + (index + 1).toString().padStart(6, '0') + ".json")

    fun read(directory: File, index: Int): Page? {
        val file = pageFile(directory, index)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val width = root.getInt("width")
            val height = root.getInt("height")
            require(width > 0 && height > 0)
            val rows = root.getJSONArray("lines")
            val lines = ArrayList<Line>(rows.length())
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                require(row.length() == 5)
                val text = row.getString(0)
                val rect = Rect(row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4))
                if (text.isNotBlank()) lines.add(Line(text, rect))
            }
            Page(width, height, lines)
        }.getOrNull()
    }

    fun write(directory: File, index: Int, page: Page) {
        check(directory.isDirectory || directory.mkdirs())
        val rows = JSONArray()
        for (line in page.lines) {
            rows.put(
                JSONArray()
                    .put(line.text)
                    .put(line.rect.left)
                    .put(line.rect.top)
                    .put(line.rect.right)
                    .put(line.rect.bottom)
            )
        }
        val root = JSONObject()
            .put("width", page.width)
            .put("height", page.height)
            .put("lines", rows)
        val dst = pageFile(directory, index)
        val tmp = File(directory, dst.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(dst)) {
                throw IllegalStateException("Cannot finish OCR checkpoint for page " + (index + 1))
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
