package dev.munote.ocrtool

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private var inputUri: Uri? = null
    private var pendingMaxDimension = 2200

    private lateinit var fileLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button

    private val inputPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inputUri = uri
            getSharedPreferences("muocr", MODE_PRIVATE).edit()
                .putString("input_uri", uri.toString()).apply()
            fileLabel.text = displayName(uri)
            startButton.isEnabled = true
        }
    }

    private val outputPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val source = inputUri
        if (uri != null && source != null) {
            persist(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            enqueue(source, uri, pendingMaxDimension)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MuOCR"
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        getSharedPreferences("muocr", MODE_PRIVATE)
            .getString("input_uri", null)?.let { saved ->
                runCatching {
                    val restored = Uri.parse(saved)
                    inputUri = restored
                    fileLabel.text = displayName(restored)
                    startButton.isEnabled = true
                }
            }

        restoreRunningWork()
    }

    private fun buildUi(): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "MuOCR · 0.1.2"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "大文件 OCR：先逐页识别，随后每 16 页分批写入不可见文字层，分批释放内存。保留原 PDF 页面；失败后自动接着已完成的 OCR 和 PDF 导出进度处理。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(20))
        })

        fileLabel = TextView(this).apply {
            text = "尚未选择 PDF"
            textSize = 14f
        }
        root.addView(fileLabel)

        root.addView(Button(this).apply {
            text = "选择 PDF"
            setOnClickListener { inputPicker.launch(arrayOf("application/pdf")) }
        })

        root.addView(TextView(this).apply {
            text = "OCR 分辨率"
            textSize = 15f
            setPadding(0, dp(20), 0, dp(6))
        })

        val quality = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val balanced = RadioButton(this).apply {
            text = "平衡（最长边 2200 px，推荐 1GB 大文档）"
            id = View.generateViewId()
            isChecked = true
        }
        val high = RadioButton(this).apply {
            text = "高精度（最长边 2800 px，更慢、占内存更高）"
            id = View.generateViewId()
        }
        quality.addView(balanced)
        quality.addView(high)
        quality.setOnCheckedChangeListener { _, checked ->
            pendingMaxDimension = if (checked == high.id) 2800 else 2200
        }
        root.addView(quality)

        startButton = Button(this).apply {
            text = "开始 OCR 并导出 PDF"
            isEnabled = false
            setOnClickListener {
                val source = inputUri ?: return@setOnClickListener
                val name = displayName(source).substringBeforeLast(".") + "_OCR.pdf"
                outputPicker.launch(name)
            }
        }
        root.addView(startButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(18)
        ).apply { topMargin = dp(18) })

        statusLabel = TextView(this).apply {
            text = "待机"
            textSize = 14f
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(statusLabel)

        cancelButton = Button(this).apply {
            text = "取消"
            visibility = View.GONE
            setOnClickListener {
                runningWorkId()?.let { WorkManager.getInstance(this@MainActivity).cancelWorkById(it) }
            }
        }
        root.addView(cancelButton)

        root.addView(TextView(this).apply {
            text = "建议：处理 1GB 文件建议预留至少 4GB 存储空间，并连接充电器。先复制工作副本，再逐页识别，最后导出。出错时保留 OCR 缓存，成功后自动清理。不要清除应用数据。"
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })

        return root
    }

    private fun enqueue(source: Uri, destination: Uri, maxDimension: Int) {
        val request = OneTimeWorkRequestBuilder<LargePdfOcrWorker>()
            .setInputData(
                Data.Builder()
                    .putString(LargePdfOcrWorker.KEY_INPUT_URI, source.toString())
                    .putString(LargePdfOcrWorker.KEY_OUTPUT_URI, destination.toString())
                    .putInt(LargePdfOcrWorker.KEY_MAX_DIMENSION, maxDimension)
                    .build()
            )
            .addTag("muocr")
            .build()

        getSharedPreferences("muocr", MODE_PRIVATE)
            .edit()
            .putString("work_id", request.id.toString())
            .apply()

        WorkManager.getInstance(this).enqueue(request)
        observe(request.id)
    }

    private fun restoreRunningWork() {
        runningWorkId()?.let { observe(it) }
    }

    private fun observe(id: UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null) return@observe
            val page = info.progress.getInt("page", 0)
            val total = info.progress.getInt("total", 0)
            val stage = info.progress.getString("stage") ?: when (info.state) {
                WorkInfo.State.ENQUEUED -> "等待开始"
                WorkInfo.State.RUNNING -> "处理中"
                WorkInfo.State.SUCCEEDED -> "完成"
                WorkInfo.State.FAILED -> "失败"
                WorkInfo.State.CANCELLED -> "已取消"
                else -> info.state.name
            }

            val pct = info.progress.getInt("percent",
                if (total > 0) ((page * 100L) / total).toInt() else 0)

            progressBar.visibility = if (info.state.isFinished) View.GONE else View.VISIBLE
            progressBar.progress = pct.coerceIn(0, 100)
            cancelButton.visibility = if (info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.ENQUEUED) View.VISIBLE else View.GONE

            startButton.isEnabled = inputUri != null && info.state.isFinished
            statusLabel.text = if (page > 0 && total > 0) {
                "$stage · 第 $page / $total 页 · $pct%"
            } else {
                stage
            }

            if (info.state == WorkInfo.State.FAILED) {
                val error = info.outputData.getString("error")
                if (!error.isNullOrBlank()) {
                    statusLabel.text = "失败：$error\nOCR 完成页已缓存在设备中，再次运行可以续做。"
                }
            }
            if (info.state == WorkInfo.State.SUCCEEDED) {
                statusLabel.text = "完成：已导出可搜索 PDF"
            }
            if (info.state.isFinished) {
                getSharedPreferences("muocr", MODE_PRIVATE).edit().remove("work_id").apply()
            }
        }
    }

    private fun runningWorkId(): UUID? {
        val raw = getSharedPreferences("muocr", MODE_PRIVATE).getString("work_id", null)
        return raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    private fun persist(uri: Uri, flags: Int) {
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0) ?: "document.pdf"
        }
        return uri.lastPathSegment ?: "document.pdf"
    }
}
