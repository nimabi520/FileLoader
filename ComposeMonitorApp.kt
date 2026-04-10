package topview.fileloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.LinkedHashSet
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import java.awt.FileDialog
import java.awt.Frame
import javax.swing.JFrame
import javax.swing.SwingUtilities

private data class UploadTask(val file: File, val batchId: String)

private data class UploadRecordUi(
    val time: String,
    val fileName: String,
    val batchId: String,
    val status: String
)

private data class BatchStatusUi(
    val batchId: String,
    val status: String,
    val downloadable: Boolean
)

private class ComposeMonitorStore : MonitorCallbacks {
    private val logger = Logger.getLogger(ComposeMonitorStore::class.java.name)

    private val monitoredFolders = ConcurrentHashMap<Path, Boolean>()
    private val batchDownloadableFlags = ConcurrentHashMap<String, Boolean>()
    private val knownBatchIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val uploadQueue: BlockingQueue<UploadTask> = LinkedBlockingQueue()
    private val uploadExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val batchRefreshScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val batchQueryExecutor: ExecutorService = Executors.newFixedThreadPool(4)

    private val closed = AtomicBoolean(false)

    @Volatile
    private var autoRefreshTask: ScheduledFuture<*>? = null

    @Volatile
    private var currentBatchId: String? = null

    @Volatile
    private var batchStartTime: Long = 0

    private val fileMonitor = FileMonitor(this)

    val folderInput = mutableStateOf("")
    val selectedFolder = mutableStateOf<String?>(null)
    val statusText = mutableStateOf("状态: 等待监控...")
    val autoRefreshLabel = mutableStateOf("手动刷新")

    val monitoredFoldersUi = mutableStateListOf<String>()
    val uploadRecords = mutableStateListOf<UploadRecordUi>()
    val batchStatuses = mutableStateListOf<BatchStatusUi>()
    val logs = mutableStateListOf<String>()
    val progressMap = mutableStateMapOf<String, Int>()

