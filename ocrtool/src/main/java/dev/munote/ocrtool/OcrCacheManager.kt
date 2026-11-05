package dev.munote.ocrtool

import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * All deletions are limited to MuOCR's own private work directories.
 * A queued/running job is NEVER deleted based on a stale UI snapshot:
 * the ledger atomically authorizes each destructive operation first.
 */
internal class OcrCacheManager(
    private val roots: List<File>,
    private val ledger: OcrQueueLedger,
    private val deletePartialOutput: (String) -> Unit = {},
    private val fontDirectory: File? = null
) {
    data class Job(val id: String, val name: String, val status: String, val bytes: Long)
    data class Folder(val root: File, val name: String, val bytes: Long)
    data class Snapshot(
        val jobs: List<Job>,
        val legacy: List<Folder>,
        val orphans: List<Folder>,
        val unclassifiedBytes: Long,
        val fontBytes: Long
    ) {
        val workBytes: Long get() =
            jobs.sumOf { it.bytes } + legacy.sumOf { it.bytes } +
                orphans.sumOf { it.bytes } + unclassifiedBytes
        val totalBytes: Long get() = workBytes + fontBytes
    }
    data class Cleanup(val success: Boolean, val details: String, val bytesRemoved: Long = 0L)

    private val oldJobName = Regex("[a-f0-9]{32}")
    private val queueName = Regex("queue-[0-9a-fA-F-]{36}")

    fun inspect(): Snapshot {
        val entries = ledger.snapshot().entries
        val known = entries.map { "queue-" + it.id }.toSet()
        val jobs = entries.map { entry ->
            Job(entry.id, entry.name, entry.status,
                roots.sumOf { root -> sizeOf(File(root, "queue-" + entry.id)) })
        }
        val legacy = ArrayList<Folder>()
        val orphans = ArrayList<Folder>()
        var other = 0L
        for (root in roots) {
            for (child in root.listFiles().orEmpty()) {
                when {
                    child.name in known && child.isDirectory -> Unit
                    child.isDirectory && oldJobName.matches(child.name) ->
                        legacy.add(Folder(root, child.name, sizeOf(child)))
                    child.isDirectory && queueName.matches(child.name) &&
                        child.name !in known && validQueueName(child.name) ->
                        orphans.add(Folder(root, child.name, sizeOf(child)))
                    else -> other += sizeOf(child)
                }
            }
        }
        return Snapshot(jobs, legacy, orphans, other,
            fontDirectory?.let(::sizeOf) ?: 0L)
    }

    /** A completed or cancelled task is safe; unfinished work needs explicit consent. */
    fun cleanTask(id: String, discardProgress: Boolean): Cleanup {
        if (runCatching { UUID.fromString(id) }.isFailure) {
            return Cleanup(false, "无效任务编号")
        }
        val permit = ledger.authorizeCacheCleanup(id, discardProgress)
            ?: return Cleanup(false, "任务正在处理，或未允许放弃识别断点")
        return deleteAuthorizedTask(id, permit)
    }

    fun cleanCompleted(): Cleanup {
        var freed = 0L
        var failed = 0
        var handled = 0
        val eligible = ledger.snapshot().entries.filter {
            it.status == OcrQueueLedger.Status.DONE ||
                it.status == OcrQueueLedger.Status.CANCELLED
        }
        for (entry in eligible) {
            val result = cleanTask(entry.id, false)
            if (result.success) { handled++; freed += result.bytesRemoved }
            else failed++
        }
        return Cleanup(failed == 0,
            "已检查 " + handled + " 个完成/取消任务" +
                if (failed > 0) "，清理失败 " + failed + " 个" else "",
            freed)
    }

    /**
     * One atomic ledger transaction first cancels all non-running tasks.
     * If any worker is active, refuse the entire operation. The old 0.1.x
     * folders are removed only after the user's second confirmation in UI.
     */
    fun cleanAll(): Cleanup {
        val permits = ledger.authorizeBulkCacheCleanup()
            ?: return Cleanup(false, "有任务正在处理，请先暂停并等待停止")
        var freed = 0L
        var failures = 0
        for ((id, permit) in permits) {
            val result = deleteAuthorizedTask(id, permit)
            if (result.success) freed += result.bytesRemoved else failures++
        }
        val snapshot = inspect()
        for (folder in snapshot.legacy + snapshot.orphans) {
            val result = removeFolder(folder)
            if (result.success) freed += result.bytesRemoved else failures++
        }
        val fonts = fontDirectory
        if (fonts != null && fonts.exists()) {
            val before = sizeOf(fonts)
            if (!fonts.deleteRecursively()) failures++
            freed += (before - sizeOf(fonts)).coerceAtLeast(0L)
        }
        return Cleanup(failures == 0,
            if (failures == 0) "可识别的任务缓存已清理"
            else "有 " + failures + " 个缓存目录未能完整删除，请重试",
            freed)
    }

    /** The user must explicitly choose an old-format cache folder to delete. */
    fun cleanLegacy(folder: Folder): Cleanup {
        if (!oldJobName.matches(folder.name) &&
            !(queueName.matches(folder.name) && validQueueName(folder.name))) {
            return Cleanup(false, "无效缓存目录")
        }
        val busy = ledger.snapshot().entries.any {
            it.status in setOf(OcrQueueLedger.Status.WAITING,
                OcrQueueLedger.Status.RUNNING,
                OcrQueueLedger.Status.PAUSING,
                OcrQueueLedger.Status.CANCELLING)
        }
        if (busy) return Cleanup(false, "仍有运行中或排队任务，不能删除旧缓存")
        val current = inspect()
        if (current.legacy.none { it.root == folder.root && it.name == folder.name } &&
            current.orphans.none { it.root == folder.root && it.name == folder.name }) {
            return Cleanup(false, "该缓存已不存在或仍属于队列")
        }
        return removeFolder(folder)
    }

    private fun deleteAuthorizedTask(
        id: String, permit: OcrQueueLedger.CacheCleanupAuthorization
    ): Cleanup {
        if (permit.previousStatus != OcrQueueLedger.Status.DONE &&
            permit.outputUri.isNotBlank()) {
            runCatching { deletePartialOutput(permit.outputUri) }
        }
        var freed = 0L
        var failed = false
        for (root in roots) {
            val directory = File(root, "queue-" + id)
            val before = sizeOf(directory)
            if (directory.exists() && !directory.deleteRecursively()) failed = true
            freed += (before - sizeOf(directory)).coerceAtLeast(0L)
        }
        return Cleanup(!failed,
            if (failed) "部分文件删除失败" else "已清理该任务缓存", freed)
    }

    private fun removeFolder(folder: Folder): Cleanup {
        if (folder.root !in roots) return Cleanup(false, "缓存目录不属于 MuOCR")
        // Exact child only: no user-supplied paths, nested paths or traversal.
        val directory = File(folder.root, folder.name)
        val before = sizeOf(directory)
        val success = !directory.exists() || directory.deleteRecursively()
        val after = sizeOf(directory)
        return Cleanup(success, if (success) "已清理" else "部分缓存删除失败",
            (before - after).coerceAtLeast(0L))
    }

    private fun validQueueName(name: String): Boolean =
        runCatching { UUID.fromString(name.removePrefix("queue-")) }.isSuccess

    private fun sizeOf(start: File): Long {
        if (!start.exists()) return 0L
        val stack = ArrayDeque<File>()
        stack.add(start)
        var bytes = 0L
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) continue
            if (file.isFile) bytes += file.length().coerceAtLeast(0L)
            else if (file.isDirectory) file.listFiles()?.forEach(stack::add)
        }
        return bytes
    }
}
