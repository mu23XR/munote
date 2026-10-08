package dev.munote.ocrtool

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Only one WorkManager chain can own the OCR queue.
 *
 * APPEND_OR_REPLACE schedules a later supervisor after an active one rather
 * than racing it. A supervisor drains all waiting entries sequentially.
 * It is never cancelled to pause a single task: the worker cooperatively
 * observes per-task PAUSING/CANCELLING flags instead.
 */
internal object OcrQueueScheduler {
    const val UNIQUE_NAME = "muocr_serial_batch"
    const val TAG = "muocr_serial_batch"

    @Volatile private var ledger: OcrQueueLedger? = null

    fun store(context: Context): OcrQueueLedger =
        ledger ?: synchronized(this) {
            ledger ?: OcrQueueLedger(
                File(context.applicationContext.filesDir, "ocr-queue-v1.json")
            ).also { ledger = it }
        }

    fun wake(context: Context) {
        val request = OneTimeWorkRequestBuilder<LargePdfOcrWorker>()
            .setInputData(Data.Builder()
                .putBoolean(LargePdfOcrWorker.KEY_QUEUE_MODE, true)
                .build())
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun taskDirectory(context: Context, taskId: String): File {
        // Never accept arbitrary strings as path components.
        UUID.fromString(taskId)
        val root = context.getExternalFilesDir("muocr-work")
            ?: File(context.filesDir, "muocr-work")
        return File(root, "queue-$taskId")
    }

    fun createOutputFile(context: Context, task: OcrQueueLedger.Entry): Uri {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(task.treeUri))
            ?: throw IOException("无法打开输出文件夹，请重新授权")
        if (!tree.isDirectory || !tree.canWrite()) {
            throw IOException("输出文件夹无写入权限，请重新选择")
        }
        val name = OcrOutputNaming.choose(task.name,
            tree.listFiles().mapNotNull { it.name })

        val created = tree.createFile("application/pdf", name)
            ?: throw IOException("无法在输出文件夹创建 PDF")
        return created.uri
    }

    fun deleteIncompleteOutput(context: Context, uri: String) {
        if (uri.isBlank()) return
        runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(uri))?.let {
                if (it.exists()) it.delete()
            }
        }
    }

    fun cleanTaskCache(context: Context, taskId: String) {
        runCatching { taskDirectory(context, taskId).deleteRecursively() }
    }
}