    init {
        BatchDatabase.init()
        loadBatchHistory()
        startUploadWorker()
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        autoRefreshTask?.cancel(false)
        fileMonitor.shutdown()
        batchRefreshScheduler.shutdownNow()
        batchQueryExecutor.shutdownNow()

        if (!uploadQueue.isEmpty()) {
            val remaining = mutableListOf<UploadTask>().also { uploadQueue.drainTo(it) }
            logger.info("关闭时清空上传队列，剩余文件: ${remaining.size}")
        }
        uploadExecutor.shutdownNow()

        // 在后台线程温和等待线程池结束，不阻塞窗口关闭路径
        Thread {
            try {
                if (!uploadExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warning("上传线程池未在 3 秒内完全终止")
                }
                if (!batchQueryExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logger.warning("状态查询线程池未在 3 秒内完全终止")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply {
            isDaemon = true
            name = "Shutdown-Cleanup"
            start()
        }
    }

    fun browseFolder() {
        val selected = chooseDirectory("选择要监控的文件夹") ?: return
        folderInput.value = selected.absolutePath
    }

    fun addFolderFromInput() {
        addFolder(folderInput.value)
        folderInput.value = ""
    }

    fun addFolder(pathText: String) {
        val path = pathText.trim()
        if (path.isEmpty()) {
            addLog("请输入有效的文件夹路径")
            return
        }

        val file = File(path)
        if (!file.exists()) {
            updateStatus("状态: 路径不存在 $path")
            addLog("路径不存在: $path")
            return
        }

        // 如果是压缩包，解压并上传内部文件
        if (file.isFile && ArchiveExtractor.isArchiveFile(file.name)) {
            handleArchiveUpload(file)
            return
        }

        // 如果不是目录，提示错误
        if (!file.isDirectory) {
            updateStatus("状态: 无效路径（请选择文件夹或压缩包）$path")
            addLog("无效路径（请选择文件夹或压缩包）: $path")
            return
        }

        val folder = file.toPath()
        if (monitoredFolders.putIfAbsent(folder, true) == null) {
            fileMonitor.startMonitoring(folder)
            refreshMonitoredFoldersUi()
            updateStatus("状态: 成功添加文件夹 $folder")
            addLog("添加监控文件夹: $folder")
        } else {
            updateStatus("状态: 文件夹已存在 $folder")
            addLog("文件夹已存在: $folder")
        }
    }

    /**
     * 处理压缩包上传：解压并逐个上传内部文件
     */
    private fun handleArchiveUpload(archiveFile: File) {
        val batchId = System.currentTimeMillis().toString()
        addLog("检测到压缩包: ${archiveFile.name}")
        updateStatus("正在解压: ${archiveFile.name}")

        batchQueryExecutor.submit {
            var tempDir: Path? = null
            try {
                // 创建临时解压目录
                tempDir = ArchiveExtractor.createTempExtractDir(batchId)
                addLog("创建临时解压目录: $tempDir")

                // 解压 ZIP 文件
                val extractedFiles = ArchiveExtractor.extractZip(archiveFile, tempDir)
                addLog("解压完成，共 ${extractedFiles.size} 个文件")

                // 过滤出有效文件
                val validFiles = ArchiveExtractor.filterValidFiles(extractedFiles)
                if (validFiles.isEmpty()) {
                    addLog("压缩包内没有可上传的文件")
                    updateStatus("压缩包为空: ${archiveFile.name}")
                    return@submit
                }

                addLog("准备上传 ${validFiles.size} 个文件，批次ID: $batchId")
                updateStatus("正在上传压缩包内容 (${validFiles.size} 个文件)...")

                // 注册批次ID
                registerBatchId(batchId)

                // 逐个添加上传任务
                validFiles.forEach { file ->
                    val task = UploadTask(file, batchId)
                    if (uploadQueue.offer(task)) {
                        addLog("已加入上传队列: ${file.name} (批次: $batchId)")
                    } else {
                        addLog("加入上传队列失败: ${file.name}")
                    }
                }

                updateStatus("压缩包文件已加入上传队列: ${archiveFile.name} (${validFiles.size} 个文件)")

            } catch (e: Exception) {
                logger.log(Level.SEVERE, "解压失败: ${archiveFile.path}", e)
                addLog("解压失败: ${archiveFile.name} - ${e.message}")
                updateStatus("解压失败: ${archiveFile.name}")
            } finally {
                // 延迟清理临时文件（5分钟后）
                tempDir?.let { dir ->
                    batchRefreshScheduler.schedule({
                        ArchiveExtractor.cleanupTempDir(dir)
                        addLog("清理临时解压目录: $dir")
                    }, 5, TimeUnit.MINUTES)
                }
            }
        }
    }

    fun removeSelectedFolder() {
        val selected = selectedFolder.value
        if (selected == null) {
            addLog("请先在左侧列表选择要移除的文件夹")
            return
        }
        removeFolder(Paths.get(selected))
    }

    private fun removeFolder(path: Path) {
        if (monitoredFolders.remove(path) != null) {
            fileMonitor.stopMonitoring(path)
            refreshMonitoredFoldersUi()
            selectedFolder.value = null
            updateStatus("状态: 成功移除文件夹 $path")
            addLog("移除监控文件夹: $path")
        } else {
            updateStatus("状态: 文件夹未找到 $path")
        }
    }

    fun openConfig() {
        onUi {
            val owner = JFrame()
            owner.setLocationRelativeTo(null)
            val dialog = ConfigDialog(owner)
            dialog.isVisible = true
            if (dialog.isConfigChanged) {
                addLog("配置已更新")
            }
            owner.dispose()
        }
    }

    fun refreshAllBatchStatuses() {
        val ids: List<String>
        synchronized(knownBatchIds) {
            ids = knownBatchIds.toList()
        }
        if (ids.isEmpty()) {
            addLog("暂无已知批次ID，刷新跳过")
            return
        }

        addLog("刷新 ${ids.size} 个批次的状态...")
        ids.forEach { refreshBatchStatus(it) }
    }

    fun setAutoRefresh(label: String) {
        autoRefreshTask?.cancel(false)
        autoRefreshTask = null
        autoRefreshLabel.value = label

        val seconds = when (label) {
            "每10秒" -> 10L
            "每30秒" -> 30L
            "每1分钟" -> 60L
            "每5分钟" -> 300L
            else -> 0L
        }

        if (seconds <= 0) {
            addLog("已切换为手动刷新")
            return
        }

        addLog("已启用自动刷新，间隔: $label")
        autoRefreshTask = batchRefreshScheduler.scheduleAtFixedRate(
            { refreshAllBatchStatuses() },
            seconds,
            seconds,
            TimeUnit.SECONDS
        )
    }

    fun downloadBatchResult(batchId: String) {
        val saveDir = chooseDirectory("选择保存目录") ?: return
        addLog("开始下载批次 $batchId 的汇总报告...")
        batchQueryExecutor.submit {
            val result = BatchStatusService.downloadBatchResult(batchId, saveDir)
            addLog(result)
            updateStatus(result)
        }
    }

    fun downloadBatchWrong(batchId: String) {
        val saveDir = chooseDirectory("选择保存目录") ?: return
        addLog("开始下载批次 $batchId 的异常文件...")
        batchQueryExecutor.submit {
            val result = DownloadService.downloadBatch(batchId, saveDir, true)
            addLog(result)
            updateStatus(result)
        }
    }

    override fun updateStatus(status: String) {
        onUi {
            statusText.value = status
        }
    }

    override fun addLog(message: String) {
        onUi {
            logs.add("[${LocalTime.now()}] $message")
            if (logs.size > 2000) {
                logs.removeAt(0)
            }
        }
    }

    override fun uploadFile(filePath: String) {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            logger.warning("File not found or invalid: $filePath")
            updateStatus("错误: 文件不存在 $filePath")
            addLog("错误: 文件不存在 $filePath")
            return
        }

        val batchId: String
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (currentBatchId == null || now - batchStartTime > 5000) {
                currentBatchId = now.toString()
                batchStartTime = now
            }
            batchId = currentBatchId!!
        }

        val task = UploadTask(file, batchId)
        if (uploadQueue.offer(task)) {
            addLog("已加入上传队列: ${file.name} (批次: $batchId, 队列大小: ${uploadQueue.size})")
        } else {
            addLog("加入上传队列失败: ${file.name}")
        }
    }

    private fun loadBatchHistory() {
        val records = BatchDatabase.getAll()
        if (records.isEmpty()) {
            return
        }

        synchronized(knownBatchIds) {
            records.forEach { knownBatchIds.add(it.batchId) }
        }

        onUi {
            records.forEach { record ->
                if (batchStatuses.none { it.batchId == record.batchId }) {
                    batchStatuses.add(BatchStatusUi(record.batchId, record.status, false))
                }
            }
        }
        logger.info("从数据库恢复 ${records.size} 条历史批次记录")
    }

    private fun refreshMonitoredFoldersUi() {
        onUi {
            monitoredFoldersUi.clear()
            monitoredFolders.keys
                .map { it.toString() }
                .sorted()
                .forEach { monitoredFoldersUi.add(it) }
        }
    }

    private fun startUploadWorker() {
        repeat(3) {
            uploadExecutor.submit {
                while (!Thread.currentThread().isInterrupted && !closed.get()) {
                    try {
                        val task = uploadQueue.take()
                        performUpload(task)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        logger.log(Level.SEVERE, "Upload worker error", e)
                        addLog("上传工作线程错误: ${e.message}")
                    }
                }
            }
        }
    }

    private fun performUpload(task: UploadTask) {
        val file = task.file
        val fileName = file.name
        val batchId = task.batchId
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

        onUi {
            uploadRecords.add(UploadRecordUi(timeStr, fileName, batchId, "上传中..."))
            progressMap[fileName] = -1
        }
        registerBatchId(batchId)

        try {
            addLog("开始上传文件: $fileName")
            updateStatus("正在上传: $fileName")

            val response = ProgressUploadService.uploadFile(file, batchId) { progress ->
                onUi {
                    progressMap[fileName] = progress
                    statusText.value = "正在上传: $fileName ($progress%)"
                }
            }

            if (response.code == 200) {
                updateStatus("上传成功: $fileName")
                addLog("上传成功: $fileName - ${response.message}")
                updateUploadStatus(batchId, fileName, "成功")
            } else {
                updateStatus("上传失败: $fileName")
                addLog("上传失败: $fileName - ${response.message}")
                updateUploadStatus(batchId, fileName, "失败")
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Upload error for ${file.path}", e)
            updateStatus("上传错误: $fileName")
            addLog("上传错误: $fileName - ${e.message}")
            updateUploadStatus(batchId, fileName, "错误")
        } finally {
            onUi {
                progressMap.remove(fileName)
            }
            addLog("当前队列大小: ${uploadQueue.size}")
        }
    }

    private fun updateUploadStatus(batchId: String, fileName: String, status: String) {
        onUi {
            val idx = uploadRecords.indexOfFirst { it.batchId == batchId && it.fileName == fileName }
            if (idx >= 0) {
                val old = uploadRecords[idx]
                uploadRecords[idx] = old.copy(status = status)
            }
        }
    }

    private fun registerBatchId(batchId: String) {
        val isNew = knownBatchIds.add(batchId)
        if (isNew) {
            onUi {
                if (batchStatuses.none { it.batchId == batchId }) {
                    batchStatuses.add(BatchStatusUi(batchId, "查询中...", false))
                }
            }
            batchDownloadableFlags[batchId] = false
            BatchDatabase.upsert(batchId, "查询中...")
        }
        refreshBatchStatus(batchId)
    }

    private fun refreshBatchStatus(batchId: String) {
        if (closed.get()) {
            return
        }

        batchQueryExecutor.submit {
            val result = BatchStatusService.fetchBatchStatus(batchId)
            updateBatchStatus(batchId, result.msg, result.isDownloadable)
            if (result.isDownloadable) {
                addLog("批次 $batchId 已可下载")
            }
        }
    }

    private fun updateBatchStatus(batchId: String, status: String, downloadable: Boolean) {
        batchDownloadableFlags[batchId] = downloadable
        BatchDatabase.updateStatus(batchId, status)
        onUi {
            val idx = batchStatuses.indexOfFirst { it.batchId == batchId }
            if (idx >= 0) {
                val old = batchStatuses[idx]
                batchStatuses[idx] = old.copy(status = status, downloadable = downloadable)
            } else {
                batchStatuses.add(BatchStatusUi(batchId, status, downloadable))
            }
        }
    }

    private fun onUi(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    private fun chooseDirectory(title: String): File? {
        // 使用FileDialog获得原生文件选择器体验
        // 在macOS上需要设置系统属性才能选择目录
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file
        val directory = dialog.directory
        return if (file != null && directory != null) {
            File(directory, file)
        } else if (directory != null) {
            File(directory)
        } else {
            null
        }
    }
}

private val LightMaterialYouScheme = lightColorScheme(
    primary = Color(0xFF345CA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001944),
    secondary = Color(0xFF38656A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFBCEBF1),
    onSecondaryContainer = Color(0xFF001F23),
    tertiary = Color(0xFF705575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD8FE),
    onTertiaryContainer = Color(0xFF29132F),
    error = Color(0xFFB3261E),
    background = Color(0xFFF9F8FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE2E2EE),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757680)
)

private val DarkMaterialYouScheme = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002D6A),
    primaryContainer = Color(0xFF18438F),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFA0CFD4),
    onSecondary = Color(0xFF00363C),
    secondaryContainer = Color(0xFF1E4D52),
    onSecondaryContainer = Color(0xFFBCEBF1),
    tertiary = Color(0xFFDDBCE1),
    onTertiary = Color(0xFF402843),
    tertiaryContainer = Color(0xFF583E5B),
    onTertiaryContainer = Color(0xFFFAD8FE),
    error = Color(0xFFFFB4AB),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2EA),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2EA),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C6D2),
    outline = Color(0xFF8F909A)
)

