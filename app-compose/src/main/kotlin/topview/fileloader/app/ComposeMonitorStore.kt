package topview.fileloader.app

import topview.fileloader.config.AppConfig
import topview.fileloader.monitor.FileMonitor
import topview.fileloader.monitor.MonitorCallbacks
import topview.fileloader.persistence.BatchDatabase
import topview.fileloader.service.BatchIdService
import topview.fileloader.service.BatchStatusService
import topview.fileloader.service.DownloadService
import topview.fileloader.service.EncryptPasswordService
import topview.fileloader.service.ProgressUploadService
import topview.fileloader.util.ArchiveExtractor

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset

import java.awt.Desktop
import java.io.File
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
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

internal class ComposeMonitorStore : MonitorCallbacks {
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

    @Volatile
    private var loginCheckInFlight = false

    @Volatile
    private var logoutInFlight = false

    @Volatile
    private var loggedInFlag = false

    @Volatile
    private var lastLoginBlockLogAt = 0L

    private val fileMonitor = FileMonitor(this)

    val folderInput = mutableStateOf("")
    val selectedFolder = mutableStateOf<String?>(null)
    val statusText = mutableStateOf("状态: 等待监控...")
    val autoRefreshLabel = mutableStateOf("手动刷新")
    val showSettingsPanel = mutableStateOf(false)
    val showLoginDialog = mutableStateOf(false)
    val showEncryptLoginDialog = mutableStateOf(false)
    val snackbarHostState = SnackbarHostState()
    val showFolderContextMenu = mutableStateOf(false)
    val contextMenuFolder = mutableStateOf<String?>(null)
    val contextMenuOffset = mutableStateOf(Offset.Zero)
    val isDragOver = mutableStateOf(false)
    val pendingSnackbarMessage = mutableStateOf<String?>(null)
    val serverUrlInput = mutableStateOf("")
    val connectTimeoutInput = mutableStateOf("")
    val readTimeoutInput = mutableStateOf("")
    val onlyPdfInput = mutableStateOf(false)
    val isCheckingLogin = mutableStateOf(false)
    val isLoggingOut = mutableStateOf(false)
    val isLoggedIn = mutableStateOf(false)
    val loginDialogMessage = mutableStateOf("尚未检查登录状态")
    val loginUserId = mutableStateOf("")
    val loginUserName = mutableStateOf("")
    val loginRole = mutableStateOf(0)
    val loginSessionId = mutableStateOf("")
    val loginTime = mutableStateOf("")
    val encryptLoginUserIdInput = mutableStateOf("")
    val encryptLoginPasswordInput = mutableStateOf("")
    val isEncryptingPassword = mutableStateOf(false)
    val encryptLoginErrorMessage = mutableStateOf("")

    val monitoredFoldersUi = mutableStateListOf<String>()
    val uploadRecords = mutableStateListOf<UploadRecordUi>()
    val batchStatuses = mutableStateListOf<BatchStatusUi>()
    val logs = mutableStateListOf<String>()
    val progressMap = mutableStateMapOf<String, Int>()
    val sortOrder = mutableStateOf("newest")
    val showRefreshMenu = mutableStateOf(false)
    val showSortMenu = mutableStateOf(false)

    init {
        restoreLoginState()
        BatchDatabase.init()
        reloadConfigDraft()
        loadBatchHistory()
        startUploadWorker()
    }

