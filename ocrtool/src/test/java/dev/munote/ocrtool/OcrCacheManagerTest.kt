package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OcrCacheManagerTest {
    private fun withManager(block: (OcrCacheManager, OcrQueueLedger, File) -> Unit) {
        val root = Files.createTempDirectory("muocr-cache").toFile()
        try {
            val ledger = OcrQueueLedger(File(root, "queue-ledger.json"))
            val manager = OcrCacheManager(listOf(File(root, "work")), ledger)
            block(manager, ledger, File(root, "work"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun countsTaskAndLegacyCachesWithoutTouchingSourcePdf() = withManager {
        manager, ledger, root ->
        val task = ledger.add(listOf("content://docs/book" to "book.pdf"),
            "content://docs/exports", 2200).first()
        val directory = File(root, "queue-" + task.id)
        directory.mkdirs()
        File(directory, "original.pdf").writeBytes(ByteArray(8192))
        val legacy = File(root, "0123456789abcdef0123456789abcdef")
        legacy.mkdirs()
        File(legacy, "page.json").writeBytes(ByteArray(512))
        val snapshot = manager.inspect()
        assertEquals(8192L, snapshot.jobs.single().bytes)
        assertEquals(512L, snapshot.legacy.single().bytes)
        assertEquals(8704L, snapshot.totalBytes)
        assertTrue(File(directory, "original.pdf").isFile)
    }

    @Test fun queuedAndPausedWorkKeepCheckpointsUntilExplicitDiscard() = withManager {
        manager, ledger, root ->
        val task = ledger.add(listOf("content://docs/book" to "book.pdf"),
            "content://docs/exports", 2200).first()
        val directory = File(root, "queue-" + task.id)
        directory.mkdirs()
        File(directory, "page_000001.json").writeText("cached")
        assertFalse(manager.cleanTask(task.id, false).success)
        assertTrue(directory.exists())
        assertTrue(manager.cleanTask(task.id, true).success)
        assertFalse(directory.exists())
        assertEquals(OcrQueueLedger.Status.CANCELLED, ledger.stateOf(task.id))
    }

    @Test fun runningTaskCannotBeDeletedBySingleOrBulkAction() = withManager {
        manager, ledger, root ->
        val task = ledger.add(listOf("content://docs/book" to "book.pdf"),
            "content://docs/exports", 2200).first()
        ledger.claimNext()
        val directory = File(root, "queue-" + task.id)
        directory.mkdirs()
        File(directory, "original.pdf").writeText("still running")
        assertFalse(manager.cleanTask(task.id, true).success)
        assertFalse(manager.cleanAll().success)
        assertTrue(directory.exists())
        assertEquals(OcrQueueLedger.Status.RUNNING, ledger.stateOf(task.id))
    }

    @Test fun cleanAllCancelsUnfinishedAndRemovesLegacyAndOrphans() = withManager {
        manager, ledger, root ->
        val first = ledger.add(listOf("content://docs/a" to "a.pdf"),
            "content://docs/exports", 2200).first()
        val second = ledger.add(listOf("content://docs/b" to "b.pdf"),
            "content://docs/exports", 2200).first()
        val legacy = File(root, "0123456789abcdef0123456789abcdef")
        val orphan = File(root, "queue-1b75696c-d5da-48ba-814c-fc539aa89a51")
        listOf(first.id, second.id).forEach {
            val dir = File(root, "queue-" + it)
            dir.mkdirs()
            File(dir, "ocr").writeText("checkpoint")
        }
        legacy.mkdirs()
        File(legacy, "original.pdf").writeText("legacy")
        orphan.mkdirs()
        File(orphan, "original.pdf").writeText("orphan")
        val result = manager.cleanAll()
        assertTrue(result.details, result.success)
        assertFalse(legacy.exists())
        assertFalse(orphan.exists())
        assertEquals(0L, manager.inspect().workBytes)
        assertEquals(OcrQueueLedger.Status.CANCELLED, ledger.stateOf(first.id))
        assertEquals(OcrQueueLedger.Status.CANCELLED, ledger.stateOf(second.id))
    }

    @Test fun completedOutputRemainsWhileWorkingCopyIsDeleted() = withManager {
        manager, ledger, root ->
        val task = ledger.add(listOf("content://docs/a" to "a.pdf"),
            "content://docs/exports", 2200).first()
        val original = File(root, "queue-" + task.id)
        original.mkdirs()
        File(original, "writing.pdf").writeText("temporary")
        ledger.claimNext()
        ledger.setOutput(task.id, "content://docs/exports/output.pdf")
        ledger.finish(task.id, OcrQueueLedger.Status.DONE)
        val cleanup = manager.cleanCompleted()
        assertTrue(cleanup.details, cleanup.success)
        assertFalse(original.exists())
        assertEquals(OcrQueueLedger.Status.DONE, ledger.stateOf(task.id))
        assertEquals("content://docs/exports/output.pdf",
            ledger.snapshot().entries.single().outputUri)
    }
}
