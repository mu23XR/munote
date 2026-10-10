package dev.munote.ocrtool

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OcrQueueLedgerTest {
    private fun withLedger(block: (OcrQueueLedger, File) -> Unit) {
        val dir = Files.createTempDirectory("muocr-queue-jvm").toFile()
        try {
            block(OcrQueueLedger(File(dir, "queue.json")), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun OcrQueueLedger.three(): List<OcrQueueLedger.Entry> =
        add(
            listOf(
                "content://input/A" to "教材A.pdf",
                "content://input/B" to "教材B.pdf",
                "content://input/C" to "教材C.pdf"
            ),
            "content://tree/output", 2200
        )

    @Test
    fun threeEntriesRunStrictlyOneAtATimeAndInOrder() = withLedger { queue, _ ->
        val added = queue.three()
        assertEquals(3, added.size)
        assertEquals(3, added.map { it.id }.toSet().size)
        assertEquals(added[0].id, queue.claimNext()!!.id)
        assertNull("Another entry started before active completed", queue.claimNext())
        queue.progress(added[0].id, "OCR", 40, 100, 35)
        assertEquals(35, queue.snapshot().entries.first().progress)
        queue.finish(added[0].id, OcrQueueLedger.Status.DONE)
        assertEquals(added[1].id, queue.claimNext()!!.id)
        queue.finish(added[1].id, OcrQueueLedger.Status.FAILED, "Damaged source")
        assertEquals(added[2].id, queue.claimNext()!!.id)
        queue.finish(added[2].id, OcrQueueLedger.Status.DONE)
        assertNull(queue.claimNext())
        assertEquals(
            listOf("done", "failed", "done"),
            queue.snapshot().entries.map { it.status }
        )
    }

    @Test
    fun pauseActiveTaskDoesNotPauseNextTask() = withLedger { queue, _ ->
        val entries = queue.three()
        assertEquals(entries[0].id, queue.claimNext()!!.id)
        queue.progress(entries[0].id, "已识别", 303, 583, 55)
        queue.pause(entries[0].id)
        assertEquals("pausing", queue.stateOf(entries[0].id))
        assertNull(queue.claimNext())
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertEquals("paused", queue.stateOf(entries[0].id))
        assertEquals(303, queue.snapshot().entries.first().page)
        assertEquals(entries[1].id, queue.claimNext()!!.id)
        queue.finish(entries[1].id, OcrQueueLedger.Status.DONE)
        queue.resume(entries[0].id)
        queue.finish(entries[2].id, OcrQueueLedger.Status.WAITING)
        assertEquals(entries[0].id, queue.claimNext()!!.id)
    }

    @Test
    fun pauseAllThenImmediatelyResumeDoesNotStrandRunningTask() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.claimNext()
        queue.pauseAll()
        assertEquals("pausing", queue.stateOf(entries[0].id))
        queue.resumeAll()
        assertEquals("running", queue.stateOf(entries[0].id))
        // If the worker already noticed pause before resume-all was clicked,
        // it should be requeued to continue, not left paused forever.
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertEquals("waiting", queue.stateOf(entries[0].id))
        assertEquals(entries[0].id, queue.claimNext()!!.id)
    }

    @Test
    fun resumeAllMustNotResumeIndividuallyPausedPdf() = withLedger { queue, dir ->
        val entries = queue.three()
        val active = queue.claimNext()!!
        assertEquals(entries[0].id, active.id)

        // Individually pause B, then suspend the entire queue while A runs.
        queue.pause(entries[1].id)
        assertEquals(OcrQueueLedger.Status.PAUSED, queue.stateOf(entries[1].id))
        queue.pauseAll()
        assertEquals(OcrQueueLedger.Status.PAUSING, queue.stateOf(entries[0].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)

        // Simulate app process restart before global resume.
        val reopened = OcrQueueLedger(File(dir, "queue.json"))
        reopened.resumeAll()
        assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[0].id))
        assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[1].id))
        assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[2].id))

        assertEquals(entries[0].id, reopened.claimNext()!!.id)
        reopened.finish(entries[0].id, OcrQueueLedger.Status.DONE)
        assertEquals(entries[2].id, reopened.claimNext()!!.id)
        reopened.finish(entries[2].id, OcrQueueLedger.Status.DONE)
        assertNull(reopened.claimNext())

        // The explicit resume action, and only that action, can resume B.
        reopened.resume(entries[1].id)
        assertEquals(entries[1].id, reopened.claimNext()!!.id)
    }

    @Test
    fun resumeAllDuringActivePauseHonorsExplicitPerFilePause() =
        withLedger { queue, _ ->
            val entries = queue.three()
            queue.claimNext()
            queue.pauseAll()
            // A was pausing from a global request, but user explicitly
            // pauses that PDF before pressing Resume All.
            queue.pause(entries[0].id)
            queue.resumeAll()
            assertEquals(OcrQueueLedger.Status.PAUSING, queue.stateOf(entries[0].id))
            queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
            assertEquals(OcrQueueLedger.Status.PAUSED, queue.stateOf(entries[0].id))
            assertEquals(entries[1].id, queue.claimNext()!!.id)
        }

    @Test
    fun globalPausePendingAcrossWorkerRestartDoesNotUnpauseManualPause() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.pause(entries[1].id)
            queue.claimNext()
            queue.pauseAll()
            val reopened = OcrQueueLedger(File(dir, "queue.json"))
            reopened.recoverAfterWorkerRestart()
            assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[0].id))
            reopened.resumeAll()
            assertEquals(OcrQueueLedger.Status.WAITING, reopened.stateOf(entries[0].id))
            assertEquals(OcrQueueLedger.Status.PAUSED, reopened.stateOf(entries[1].id))
        }

    @Test
    fun cancellingOneTaskDoesNotCancelOtherTasks() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.claimNext()
        queue.cancel(entries[0].id)
        queue.cancel(entries[1].id)
        assertEquals("cancelling", queue.stateOf(entries[0].id))
        assertEquals("cancelled", queue.stateOf(entries[1].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.CANCELLED)
        assertEquals(entries[2].id, queue.claimNext()!!.id)
    }

    /**
     * Cancelling a task being claimed by the supervisor must tell the UI
     * whether it can safely reclaim files immediately. The old UI first
     * read WAITING, then called cancel(): if the worker claimed the task in
     * between, the UI would delete its live 1GB working directory.
     */
    @Test
    fun cancellationReturnsActualCommittedStateForSafeIndependentCleanup() =
        withLedger { queue, _ ->
            val entries = queue.three()
            assertEquals(
                OcrQueueLedger.Status.CANCELLED,
                queue.cancel(entries[2].id)
            )
            val claimed = queue.claimNext()!!
            assertEquals(entries[0].id, claimed.id)
            assertEquals(
                OcrQueueLedger.Status.CANCELLING,
                queue.cancel(entries[0].id)
            )
            assertNull("Running cancellation must finish before next claim", queue.claimNext())
            queue.finish(entries[0].id, OcrQueueLedger.Status.CANCELLED)
            assertEquals(entries[1].id, queue.claimNext()!!.id)
            // Cancelling a finished task must never delete its output.
            queue.finish(entries[1].id, OcrQueueLedger.Status.DONE)
            assertEquals(OcrQueueLedger.Status.DONE, queue.cancel(entries[1].id))
            assertNull(queue.cancel("missing-id"))
        }

    @Test
    fun globalPauseAndResumeAreDurable() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        queue.pauseAll()
        assertTrue(queue.snapshot().pausedAll)
        assertEquals("pausing", queue.stateOf(entries[0].id))
        queue.finish(entries[0].id, OcrQueueLedger.Status.PAUSED)
        assertNull(queue.claimNext())

        val reopened = OcrQueueLedger(File(dir, "queue.json"))
        assertTrue(reopened.snapshot().pausedAll)
        reopened.resumeAll()
        assertEquals(entries[0].id, reopened.claimNext()!!.id)
    }

    @Test
    fun workerRestartResumesIncompleteEntriesButNotCancelled() =
        withLedger { queue, dir ->
            val entries = queue.three()
            queue.claimNext()
            queue.progress(entries[0].id, "写入文字层", 16, 583, 92)
            queue.cancel(entries[1].id)
            val afterRestart = OcrQueueLedger(File(dir, "queue.json"))
            afterRestart.recoverAfterWorkerRestart()
            assertEquals("waiting", afterRestart.stateOf(entries[0].id))
            assertEquals("cancelled", afterRestart.stateOf(entries[1].id))
            val resumed = afterRestart.claimNext()!!
            assertEquals(entries[0].id, resumed.id)
            assertEquals(16, resumed.page)
            assertEquals(92, resumed.progress)
        }

    @Test
    fun duplicatePdfDocumentsRemainSeparateQueueEntries() = withLedger { queue, _ ->
        val tasks = queue.add(
            listOf("content://same" to "same.pdf", "content://same" to "same.pdf"),
            "content://tree/out", 2800
        )
        assertNotEquals(tasks[0].id, tasks[1].id)
        queue.claimNext()
        queue.setOutput(tasks[0].id, "content://output/first")
        queue.finish(tasks[0].id, OcrQueueLedger.Status.DONE)
        queue.claimNext()
        queue.setOutput(tasks[1].id, "content://output/second")
        assertNotEquals(
            queue.snapshot().entries[0].outputUri,
            queue.snapshot().entries[1].outputUri
        )
        assertEquals(2800, tasks[1].quality)
    }

    @Test
    fun failedEntryCanRetryWithoutResettingItsProgress() = withLedger { queue, _ ->
        val entry = queue.add(listOf("content://input/X" to "X.pdf"), "tree", 2200)[0]
        queue.claimNext()
        queue.progress(entry.id, "OCR", 150, 200, 70)
        queue.finish(entry.id, OcrQueueLedger.Status.FAILED, "Too little disk")
        queue.resume(entry.id)
        assertEquals(entry.id, queue.claimNext()!!.id)
        assertEquals(150, queue.snapshot().entries.first().page)
    }

    @Test
    fun queueRecoversFromCorruptedPrimaryManifest() = withLedger { queue, dir ->
        val entries = queue.three()
        queue.claimNext()
        assertTrue(File(dir, "queue.json.bak").exists())
        File(dir, "queue.json").writeText("{CORRUPT")
        val restored = OcrQueueLedger(File(dir, "queue.json")).snapshot()
        assertEquals(entries.size, restored.entries.size)
    }

    @Test
    fun removingPausedItemDoesNotTouchOtherRecords() = withLedger { queue, _ ->
        val entries = queue.three()
        queue.pause(entries[1].id)
        assertTrue(queue.remove(entries[1].id))
        assertEquals(listOf(entries[0].id, entries[2].id),
            queue.snapshot().entries.map { it.id })
        assertFalse(queue.remove("missing"))
    }
}
