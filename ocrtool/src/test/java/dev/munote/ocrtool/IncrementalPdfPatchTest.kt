package dev.munote.ocrtool

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class IncrementalPdfPatchTest {
    @Test
    fun savesOnlyIncrementalRevisionEvenWithSplitWrites() {
        val dir = Files.createTempDirectory("muocr-patch").toFile()
        try {
            val patch = File(dir, "revision")
            val source = "%PDF-1.7\nORIGINAL-BYTES\n".toByteArray()
            val delta = "\n9 0 obj\n<< /Type /Page >>\nendobj\nstartxref\n901\n%%EOF".toByteArray()
            val output = IncrementalPdfPatchOutputStream(patch, source.size.toLong())
            output.write(source, 0, 3)
            output.write(source, 3, 7)
            output.write(source, 10, source.size - 10)
            output.write(delta)
            output.close()
            output.verify()
            assertArrayEquals(delta, patch.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun refusesIncompleteInputPrefix() {
        val dir = Files.createTempDirectory("muocr-short").toFile()
        try {
            val out = IncrementalPdfPatchOutputStream(File(dir, "patch"), 1000)
            out.write("too short".toByteArray())
            out.close()
            out.verify()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun canResumeCommittedBatches() {
        val dir = Files.createTempDirectory("muocr-journal").toFile()
        try {
            val work = File(dir, "working.pdf")
            work.writeText("ORIGINAL")
            val patch = File(dir, "revision.patch")
            patch.writeText("FIRST-BATCH")
            val journal = PdfExportJournal(dir)
            val before = journal.initialize(work.length(), 583)
            val after = journal.append(work, patch, before, 16)
            assertEquals(16, after.nextPage)
            val resumed = journal.recover(work)
            assertNotNull(resumed)
            assertEquals(16, resumed!!.nextPage)
            assertEquals("ORIGINALFIRST-BATCH", work.readText())
            val advanced = journal.advanceWithoutChanges(resumed, 32)
            assertEquals(32, journal.recover(work)!!.nextPage)
            assertEquals(advanced.workingLength, work.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rollsBackInterruptedAppend() {
        val dir = Files.createTempDirectory("muocr-rollback").toFile()
        try {
            val work = File(dir, "working.pdf")
            work.writeText("ORIGINAL")
            val journal = PdfExportJournal(dir)
            val state = journal.initialize(work.length(), 583)

            // Simulate death after append but before checkpoint update.
            File(dir, "export.pending").writeText(
                "muocr-v1\t8\t583\t16\t8\n"
            )
            work.appendText("PARTIAL-APPEND")

            val restored = journal.recover(work)
            assertNotNull(restored)
            assertEquals(state.workingLength, work.length())
            assertEquals(0, restored!!.nextPage)
            assertEquals("ORIGINAL", work.readText())
            assertFalse(File(dir, "export.pending").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptCheckpointForcesCleanStart() {
        val dir = Files.createTempDirectory("muocr-bad-state").toFile()
        try {
            val work = File(dir, "working.pdf")
            work.writeText("ORIGINAL")
            val journal = PdfExportJournal(dir)
            journal.initialize(work.length(), 583)
            File(dir, "export.state").writeText("corrupt\n")
            assertNull(journal.recover(work))
        } finally {
            dir.deleteRecursively()
        }
    }
}
