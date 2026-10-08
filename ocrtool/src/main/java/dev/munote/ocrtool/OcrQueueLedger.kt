package dev.munote.ocrtool

import com.google.gson.Gson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Persistent queue with *one* active task. It has no Android dependencies,
 * so ordering, interruption and recovery can be tested on the JVM.
 *
 * Every mutation atomically rewrites the small queue manifest. OCR page data
 * and the PDF export journal belong to each task's own work directory.
 */
internal class OcrQueueLedger(private val manifest: File) {
    internal object Status {
        const val WAITING = "waiting"
        const val RUNNING = "running"
        const val PAUSING = "pausing"
        const val PAUSED = "paused"
        const val CANCELLING = "cancelling"
        const val CANCELLED = "cancelled"
        const val FAILED = "failed"
        const val DONE = "done"
    }

    data class Entry(
        var id: String = "",
        var name: String = "",
        var inputUri: String = "",
        var treeUri: String = "",
        var outputUri: String = "",
        var status: String = Status.WAITING,
        var quality: Int = 2200,
        var progress: Int = 0,
        var page: Int = 0,
        var total: Int = 0,
        var stage: String = "等待处理",
        var error: String = "",
        var createdAt: Long = 0L
    )

    data class Snapshot(
        var pausedAll: Boolean = false,
        var entries: MutableList<Entry> = mutableListOf()
    )

    private val gson = Gson()
    private val backup = File(manifest.parentFile, manifest.name + ".bak")

    @Synchronized
    fun snapshot(): Snapshot = read().let { copyOf(it) }

    @Synchronized
    fun add(inputs: List<Pair<String, String>>, treeUri: String, quality: Int): List<Entry> {
        require(treeUri.isNotBlank())
        if (inputs.isEmpty()) return emptyList()
        return edit {
            val newlyAdded = inputs.map { (uri, filename) ->
                Entry(
                    id = UUID.randomUUID().toString(),
                    name = filename.ifBlank { "document.pdf" },
                    inputUri = uri,
                    treeUri = treeUri,
                    quality = quality.coerceIn(1200, 3200),
                    createdAt = System.currentTimeMillis()
                ).also { entry -> it.entries.add(entry) }
            }
            newlyAdded.map(Entry::copy)
        }
    }

    /**
     * WorkManager guarantees only one queue supervisor executes at once.
     * On a restarted supervisor, a previously in-progress entry can resume
     * from its journal instead of being stuck in RUNNING forever.
     */
    @Synchronized
    fun recoverAfterWorkerRestart() {
        edit { state ->
            state.entries.forEach { entry ->
                when (entry.status) {
                    Status.RUNNING -> {
                        entry.status = Status.WAITING
                        entry.stage = "恢复未完成的任务"
                    }
                    Status.PAUSING -> {
                        entry.status = Status.PAUSED
                        entry.stage = "已暂停，可继续"
                    }
                    Status.CANCELLING -> {
                        entry.status = Status.CANCELLED
                        entry.stage = "已取消"
                    }
                }
            }
        }
    }

    @Synchronized
    fun claimNext(): Entry? = edit { state ->
        if (state.pausedAll ||
            state.entries.any { it.status in setOf(Status.RUNNING, Status.PAUSING, Status.CANCELLING) }) {
            return@edit null
        }
        val entry = state.entries.firstOrNull { it.status == Status.WAITING }
            ?: return@edit null
        entry.status = Status.RUNNING
        entry.stage = "等待文件准备"
        entry.error = ""
        entry.copy()
    }

    @Synchronized
    fun setOutput(id: String, uri: String) = edit { state ->
        state.entries.find { it.id == id }?.let { it.outputUri = uri }
    }

    @Synchronized
    fun clearOutput(id: String) = edit { state ->
        state.entries.find { it.id == id }?.let { it.outputUri = "" }
    }

    @Synchronized
    fun progress(id: String, stage: String, page: Int, total: Int, percent: Int) =
        edit { state ->
            val entry = state.entries.find { it.id == id } ?: return@edit
            if (entry.status == Status.RUNNING) {
                entry.stage = stage
                entry.page = page
                entry.total = total
                entry.progress = percent.coerceIn(0, 100)
            }
        }

    @Synchronized
    fun stateOf(id: String): String? =
        read().entries.find { it.id == id }?.status