private val FileLoaderTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium
    )
)

@Composable
private fun FileLoaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkMaterialYouScheme else LightMaterialYouScheme,
        typography = FileLoaderTypography,
        content = content
    )
}

@Composable
private fun ComposeMonitorScreen(store: ComposeMonitorStore) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("监控记录", "批次状态", "系统日志")
    val colors = MaterialTheme.colorScheme
    val background = remember(colors) {
        Brush.verticalGradient(
            colors = listOf(
                colors.background,
                colors.surfaceVariant.copy(alpha = 0.35f),
                colors.background
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.94f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text("FileLoader", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "简洁、稳定、现代化的自动上传面板",
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = store.folderInput.value,
                            onValueChange = { store.folderInput.value = it },
                            label = { Text("文件夹路径") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = { store.browseFolder() },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("浏览")
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { store.addFolderFromInput() },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("添加文件夹")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { store.removeSelectedFolder() },
                            enabled = store.selectedFolder.value != null,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("移除选中")
                        }
                        Button(
                            onClick = { store.openConfig() },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Text("配置")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "监控文件夹",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                        HorizontalDivider(color = colors.outline.copy(alpha = 0.25f))

                        if (store.monitoredFoldersUi.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("暂无监控文件夹", color = colors.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(store.monitoredFoldersUi) { folder ->
                                    val selected = store.selectedFolder.value == folder
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) colors.secondaryContainer else colors.surface,
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (selected) colors.secondary else colors.outline.copy(alpha = 0.25f)
                                        ),
                                        tonalElevation = if (selected) 2.dp else 0.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { store.selectedFolder.value = folder }
                                    ) {
                                        Text(
                                            text = folder,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = Color.Transparent,
                            divider = {
                                HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))
                            }
                        ) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }

                        when (selectedTab) {
                            0 -> UploadRecordsTab(store)
                            1 -> BatchStatusTab(store)
                            else -> LogTab(store)
                        }
                    }
                }
            }

            if (store.progressMap.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = 0.95f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text("上传进度", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(10.dp))

                        store.progressMap.toSortedMap().forEach { (fileName, progress) ->
                            Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (progress < 0) {
                                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                                } else {
                                    val value = progress.coerceIn(0, 100) / 100f
                                    LinearProgressIndicator(progress = { value }, modifier = Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (progress < 0) "..." else "$progress%")
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.surface.copy(alpha = 0.9f),
                tonalElevation = 1.dp
            ) {
                Text(
                    text = store.statusText.value,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun UploadRecordsTab(store: ComposeMonitorStore) {
    if (store.uploadRecords.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无上传记录")
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val colors = MaterialTheme.colorScheme

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text("时间", modifier = Modifier.weight(0.28f), color = colors.onSurfaceVariant)
            Text("文件名", modifier = Modifier.weight(0.30f), color = colors.onSurfaceVariant)
            Text("批次ID", modifier = Modifier.weight(0.25f), color = colors.onSurfaceVariant)
            Text("状态", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(store.uploadRecords) { row ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f)),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(row.time, modifier = Modifier.weight(0.28f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.fileName, modifier = Modifier.weight(0.30f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.batchId, modifier = Modifier.weight(0.25f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.status, modifier = Modifier.weight(0.17f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatchStatusTab(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { store.refreshAllBatchStatuses() }, shape = RoundedCornerShape(14.dp)) {
                Text("刷新状态")
            }
            Text("自动刷新", color = colors.onSurfaceVariant)
            RefreshOptionButton("手动刷新", store)
            RefreshOptionButton("每10秒", store)
            RefreshOptionButton("每30秒", store)
            RefreshOptionButton("每1分钟", store)
            RefreshOptionButton("每5分钟", store)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("当前模式: ${store.autoRefreshLabel.value}", color = colors.onSurfaceVariant)
        Spacer(modifier = Modifier.height(10.dp))

        if (store.batchStatuses.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无批次记录")
            }
            return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("批次ID", modifier = Modifier.weight(0.28f), color = colors.onSurfaceVariant)
            Text("状态", modifier = Modifier.weight(0.38f), color = colors.onSurfaceVariant)
            Text("下载报告", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
            Text("下载异常", modifier = Modifier.weight(0.17f), color = colors.onSurfaceVariant)
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(store.batchStatuses) { row ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (row.downloadable) colors.secondaryContainer.copy(alpha = 0.45f) else colors.surface,
                    border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f)),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(row.batchId, modifier = Modifier.weight(0.28f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.status, modifier = Modifier.weight(0.38f), maxLines = 2, overflow = TextOverflow.Ellipsis)

                        Box(modifier = Modifier.weight(0.17f), contentAlignment = Alignment.CenterStart) {
                            Button(
                                onClick = { store.downloadBatchResult(row.batchId) },
                                enabled = row.downloadable,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("下载")
                            }
                        }

                        Box(modifier = Modifier.weight(0.17f), contentAlignment = Alignment.CenterStart) {
                            OutlinedButton(
                                onClick = { store.downloadBatchWrong(row.batchId) },
                                enabled = row.downloadable,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("下载")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RefreshOptionButton(label: String, store: ComposeMonitorStore) {
    val active = store.autoRefreshLabel.value == label
    if (active) {
        Button(
            onClick = { store.setAutoRefresh(label) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.filledTonalButtonColors(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = { store.setAutoRefresh(label) },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(label)
        }
    }
}

@Composable
private fun LogTab(store: ComposeMonitorStore) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .background(Color.Transparent)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.2f))
        ) {
            if (store.logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志", color = colors.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(store.logs) { line ->
                        Text(line, fontFamily = FontFamily.Monospace, color = colors.onSurface)
                    }
                }
            }
        }
    }
}

fun startComposeApp() = application {
    println("[ComposeMonitorApp] Compose UI started.")
    val store = remember { ComposeMonitorStore() }

    Window(
        title = "多文件夹监控上传工具（Compose）",
        onCloseRequest = {
            store.shutdown()
            exitApplication()
        }
    ) {
        DisposableEffect(Unit) {
            onDispose {
                store.shutdown()
            }
        }

        FileLoaderTheme {
            ComposeMonitorScreen(store)
        }
    }
}

fun main() = startComposeApp()
