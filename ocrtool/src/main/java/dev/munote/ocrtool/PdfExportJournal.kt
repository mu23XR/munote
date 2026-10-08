package dev.munote.ocrtool

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Crash-safe PDF export commit state. The OCR page cache is independent and
 * remains valid if we need to rebuild the working PDF.
 */
internal class PdfExportJournal(private val dir: File) {
    data class State(
        val originalLength: Long,
        val pageCount: Int,
        val nextPage: Int,
        val workingLength: Long
    )

    private val stateFile = File(dir, "export.state")
    private val pendingFile = File(dir, "export.pending")

    fun readState(): State? = parse(stateFile)?.takeIf {
        it.originalLength > 0 && it.pageCount > 0 &&
            it.nextPage in 0..it.pageCount &&
            it.workingLength >= it.originalLength
    }

    fun initialize(originalLength: Long, pageCount: Int): State {
        val state = State(originalLength, pageCount, 0, originalLength)
        writeAtomic(stateFile, encode(state))
        pendingFile.delete()
        return state
    }

    fun recover(working: File): State? {
        val state = readState() ?: return null
        if (pendingFile.isFile) {
            val pending = parse(pendingFile) ?: return null
            if (pending.originalLength != state.originalLength ||
                pending.pageCount != state.pageCount ||
                pending.nextPage !in state.nextPage..state.pageCount ||
                pending.workingLength < state.originalLength
            ) return null

            if (state.nextPage >= pending.nextPage &&
                working.length() == state.workingLength
            ) {
                // The commit completed, but the process died before removing
                // the pending marker.
                pendingFile.delete()
            } else {
                if (!working.isFile || working.length() < pending.workingLength) return null
                RandomAccessFile(working, "rw").use { file ->
                    file.setLength(pending.workingLength)
                    file.fd.sync()
                }
                pendingFile.delete()
            }
        }
        return state.takeIf { working.isFile && working.length() == it.workingLength }
    }

    /**
     * First persist the old length, then append, fsync, then commit nextPage.
     * Restart can truncate an interrupted append back to the old length.
     */
    fun append(
        working: File,
        patch: File,
        current: State,
        newNextPage: Int
    ): State {
        require(newNextPage in (current.nextPage + 1)..current.pageCount)
        check(working.length() == current.workingLength) {
            "PDF work copy changed unexpectedly"
        }
        if (!patch.isFile || patch.length() <= 0) throw IOException("Empty PDF patch")
        writeAtomic(
            pendingFile,
            encode(current.copy(nextPage = newNextPage))
        )

        val before = current.workingLength
        FileOutputStream(working, true).use { target ->
            patch.inputStream().buffered(128 * 1024).use { input ->
                input.copyTo(target, 128 * 1024)
            }
            target.fd.sync()
        }
        val length = working.length()
        if (length != before + patch.length()) {
            throw IOException("Incomplete PDF patch append; recover on next launch")
        }

        val updated = current.copy(nextPage = newNextPage, workingLength = length)
        writeAtomic(stateFile, encode(updated))
        pendingFile.delete()
        return updated
    }

    fun advanceWithoutChanges(current: State, nextPage: Int): State {
        val updated = current.copy(nextPage = nextPage)
        writeAtomic(stateFile, encode(updated))
        return updated
    }

    private fun encode(s: State): String =
        "muocr-v1\t${s.originalLength}\t${s.pageCount}\t${s.nextPage}\t${s.workingLength}\n"

    private fun parse(file: File): State? {
        if (!file.isFile) return null
        return runCatching {
            val parts = file.readText(Charsets.US_ASCII).trim().split('\t')
            if (parts.size != 5 || parts[0] != "muocr-v1") return@runCatching null
            State(parts[1].toLong(), parts[2].toInt(),
                parts[3].toInt(), parts[4].toLong())
        }.getOrNull()
    }

    private fun writeAtomic(destination: File, content: String) {
        val temp = File(dir, destination.name + ".new")
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(content.toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            if (!temp.renameTo(destination)) {
                throw IOException("Cannot commit PDF export checkpoint")
            }
        } finally {
            temp.delete()
        }
    }
}
