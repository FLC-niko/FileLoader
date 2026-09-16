package topview.fileloader.app

import topview.fileloader.config.AppConfig
import topview.fileloader.monitor.SourceMonitor
import topview.fileloader.persistence.BatchDatabase
import topview.fileloader.service.AuthStatusService

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

import java.awt.Desktop
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class AppViewModel(
    private val authGateway: AuthGateway = DefaultAuthGateway(),
    private val batchGateway: BatchGateway = DefaultBatchGateway(),
    uploadGateway: UploadGateway = DefaultUploadGateway(),
    taskRepository: TaskRepository = DatabaseTaskRepository(),
    private val supportGateway: SupportGateway = DefaultSupportGateway(),
    private val logGateway: LogGateway = DefaultLogGateway()
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    @Suppress("unused")
    private val databaseReady = BatchDatabase.init()
    private val ioExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "fileloader-io").apply { isDaemon = true }
    }
    private val _state = MutableStateFlow(
        AppUiState(
            loginUserId = AppConfig.getUserId(),
            settings = readSettings()
        )
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 32)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    private val uploadCoordinator = UploadCoordinator(
        batchGateway = batchGateway,
        uploadGateway = uploadGateway,
        taskRepository = taskRepository,
        onTasksChanged = { tasks ->
            _state.update {
                val existingIds = tasks.mapTo(mutableSetOf()) { task -> task.batchId }
                val selected = it.selectedTaskIds.intersect(existingIds)
                it.copy(
                    tasks = tasks,
                    selectedTaskIds = selected,
                    isTaskSelectionMode = it.isTaskSelectionMode && tasks.isNotEmpty()
                )
            }
        },
        onAuthRequired = ::requireLogin,
        onMessage = ::showMessage
    )

    private val batchTracker = BatchTracker(
        gateway = batchGateway,
        onStatus = uploadCoordinator::applyBatchStatus,
        onAuthExpired = ::requireLogin
    ).also { uploadCoordinator.batchTracker = it }

    private val sourceMonitor = SourceMonitor(object : SourceMonitor.Listener {
        override fun onFileReady(file: Path, sourceFolder: Path) {
            uploadCoordinator.enqueue(file, sourceFolder)
        }

        override fun onSourceStarted(sourceFolder: Path, existingFileCount: Int) {
            val source = sourceFolder.toMonitoredSource(existingFileCount)
            _state.update { current ->
                current.copy(
                    monitoredSources = (current.monitoredSources.filterNot { it.path == source.path } + source)
                        .sortedBy { it.name }
                )
            }
            showMessage(if (existingFileCount > 0) "已添加文件夹，发现 $existingFileCount 个文件" else "已开始自动上传此文件夹")
        }

        override fun onSourceStopped(sourceFolder: Path) {
            val normalized = sourceFolder.toAbsolutePath().normalize().toString()
            _state.update { it.copy(monitoredSources = it.monitoredSources.filterNot { source -> source.path == normalized }) }
        }

        override fun onSourceError(sourceFolder: Path, message: String) {
            showMessage(message)
        }
    })

    fun dispatch(action: AppAction) {
        if (closed.get()) return
        when (action) {
            AppAction.Started -> validateInitialSession()
            is AppAction.LoginUserChanged -> _state.update { it.copy(loginUserId = action.value, loginError = "") }
            is AppAction.LoginPasswordChanged -> _state.update { it.copy(loginPassword = action.value, loginError = "") }
            AppAction.Login -> login()
            AppAction.Logout -> logout()
            AppAction.OpenLogin -> _state.update {
                it.copy(showLogin = true, loginError = "", loginUserId = AppConfig.getUserId(), showAccountMenu = false)
            }
            AppAction.CloseLogin -> _state.update { it.copy(showLogin = false) }
            AppAction.ToggleAccountMenu -> _state.update { it.copy(showAccountMenu = !it.showAccountMenu) }
            is AppAction.DragChanged -> _state.update { it.copy(isDragOver = action.value) }
            AppAction.RequestAddSource -> {
                if (ensureLoggedIn()) _effects.tryEmit(UiEffect.ChooseSource)
            }
            is AppAction.SourcesSelected -> addSources(action.files)
            is AppAction.OpenSource -> _effects.tryEmit(UiEffect.OpenFolder(action.path))
            is AppAction.RequestStopSource -> _state.update { it.copy(showStopConfirmationFor = action.source) }
            AppAction.CancelStopSource -> _state.update { it.copy(showStopConfirmationFor = null) }
            AppAction.ConfirmStopSource -> stopConfirmedSource()
            is AppAction.RetryTask -> {
                if (ensureLoggedIn()) uploadCoordinator.retry(action.batchId)
            }
            AppAction.RefreshTasks -> refreshTasks()
            AppAction.EnterTaskSelectionMode -> _state.update {
                it.copy(isTaskSelectionMode = true, selectedTaskIds = emptySet())
            }
            AppAction.ExitTaskSelectionMode -> _state.update {
                it.copy(
                    isTaskSelectionMode = false,
                    selectedTaskIds = emptySet(),
                    showDeleteTaskConfirmation = false
                )
            }
            AppAction.ToggleAllTaskSelection -> _state.update { current ->
                val allIds = current.tasks.mapTo(mutableSetOf()) { it.batchId }
                val next = if (current.selectedTaskIds.size == allIds.size) emptySet() else allIds
                current.copy(selectedTaskIds = next)
            }
            is AppAction.ToggleTaskSelection -> _state.update { current ->
                if (!current.isTaskSelectionMode) return@update current
                val selected = current.selectedTaskIds.toMutableSet()
                if (!selected.add(action.batchId)) selected.remove(action.batchId)
                current.copy(selectedTaskIds = selected)
            }
            AppAction.RequestDeleteSelectedTasks -> {
                if (_state.value.selectedTaskIds.isNotEmpty()) {
                    _state.update { it.copy(showDeleteTaskConfirmation = true) }
                }
            }
            AppAction.CancelDeleteSelectedTasks -> _state.update { it.copy(showDeleteTaskConfirmation = false) }
            AppAction.ConfirmDeleteSelectedTasks -> deleteSelectedTasks()
            AppAction.ClearAllHistory -> clearAllHistory()
            is AppAction.DownloadResult -> _effects.tryEmit(UiEffect.ChooseDownloadDirectory(action.batchId, false))
            is AppAction.DownloadWrongFiles -> _effects.tryEmit(UiEffect.ChooseDownloadDirectory(action.batchId, true))
            is AppAction.ToggleTaskDetails -> _state.update { current ->
                val ids = current.expandedTaskIds.toMutableSet()
                if (!ids.add(action.batchId)) ids.remove(action.batchId)
                current.copy(expandedTaskIds = ids)
            }
            AppAction.OpenSettings -> _state.update {
                it.copy(showSettings = true, showAccountMenu = false, settings = readSettings(), selfCheck = null)
            }
            AppAction.CloseSettings -> _state.update {
                it.copy(showSettings = false, selfCheck = null, showLogin = !it.isLoggedIn, showSystemLog = false)
            }
            is AppAction.OnlyPdfChanged -> updateSettings { copy(onlyPdf = action.value, error = "") }
            is AppAction.ServerUrlChanged -> updateSettings { copy(serverUrl = action.value, error = "") }
            is AppAction.ConnectTimeoutChanged -> updateSettings { copy(connectTimeout = action.value, error = "") }
            is AppAction.ReadTimeoutChanged -> updateSettings { copy(readTimeout = action.value, error = "") }
            AppAction.ToggleAdvancedSettings -> updateSettings { copy(advancedVisible = !advancedVisible) }
            AppAction.SaveSettings -> saveSettings()
            AppAction.RunSelfCheck -> runSelfCheck()
            AppAction.CopySelfCheck -> _state.value.selfCheck?.let {
                _effects.tryEmit(UiEffect.CopyText(it.supportText(), toast = "检查结果已复制"))
            }
            AppAction.OpenSystemLog -> {
                _state.update { it.copy(showSystemLog = true) }
                loadLog()
            }
            AppAction.CloseSystemLog -> _state.update { it.copy(showSystemLog = false) }
            AppAction.RefreshSystemLog -> loadLog()
            AppAction.ClearSystemLog -> clearSystemLog()
            AppAction.OpenLogFolder -> _effects.tryEmit(UiEffect.OpenFolder(logGateway.logDirectory()))
            AppAction.CopySystemLog -> {
                val content = _state.value.systemLogContent
                if (content.isNotBlank()) _effects.tryEmit(UiEffect.CopyText(content, toast = "日志已复制"))
            }
            is AppAction.DownloadDirectorySelected -> download(action)
        }
    }

    private fun validateInitialSession() {
        if (!AppConfig.hasAuthToken()) {
            _state.update { it.copy(isStarting = false, showLogin = true, isUploadPaused = true) }
            return
        }
        _state.update { it.copy(isStarting = true, loginError = "正在确认登录状态") }
        ioExecutor.submit {
            val result = authGateway.validateSession()
            if (result.state == AuthStatusService.State.LOGGED_IN) {
                markLoggedIn()
            } else {
                val message = when (result.state) {
                    AuthStatusService.State.UNAUTHORIZED -> "登录已过期，请重新登录"
                    AuthStatusService.State.NETWORK_ERROR -> "暂时无法连接服务器，可以先检查连接"
                    else -> "无法确认登录状态，请检查连接"
                }
                _state.update {
                    it.copy(
                        isStarting = false,
                        isLoggedIn = false,
                        showLogin = true,
                        loginError = message,
                        isUploadPaused = true
                    )
                }
            }
        }
    }

    private fun login() {
        val userId = _state.value.loginUserId.trim()
        val password = _state.value.loginPassword
        if (userId.isBlank() || password.isBlank()) {
            _state.update { it.copy(loginError = "请输入账号和密码") }
            return
        }
        _state.update { it.copy(isAuthenticating = true, loginError = "") }
        ioExecutor.submit {
            val result = authGateway.login(userId, password)
            if (result.state == AuthStatusService.State.LOGGED_IN) {
                uploadCoordinator.resumeAfterLogin()
                markLoggedIn()
                showMessage("登录成功")
            } else {
                val message = when (result.state) {
                    AuthStatusService.State.UNAUTHORIZED -> "账号或密码不正确"
                    AuthStatusService.State.NETWORK_ERROR -> "无法连接服务器，请检查网络"
                    else -> "登录失败，请稍后重试"
                }
                _state.update { it.copy(isAuthenticating = false, loginError = message) }
            }
        }
    }

    private fun markLoggedIn() {
        batchTracker.setAuthenticated(true)
        _state.update {
            it.copy(
                isStarting = false,
                isLoggedIn = true,
                isAuthenticating = false,
                isUploadPaused = false,
                showLogin = false,
                loginPassword = "",
                loginError = "",
                userName = AppConfig.getAuthName(),
                userId = AppConfig.getAuthUserId()
            )
        }
        // 登录/重新登录后重新拉取服务器处理中的任务状态，避免进度一直停在旧状态
        uploadCoordinator.currentTasks()
            .filter { it.stage == TaskStage.PROCESSING }
            .forEach { batchTracker.track(it.batchId) }
    }

    private fun logout() {
        batchTracker.setAuthenticated(false)
        uploadCoordinator.suspendUploads()
        _state.update { it.copy(showAccountMenu = false, isUploadPaused = true) }
        ioExecutor.submit {
            authGateway.logout()
            _state.update {
                it.copy(isLoggedIn = false, userName = "", userId = "", showLogin = true, loginPassword = "")
            }
        }
    }

    private fun addSources(files: List<File>) {
        if (!ensureLoggedIn()) return
        files.forEach { file ->
            when {
                file.isDirectory -> {
                    if (!sourceMonitor.startMonitoring(file.toPath())) showMessage("此文件夹已添加或无法访问")
                }
                file.isFile && file.extension.equals("zip", ignoreCase = true) -> uploadCoordinator.enqueueArchive(file)
                else -> showMessage("请选择文件夹或 ZIP 压缩包")
            }
        }
    }

    private fun stopConfirmedSource() {
        val source = _state.value.showStopConfirmationFor ?: return
        sourceMonitor.stopMonitoring(Paths.get(source.path))
        _state.update { it.copy(showStopConfirmationFor = null) }
        showMessage("已停止自动上传此文件夹")
    }

    private fun runSelfCheck() {
        _state.update { it.copy(showSettings = true, showLogin = false, isChecking = true, selfCheck = null) }
        ioExecutor.submit {
            val result = supportGateway.runCheck()
            if (result.login.status == CheckStatus.ERROR) batchTracker.setAuthenticated(false)
            _state.update {
                it.copy(
                    isChecking = false,
                    selfCheck = result,
                    isLoggedIn = if (result.login.status == CheckStatus.ERROR) false else it.isLoggedIn
                )
            }
        }
    }

    private fun loadLog() {
        _state.update { it.copy(systemLogLoading = true) }
        ioExecutor.submit {
            val content = logGateway.readTail()
            _state.update { it.copy(systemLogContent = content, systemLogLoading = false) }
        }
    }

    private fun clearSystemLog() {
        _state.update { it.copy(systemLogLoading = true) }
        ioExecutor.submit {
            logGateway.clear()
            val content = logGateway.readTail()
            _state.update { it.copy(systemLogContent = content, systemLogLoading = false) }
            showMessage("日志已清空")
        }
    }

    private fun saveSettings() {
        val draft = _state.value.settings
        val connect = draft.connectTimeout.toIntOrNull()
        val read = draft.readTimeout.toIntOrNull()
        val error = when {
            connect == null || connect <= 0 -> "连接超时必须是大于 0 的数字"
            read == null || read <= 0 -> "读取超时必须是大于 0 的数字"
            else -> null
        }
        if (error != null) {
            updateSettings { copy(error = error) }
            return
        }
        AppConfig.setOnlyUploadPdf(draft.onlyPdf)
        AppConfig.setServerUrl(draft.serverUrl.trim())
        AppConfig.setConnectTimeout(connect!!)
        AppConfig.setReadTimeout(read!!)
        _state.update {
            it.copy(
                showSettings = false,
                selfCheck = null,
                showLogin = !it.isLoggedIn,
                settings = readSettings()
            )
        }
        showMessage("设置已保存")
    }

    private fun download(action: AppAction.DownloadDirectorySelected) {
        ioExecutor.submit {
            val message = if (action.wrongFiles) {
                batchGateway.downloadWrongFiles(action.batchId, action.directory)
            } else {
                batchGateway.downloadResult(action.batchId, action.directory)
            }
            if (message.contains("401") || message.contains("Token", ignoreCase = true)) {
                requireLogin("登录已过期，请重新登录")
            } else {
                showMessage(friendlyDownloadMessage(message))
            }
        }
    }

    private fun refreshTasks() {
        if (!ensureLoggedIn()) return
        val processingIds = _state.value.tasks
            .filter { it.stage == TaskStage.PROCESSING }
            .map { it.batchId }
        if (processingIds.isEmpty()) {
            showMessage("没有正在等待服务器处理的任务")
            return
        }
        batchTracker.refreshNow(processingIds)
        showMessage("正在刷新任务状态")
    }

    private fun deleteSelectedTasks() {
        val selected = _state.value.selectedTaskIds
        val summary = uploadCoordinator.deleteTasks(selected)
        _state.update {
            it.copy(
                showDeleteTaskConfirmation = false,
                selectedTaskIds = it.selectedTaskIds - selected
            )
        }
        val message = when {
            summary.deleted > 0 && summary.protected > 0 ->
                "已删除 ${summary.deleted} 个任务；${summary.protected} 个正在上传的任务已保留"
            summary.deleted > 0 -> "已删除 ${summary.deleted} 个任务记录"
            summary.protected > 0 -> "正在上传的任务不能删除"
            else -> "没有可删除的任务"
        }
        showMessage(message)
    }

    private fun clearAllHistory() {
        val count = uploadCoordinator.clearAllTasks()
        _state.update {
            it.copy(
                isTaskSelectionMode = false,
                selectedTaskIds = emptySet(),
                showDeleteTaskConfirmation = false
            )
        }
        showMessage(if (count > 0) "已清空全部 $count 条本地历史记录" else "本地历史记录为空")
    }

    private fun ensureLoggedIn(): Boolean {
        if (_state.value.isLoggedIn && AppConfig.hasAuthToken()) return true
        requireLogin("请先登录后再继续")
        return false
    }

    private fun requireLogin(message: String) {
        batchTracker.setAuthenticated(false)
        _state.update { current ->
            val alreadyWaiting = current.showLogin && !current.isLoggedIn
            when {
                // 已经在等待登录时保持现状，避免反复重置表单（会导致登录框闪烁、密码被清空）
                alreadyWaiting && current.loginError == message && current.isUploadPaused -> current
                alreadyWaiting -> current.copy(loginError = message, isUploadPaused = true)
                else -> current.copy(
                    isLoggedIn = false,
                    showLogin = true,
                    loginError = message,
                    loginPassword = "",
                    isUploadPaused = true
                )
            }
        }
    }

    private fun showMessage(message: String) {
        if (message.isNotBlank()) _effects.tryEmit(UiEffect.ShowMessage(message))
    }

    private fun updateSettings(transform: SettingsDraft.() -> SettingsDraft) {
        _state.update { it.copy(settings = transform(it.settings)) }
    }

    private fun readSettings() = SettingsDraft(
        onlyPdf = AppConfig.isOnlyUploadPdf(),
        serverUrl = AppConfig.getServerUrl(),
        connectTimeout = AppConfig.getConnectTimeout().toString(),
        readTimeout = AppConfig.getReadTimeout().toString()
    )

    private fun friendlyDownloadMessage(raw: String): String = when {
        raw.startsWith("下载成功") -> "结果已保存到选择的文件夹"
        raw.contains("没有可下载") -> "当前没有可下载的文件"
        raw.contains("401") || raw.contains("Token", ignoreCase = true) -> "登录已过期，请重新登录"
        raw.contains("异常") || raw.contains("失败") -> "下载失败，请稍后重试"
        else -> raw
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sourceMonitor.close()
        uploadCoordinator.close()
        batchTracker.close()
        ioExecutor.shutdownNow()
    }

    private fun Path.toMonitoredSource(existingFileCount: Int) = MonitoredSource(
        path = toAbsolutePath().normalize().toString(),
        name = fileName?.toString() ?: toString(),
        existingFileCount = existingFileCount
    )
}