    @Synchronized
    fun finish(id: String, status: String, error: String = "") = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        entry.status = when (entry.status) {
            Status.PAUSING -> Status.PAUSED
            Status.CANCELLING -> Status.CANCELLED
            // Pause-all can be released while the active task is shutting
            // down. Queue it again instead of leaving it unexpectedly paused.
            Status.RUNNING -> if (status == Status.PAUSED && !state.pausedAll)
                Status.WAITING else status
            else -> status
        }
        if (entry.status == Status.DONE) {
            entry.progress = 100
            entry.stage = "完成"
        } else {
            entry.stage = when (entry.status) {
                Status.PAUSED -> "已暂停"
                Status.CANCELLED -> "已取消"
                Status.FAILED -> "失败，可重试"
                else -> entry.stage
            }
        }
        entry.error = error.take(800)
    }

    @Synchronized
    fun pause(id: String) = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        entry.status = when (entry.status) {
            Status.RUNNING -> Status.PAUSING
            Status.WAITING -> Status.PAUSED
            else -> entry.status
        }
    }

    @Synchronized
    fun resume(id: String) = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        if (entry.status == Status.PAUSED || entry.status == Status.FAILED) {
            entry.status = Status.WAITING
            entry.error = ""
            entry.stage = "等待续做"
        }
    }

    @Synchronized
    fun cancel(id: String) = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        entry.status = when (entry.status) {
            Status.RUNNING, Status.PAUSING -> Status.CANCELLING
            Status.WAITING, Status.PAUSED, Status.FAILED -> Status.CANCELLED
            else -> entry.status
        }
    }

    @Synchronized
    fun retry(id: String) = edit { state ->
        val entry = state.entries.find { it.id == id } ?: return@edit
        if (entry.status == Status.CANCELLED || entry.status == Status.FAILED) {
            entry.status = Status.WAITING
            entry.stage = "等待重新处理"
            entry.error = ""
            entry.outputUri = ""
        }
    }

    @Synchronized
    fun pauseAll() = edit { state ->
        state.pausedAll = true
        state.entries.forEach { entry ->
            if (entry.status == Status.RUNNING) entry.status = Status.PAUSING
        }
    }

    @Synchronized
    fun resumeAll() = edit { state ->
        state.pausedAll = false
        state.entries.forEach { entry ->
            when (entry.status) {
                Status.PAUSED -> {
                    entry.status = Status.WAITING
                    entry.stage = "等待续做"
                }
                Status.PAUSING -> {
                    // The worker might not have observed the pause yet.
                    // Undo the stop request; if it has already exited,
                    // finish(PAUSED) will requeue this task.
                    entry.status = Status.RUNNING
                }
            }
        }
    }

    @Synchronized
    fun remove(id: String): Boolean = edit { state ->
        val entry = state.entries.find { it.id == id }
        if (entry == null ||
            entry.status in setOf(Status.RUNNING, Status.PAUSING, Status.CANCELLING)) {
            false
        } else {
            state.entries.remove(entry)
        }
    }

    private fun <T> edit(block: (Snapshot) -> T): T {
        val state = read()
        val result = block(state)
        write(state)
        return result
    }

    private fun copyOf(state: Snapshot): Snapshot =
        Snapshot(state.pausedAll, state.entries.map { it.copy() }.toMutableList())

    private fun read(): Snapshot {
        val file = if (manifest.isFile) manifest else backup
        if (!file.isFile) return Snapshot()
        fun parse(f: File): Snapshot? = runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), Snapshot::class.java)
        }.getOrNull()?.takeIf { it.entries != null }
        val state = parse(file) ?: parse(backup)
            ?: throw IOException("MuOCR 任务队列记录损坏，请保留应用数据并备份 queue 文件")
        if (state.entries.any { it.id.isBlank() || it.inputUri.isBlank() }) {
            throw IOException("MuOCR 任务队列包含无效任务，拒绝覆盖")
        }
        return state
    }

    private fun write(state: Snapshot) {
        val parent = manifest.parentFile ?: throw IOException("No queue directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create queue dir")
        val tmp = File(parent, manifest.name + ".new")
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(gson.toJson(state).toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (manifest.isFile) manifest.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(manifest)) throw IOException("Cannot commit queue manifest")
        } finally {
            tmp.delete()
        }
    }
}