    fun restoreLoginState() {
        if (AppConfig.getEncryptedPassword().isNotBlank()) {
            loggedInFlag = true
            isLoggedIn.value = true
            loginDialogMessage.value = "凭据已恢复，登录有效"
        }
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
        if (!ensureLoggedIn("添加监控文件夹")) {
            return
        }
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
            showSnackbar("已添加监控文件夹: $folder")
        } else {
            updateStatus("状态: 文件夹已存在 $folder")
            addLog("文件夹已存在: $folder")
        }
    }

    /**
     * 处理压缩包上传：解压并逐个上传内部文件
     */
    private fun handleArchiveUpload(archiveFile: File) {
        val result = BatchIdService.fetchBatchId()
        if (result.code != 200 || result.batchId.isNullOrEmpty()) {
            addLog("获取批次ID失败: ${result.msg}")
            updateStatus("获取批次ID失败")
            return
        }
        val batchId = result.batchId
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

    fun removeFolder(path: Path) {
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
        reloadConfigDraft()
        showSettingsPanel.value = true
    }

    fun closeSettingsPanel() {
        showSettingsPanel.value = false
    }

    fun addFolderFromDrop(paths: List<String>) {
        paths.forEach { path ->
            val file = File(path)
            if (file.isDirectory || (file.isFile && ArchiveExtractor.isArchiveFile(file.name))) {
                addFolder(path)
            }
        }
    }

    fun showSnackbar(message: String) {
        onUi { pendingSnackbarMessage.value = message }
    }

    fun openFolderInExplorer(path: String) {
        try {
            Desktop.getDesktop().open(File(path))
        } catch (e: Exception) {
            addLog("打开目录失败: $path - ${e.message}")
        }
    }

    fun triggerFolderUpload(path: String) {
        val folder = File(path)
        if (!folder.isDirectory) return
        folder.listFiles()?.forEach { file ->
            if (file.isFile) {
                uploadFile(file.absolutePath)
            }
        }
    }

    fun openLoginDialog() {
        showLoginDialog.value = true
    }

    fun closeLoginDialog() {
        showLoginDialog.value = false
    }

    fun openEncryptLoginDialog() {
        encryptLoginUserIdInput.value = AppConfig.getUserId()
        encryptLoginPasswordInput.value = ""
        encryptLoginErrorMessage.value = ""
        showEncryptLoginDialog.value = true
    }

    fun closeEncryptLoginDialog() {
        showEncryptLoginDialog.value = false
        encryptLoginErrorMessage.value = ""
    }

    fun logout(triggerSource: String = "手动退出登录") {
        if (closed.get()) {
            return
        }
        if (logoutInFlight || loginCheckInFlight) {
            return
        }
        logoutInFlight = true

        onUi {
            isLoggingOut.value = true
            loginDialogMessage.value = "正在退出登录..."
        }

        batchQueryExecutor.submit {
            try {
                loggedInFlag = false
                AppConfig.clearEncryptedPassword()
                onUi {
                    isLoggedIn.value = false
                    clearLoginIdentityUiState()
                    loginDialogMessage.value = "已退出登录"
                    showLoginDialog.value = true
                }
                showSnackbar("退出登录成功")
                val message = "退出登录成功"
                onUi {
                    statusText.value = message
                }
                addLog("[$triggerSource] $message")
            } finally {
                logoutInFlight = false
                onUi {
                    isLoggingOut.value = false
                }
            }
        }
    }

    fun encryptLogin() {
        if (closed.get()) {
            return
        }
        val userId = encryptLoginUserIdInput.value.trim()
        val password = encryptLoginPasswordInput.value
        if (userId.isBlank() || password.isBlank()) {
            encryptLoginErrorMessage.value = "请输入用户ID和密码"
            return
        }

        isEncryptingPassword.value = true
        encryptLoginErrorMessage.value = ""

        batchQueryExecutor.submit {
            try {
                val result = EncryptPasswordService.encryptPassword(userId, password)
                when (result.state) {
                    EncryptPasswordService.State.SUCCESS -> {
                        val encryptedPwd = result.encryptedPassword
                        AppConfig.setUserId(userId)
                        AppConfig.setEncryptedPassword(encryptedPwd)
                        loggedInFlag = true
                        onUi {
                            isLoggedIn.value = true
                            closeEncryptLoginDialog()
                        }
                        addLog("[登录成功] 用户ID: $userId")
                        showSnackbar("登录成功")
                    }
                    EncryptPasswordService.State.NETWORK_ERROR,
                    EncryptPasswordService.State.SERVER_ERROR,
                    EncryptPasswordService.State.PARSE_ERROR -> {
                        onUi {
                            encryptLoginErrorMessage.value = result.message
                        }
                        addLog("[登录失败] ${result.message}")
                    }
                    null -> {
                        onUi {
                            encryptLoginErrorMessage.value = "登录结果未知"
                        }
                        addLog("[登录失败] 结果未知")
                    }
                }
            } finally {
                onUi {
                    isEncryptingPassword.value = false
                }
            }
        }
    }

    fun checkLoginStatus(triggerSource: String = "手动检查", openDialogOnUnauthorized: Boolean = false) {
        if (closed.get()) {
            return
        }
        if (loginCheckInFlight || logoutInFlight) {
            return
        }
        loginCheckInFlight = true

        onUi {
            isCheckingLogin.value = true
            loginDialogMessage.value = "正在检查登录状态..."
        }

        // 不再调用远程服务器验证登录，仅检查本地是否保存了加密密码
        val hasEncryptedPassword = AppConfig.getEncryptedPassword().isNotBlank()
        val message = if (hasEncryptedPassword) {
            loggedInFlag = true
            onUi {
                isLoggedIn.value = true
                loginDialogMessage.value = "登录有效"
                val uid = AppConfig.getUserId()
                if (uid.isNotBlank()) {
                    loginUserId.value = uid
                }
            }
            "登录状态正常，凭据已保存"
        } else {
            loggedInFlag = false
            onUi {
                isLoggedIn.value = false
                clearLoginIdentityUiState()
                loginDialogMessage.value = "当前未登录"
            }
            "当前未登录"
        }

        onUi {
            statusText.value = message
        }
        addLog("[$triggerSource] $message")
        loginCheckInFlight = false
        onUi {
            isCheckingLogin.value = false
        }
    }

    fun saveConfigFromDialog(): String? {
        val serverUrl = serverUrlInput.value.trim()
        if (serverUrl.isEmpty()) {
            return "服务器URL不能为空"
        }

        val connectTimeout: Int
        val readTimeout: Int
        try {
            connectTimeout = connectTimeoutInput.value.trim().toInt()
            readTimeout = readTimeoutInput.value.trim().toInt()
        } catch (_: NumberFormatException) {
            return "超时时间必须是数字"
        }

        if (connectTimeout <= 0 || readTimeout <= 0) {
            return "超时时间必须大于0"
        }

        AppConfig.setServerUrl(serverUrl)
        AppConfig.setConnectTimeout(connectTimeout)
        AppConfig.setReadTimeout(readTimeout)
        AppConfig.setOnlyUploadPdf(onlyPdfInput.value)

        addLog("配置已更新")
        updateStatus("配置已保存并生效")
        return null
    }

    private fun reloadConfigDraft() {
        serverUrlInput.value = AppConfig.getServerUrl()
        connectTimeoutInput.value = AppConfig.getConnectTimeout().toString()
        readTimeoutInput.value = AppConfig.getReadTimeout().toString()
        onlyPdfInput.value = AppConfig.isOnlyUploadPdf()
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
        if (!ensureLoggedIn("上传文件")) {
            return
        }

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
                val result = BatchIdService.fetchBatchId()
                if (result.code != 200 || result.batchId.isNullOrEmpty()) {
                    addLog("获取批次ID失败: ${result.msg}")
                    updateStatus("获取批次ID失败")
                    return
                }
                currentBatchId = result.batchId
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
                    batchStatuses.add(BatchStatusUi(record.batchId, record.status, false, record.createdAt))
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
                showSnackbar("上传成功: $fileName")
            } else {
                updateStatus("上传失败: $fileName")
                addLog("上传失败: $fileName - ${response.message}")
                updateUploadStatus(batchId, fileName, "失败")
                showSnackbar("上传失败: $fileName")
            }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Upload error for ${file.path}", e)
            updateStatus("上传错误: $fileName")
            addLog("上传错误: $fileName - ${e.message}")
            updateUploadStatus(batchId, fileName, "错误")
            showSnackbar("上传错误: $fileName")
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
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                    batchStatuses.add(BatchStatusUi(batchId, "查询中...", false, now))
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
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                batchStatuses.add(BatchStatusUi(batchId, status, downloadable, now))
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

    private fun ensureLoggedIn(actionName: String): Boolean {
        if (logoutInFlight) {
            val message = "正在退出登录，请稍后重试"
            updateStatus(message)
            addLog(message)
            onUi {
                openEncryptLoginDialog()
            }
            return false
        }

        val hasEncryptedPassword = AppConfig.getEncryptedPassword().isNotBlank()
        if (hasEncryptedPassword) {
            if (!loggedInFlag) {
                loggedInFlag = true
                isLoggedIn.value = true
            }
            return true
        }

        loggedInFlag = false
        isLoggedIn.value = false
        val now = System.currentTimeMillis()
        if (now - lastLoginBlockLogAt >= 5000) {
            lastLoginBlockLogAt = now
            val message = "未登录，无法${actionName}，请先完成登录"
            updateStatus(message)
            addLog(message)
        }
        onUi {
            openEncryptLoginDialog()
        }
        return false
    }

    private fun clearLoginIdentityUiState() {
        fillLoginIdentityUiState("", "", 0, "", "")
    }

    private fun fillLoginIdentityUiState(
        userId: String,
        userName: String,
        role: Int,
        sessionId: String,
        loginAt: String
    ) {
        loginUserId.value = userId
        loginUserName.value = userName
        loginRole.value = role
        loginSessionId.value = sessionId
        loginTime.value = loginAt
    }

    private fun chooseDirectory(title: String): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile
        } else {
            null
        }
    }
}
