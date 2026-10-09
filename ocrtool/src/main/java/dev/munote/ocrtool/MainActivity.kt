package dev.munote.ocrtool

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * GUI for the durable serial OCR queue.
 *
 * No APK-specific storage permissions: SAF grants read access to each selected
 * PDF and a write grant to the chosen output directory. The output files are
 * created only when their individual queued task starts.
 */
class MainActivity : AppCompatActivity() {
    private val store by lazy { OcrQueueScheduler.store(applicationContext) }
    private var outputTree: String = ""
    private var selectedQuality = 2200
    private var openFilesAfterFolder = false
    private val cleanupInProgress = Collections.synchronizedSet(mutableSetOf<String>())

    private lateinit var folderText: TextView
    private lateinit var summaryText: TextView
    private lateinit var taskContainer: LinearLayout
    private lateinit var allButton: Button
    private lateinit var scroll: ScrollView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputTree = uri.toString()
                getSharedPreferences("muocr-queue-ui", MODE_PRIVATE).edit()
                    .putString("output-tree", outputTree).apply()
                folderText.text = "输出文件夹：已选择（所有文件会分别保存）"
                if (openFilesAfterFolder) filePicker.launch(arrayOf("application/pdf"))
            } catch (error: Exception) {
                toast("无法取得文件夹写入权限：" + (error.message ?: "请重选"))
            } finally {
                openFilesAfterFolder = false
            }
        } else {
            openFilesAfterFolder = false
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val tree = outputTree
        if (tree.isBlank()) {
            toast("请先选择输出文件夹")
            return@registerForActivityResult
        }
        try {
            val inputs = uris.distinct().map { uri ->
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                uri.toString() to displayName(uri)
            }
            store.add(inputs, tree, selectedQuality)
            refreshQueue()
            if (!store.snapshot().pausedAll) OcrQueueScheduler.wake(this)
            toast("已加入 ${inputs.size} 个文件，按顺序逐个处理")
        } catch (error: Exception) {
            toast("添加失败：" + (error.message ?: error.javaClass.simpleName))
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MuOCR"
        outputTree = getSharedPreferences("muocr-queue-ui", MODE_PRIVATE)
            .getString("output-tree", "") ?: ""
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // A unique WorkManager chain publishes progress after each page.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(OcrQueueScheduler.UNIQUE_NAME)
            .observe(this) { refreshQueue() }

        refreshQueue()
    }

    override fun onResume() {
        super.onResume()
        if (::taskContainer.isInitialized) refreshQueue()
        // Continue best-effort cleanup of cancelled task scratch files even
        // if the app process died part-way through a previous cancellation.
        Thread {
            runCatching {
                store.snapshot().entries.filter {
                    it.status == OcrQueueLedger.Status.CANCELLED
                }.forEach { cancelled ->
                    if (cleanupInProgress.add(cancelled.id)) {
                        try {
                            if (store.stateOf(cancelled.id) ==
                                OcrQueueLedger.Status.CANCELLED) {
                                OcrQueueScheduler.cleanTaskCache(applicationContext, cancelled.id)
                            }
                        } finally {
                            cleanupInProgress.remove(cancelled.id)
                        }
                    }
                }
            }
        }.start()
        // Recovery: Android may have stopped or force-killed the worker. If
        // WorkManager no longer owns a running supervisor, append another.
        Thread {
            try {
                val pending = store.snapshot()
                if (pending.pausedAll ||
                    pending.entries.none {
                        it.status in setOf(
                            OcrQueueLedger.Status.WAITING,
                            OcrQueueLedger.Status.RUNNING,
                            OcrQueueLedger.Status.PAUSING,
                            OcrQueueLedger.Status.CANCELLING
                        )
                    }) return@Thread

                val active = WorkManager.getInstance(applicationContext)
                    .getWorkInfosForUniqueWork(OcrQueueScheduler.UNIQUE_NAME)
                    .get(10, TimeUnit.SECONDS)
                    .any { !it.state.isFinished }
                if (!active) OcrQueueScheduler.wake(applicationContext)
            } catch (_: Exception) {
                // The next foreground visit can reattempt recovery.
            }
        }.start()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(24))
        }

        root.addView(text("MuOCR · 1.1.1", 27f))
        root.addView(text(
            "批量 OCR：多个 PDF 排队处理，同一时间只运行一个。每个文件独立缓存、导出与控制；可以暂停、继续、取消和重试。",
            14f, 10
        ))

        folderText = text("输出文件夹：尚未选择", 14f, 16)
        root.addView(folderText)
        root.addView(Button(this).apply {
            text = "选择输出文件夹"
            setOnClickListener { folderPicker.launch(null) }
        })

        root.addView(text("OCR 分辨率", 15f, 14))
        val qualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val balanced = Button(this).apply {
            text = "平衡 2200px"
            setOnClickListener { selectedQuality = 2200; updateQualityButtons(qualityRow) }
        }
        val precise = Button(this).apply {
            text = "高精度 2800px"
            setOnClickListener { selectedQuality = 2800; updateQualityButtons(qualityRow) }
        }
        qualityRow.addView(balanced, weightedParams())
        qualityRow.addView(precise, weightedParams())
        root.addView(qualityRow)
        updateQualityButtons(qualityRow)

        root.addView(Button(this).apply {
            text = "批量添加 PDF（支持多选）"
            setOnClickListener {
                if (outputTree.isBlank()) {
                    openFilesAfterFolder = true
                    folderPicker.launch(null)
                } else {
                    filePicker.launch(arrayOf("application/pdf"))
                }
            }
        })

        allButton = Button(this).apply {
            text = "暂停全部"
            setOnClickListener {
                try {
                    if (store.snapshot().pausedAll) {
                        store.resumeAll()
                        OcrQueueScheduler.wake(this@MainActivity)
                    } else {
                        store.pauseAll()
                    }
                    refreshQueue()
                } catch (error: Exception) {
                    toast("队列操作失败：" + (error.message ?: "未知错误"))
                }
            }
        }
        root.addView(allButton)

        summaryText = text("尚无任务", 14f, 12)
        root.addView(summaryText)
        root.addView(text(
            "单独暂停的文件不会因为点击「继续全部任务」而自动恢复。暂停保留 OCR 和 PDF 导出断点；取消只清理指定任务。操作在页或批次检查点生效。",
            12f, 6
        ))

        taskContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(taskContainer)
        root.addView(text(
            "建议：处理 1GB 扫描 PDF 时保留至少 4GB 可用存储空间并接电。不要卸载应用或清除数据，以免丢失未完成任务的 OCR 缓存。",
            12f, 14
        ))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        return scroll
    }

    private fun updateQualityButtons(row: LinearLayout) {
        val chosen = selectedQuality
        for (i in 0 until row.childCount) {
            val button = row.getChildAt(i) as? Button ?: continue
            button.isAllCaps = false
            button.alpha = if (i == 0 && chosen == 2200 ||
                i == 1 && chosen == 2800) 1f else 0.55f
        }
    }

    private fun refreshQueue() {
        if (!::taskContainer.isInitialized) return
        val snapshot = try {
            store.snapshot()
        } catch (error: Exception) {
            summaryText.text = "无法读取任务列表：" + (error.message ?: "文件出错")
            return
        }

        folderText.text = if (outputTree.isBlank()) "输出文件夹：尚未选择"
            else "输出文件夹：已选择"
        allButton.text = if (snapshot.pausedAll) "继续全部任务" else "暂停全部任务"
        val running = snapshot.entries.count {
            it.status in setOf(
                OcrQueueLedger.Status.RUNNING,
                OcrQueueLedger.Status.PAUSING,
                OcrQueueLedger.Status.CANCELLING
            )
        }
        val waiting = snapshot.entries.count { it.status == OcrQueueLedger.Status.WAITING }
        val done = snapshot.entries.count { it.status == OcrQueueLedger.Status.DONE }
        val paused = snapshot.entries.count { it.status == OcrQueueLedger.Status.PAUSED }
        summaryText.text = "共 ${snapshot.entries.size} 项 · 运行 $running · 等待 $waiting · 暂停 $paused · 完成 $done"

        val y = if (::scroll.isInitialized) scroll.scrollY else 0
        taskContainer.removeAllViews()
        for ((index, entry) in snapshot.entries.withIndex()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(
                    if (index % 2 == 0) 0xFFF3F5F7.toInt() else 0xFFEBEEF2.toInt()
                )
            }
            box.addView(text("${index + 1}. ${entry.name}", 15f))

            val status = when (entry.status) {
                OcrQueueLedger.Status.WAITING -> "排队等待"
                OcrQueueLedger.Status.RUNNING -> "正在处理"
                OcrQueueLedger.Status.PAUSING -> "正在暂停"
                OcrQueueLedger.Status.PAUSED -> "已暂停"
                OcrQueueLedger.Status.CANCELLING -> "正在取消"
                OcrQueueLedger.Status.CANCELLED -> "已取消"
                OcrQueueLedger.Status.FAILED -> "处理失败"
                OcrQueueLedger.Status.DONE -> "已完成"
                else -> entry.status
            }
            val detail = if (entry.total > 0) {
                "第 ${entry.page}/${entry.total} 页 · ${entry.progress}%"
            } else "${entry.progress}%"
            box.addView(text("$status · $detail · ${entry.stage}", 12f, 5))
            if (entry.status == OcrQueueLedger.Status.RUNNING ||
                entry.status == OcrQueueLedger.Status.PAUSING ||
                entry.status == OcrQueueLedger.Status.DONE
            ) {
                box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = entry.progress
                })
            }
            if (entry.error.isNotBlank()) box.addView(
                text("原因：${entry.error}", 12f, 5)
            )

            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            when (entry.status) {
                OcrQueueLedger.Status.RUNNING, OcrQueueLedger.Status.WAITING -> {
                    actions.addView(action("暂停") { store.pause(entry.id); refreshQueue() })
                    actions.addView(action("取消") { cancelEntry(entry.id) })
                }
                OcrQueueLedger.Status.PAUSING -> {
                    actions.addView(action("取消") { cancelEntry(entry.id) })
                }
                OcrQueueLedger.Status.PAUSED -> {
                    actions.addView(action("继续") {
                        store.resume(entry.id)
                        if (!store.snapshot().pausedAll) OcrQueueScheduler.wake(this)
                        refreshQueue()
                    })
                    actions.addView(action("取消") { cancelEntry(entry.id) })
                }
                OcrQueueLedger.Status.FAILED, OcrQueueLedger.Status.CANCELLED -> {
                    actions.addView(action("重试") {
                        if (cleanupInProgress.contains(entry.id)) {
                            toast("正在清理任务缓存，请稍后重试")
                            return@action
                        }
                        store.retry(entry.id)
                        if (!store.snapshot().pausedAll) OcrQueueScheduler.wake(this)
                        refreshQueue()
                    })
                    actions.addView(action("移除") { removeEntry(entry.id) })
                }
                OcrQueueLedger.Status.DONE -> {
                    actions.addView(action("打开 PDF") {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(entry.outputUri), "application/pdf")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        } catch (_: Exception) {
                            toast("无法打开 PDF，请去输出文件夹查看")
                        }
                    })
                    actions.addView(action("移除") { removeEntry(entry.id) })
                }
            }
            if (actions.childCount > 0) box.addView(actions)
            taskContainer.addView(box, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        if (::scroll.isInitialized) scroll.post { scroll.scrollTo(0, y) }
    }

    private fun cancelEntry(id: String) {
        val previous = store.stateOf(id)
        val uri = store.snapshot().entries.find { it.id == id }?.outputUri ?: ""
        store.cancel(id)
        refreshQueue()

        // Running work is cleaned up by the worker after it exits safely.
        // Paused or waiting tasks have no active PDFBox/OCR resources, so
        // clean their private work dir off the UI thread right away.
        if (previous in setOf(
                OcrQueueLedger.Status.PAUSED,
                OcrQueueLedger.Status.FAILED,
                OcrQueueLedger.Status.WAITING
            )) {
            cleanupInProgress.add(id)
            Thread {
                try {
                    OcrQueueScheduler.deleteIncompleteOutput(applicationContext, uri)
                    OcrQueueScheduler.cleanTaskCache(applicationContext, id)
                    if (store.stateOf(id) == OcrQueueLedger.Status.CANCELLED) {
                        store.clearOutput(id)
                    }
                } finally {
                    cleanupInProgress.remove(id)
                    runOnUiThread { if (!isFinishing) refreshQueue() }
                }
            }.start()
        }
    }

    private fun removeEntry(id: String) {
        if (cleanupInProgress.contains(id)) {
            toast("请等任务缓存清理完成后再移除")
            return
        }
        if (store.remove(id)) {
            // Deleting a leftover 1GB task scratch directory must not block UI.
            Thread { OcrQueueScheduler.cleanTaskCache(applicationContext, id) }.start()
        }
        refreshQueue()
    }

    private fun action(title: String, onTap: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener {
                try { onTap() }
                catch (error: Exception) {
                    toast("操作失败：" + (error.message ?: error.javaClass.simpleName))
                }
            }
            layoutParams = weightedParams()
        }

    private fun weightedParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun text(content: String, size: Float, topMargin: Int = 0): TextView =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(0xFF25292D.toInt())
            setPadding(0, dp(topMargin), 0, dp(6))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0) ?: "document.pdf"
            }
        return uri.lastPathSegment ?: "document.pdf"
    }
}
