package topview.fileloader.app

import topview.fileloader.config.AppConfig
import topview.fileloader.util.ArchiveExtractor

import java.io.File
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class UploadCoordinator(
    private val batchGateway: BatchGateway,
    private val uploadGateway: UploadGateway,
    private val taskRepository: TaskRepository,
    private val onTasksChanged: (List<UploadTask>) -> Unit,
    private val onAuthRequired: (String) -> Unit,
    private val onMessage: (String) -> Unit,
    private val sourceSettleMillis: Long = 2_000
) : AutoCloseable {
    private data class DiscoveredFile(val file: File, val sourcePath: String, val sourceLabel: String)
    private data class UploadWork(val batchId: String, val filePath: String)
    private class PendingSourceBatch(val sourcePath: String, val sourceLabel: String) {
        val files = LinkedHashMap<String, File>()
        var scheduledFlush: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private val pendingLock = Any()
    private val tasks = LinkedHashMap<String, UploadTask>()
    private val pendingSourceBatches = LinkedHashMap<String, PendingSourceBatch>()
    private val uploadQueue = LinkedBlockingQueue<UploadWork>()
    private val archiveTempDirectories = ConcurrentHashMap<String, Path>()
    private val closed = AtomicBoolean(false)
    private val pausedForAuth = AtomicBoolean(false)
    private val prepareExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "upload-preparer").apply { isDaemon = true }
    }
    private val batchScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "source-batch-settler").apply { isDaemon = true }
    }
    private val archiveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "archive-preparer").apply { isDaemon = true }
    }
    private val uploadExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "upload-worker").apply { isDaemon = true }
    }
    private val cleanupScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "archive-cleanup").apply { isDaemon = true }
    }

    lateinit var batchTracker: BatchTracker

    init {
        taskRepository.load().forEach { tasks[it.batchId] = it }
        emitTasks()
        repeat(3) { uploadExecutor.submit(::uploadLoop) }
    }

    fun enqueue(file: Path, sourceFolder: Path) {
        if (closed.get()) return
        val source = sourceFolder.toAbsolutePath().normalize()
        val discovered = DiscoveredFile(
            file.toAbsolutePath().normalize().toFile(),
            source.toString(),
            source.fileName?.toString() ?: source.toString()
        )
        synchronized(pendingLock) {
            val pending = pendingSourceBatches.getOrPut(discovered.sourcePath) {
                PendingSourceBatch(discovered.sourcePath, discovered.sourceLabel)
            }
            pending.files.putIfAbsent(discovered.file.absolutePath, discovered.file)
            pending.scheduledFlush?.cancel(false)
            pending.scheduledFlush = batchScheduler.schedule(
                { flushPendingSource(discovered.sourcePath) },
                sourceSettleMillis,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun enqueueArchive(archive: File) {
        if (closed.get()) return
        archiveExecutor.submit { prepareArchive(archive) }
    }

    fun retry(batchId: String) {
        val retryPaths = synchronized(lock) {
            tasks[batchId]?.files
                ?.filter { it.stage == FileStage.FAILED && it.retryable }
                ?.map { it.path }
                .orEmpty()
        }
        if (retryPaths.isEmpty()) {
            onMessage("没有可以重试的文件")
            return
        }
        resumeAfterLogin()
        retryPaths.forEach { path ->
            updateFile(batchId, path) { it.copy(stage = FileStage.QUEUED, progress = null, message = "等待重试") }
            uploadQueue.offer(UploadWork(batchId, path))
        }
    }

    fun resumeAfterLogin() {
        pausedForAuth.set(false)
    }

    /** 退出登录时立即暂停上传，等重新登录后再自动继续。 */
    fun suspendUploads() {
        pausedForAuth.set(true)
    }

    fun currentTasks(): List<UploadTask> = synchronized(lock) { tasks.values.toList() }

    data class DeleteSummary(val deleted: Int, val protected: Int)

    fun deleteTasks(batchIds: Set<String>): DeleteSummary {
        if (batchIds.isEmpty()) return DeleteSummary(0, 0)
        val deletable = synchronized(lock) {
            batchIds.mapNotNull(tasks::get)
                .filterNot { it.stage == TaskStage.PREPARING || it.stage == TaskStage.UPLOADING }
                .map { it.batchId }
                .toSet()
        }
        val protected = batchIds.size - deletable.size
        if (deletable.isNotEmpty()) {
            synchronized(lock) {
                deletable.forEach { batchId ->
                    tasks.remove(batchId)
                    batchTracker.stopTracking(batchId)
                    archiveTempDirectories.remove(batchId)?.let(ArchiveExtractor::cleanupTempDir)
                }
                taskRepository.delete(deletable)
                emitTasks()
            }
        }
        return DeleteSummary(deletable.size, protected)
    }

    fun clearAllTasks(): Int {
        return synchronized(lock) {
            val count = tasks.size
            tasks.keys.toList().forEach { batchId ->
                batchTracker.stopTracking(batchId)
                archiveTempDirectories.remove(batchId)?.let(ArchiveExtractor::cleanupTempDir)
            }
            tasks.clear()
            taskRepository.clearAll()
            emitTasks()
            count
        }
    }

    fun applyBatchStatus(batchId: String, status: topview.fileloader.service.BatchStatusService.BatchStatusResult) {
        val isTerminalFailure = status.code == 404 || status.code == 410
                || status.msg.contains("不存在") || status.msg.contains("已失效") || status.msg.contains("已过期")
        if (isTerminalFailure) {
            batchTracker.stopTracking(batchId)
        }
        updateTask(batchId) { task ->
            val localFailure = task.files.any { it.stage == FileStage.FAILED }
            val stage = when {
                isTerminalFailure -> TaskStage.NEEDS_ATTENTION
                localFailure -> TaskStage.NEEDS_ATTENTION
                task.files.any { it.stage == FileStage.UPLOADING || it.stage == FileStage.QUEUED } -> TaskStage.UPLOADING
                status.isDownloadable -> TaskStage.READY
                status.code != 200 && status.code != -1 -> TaskStage.NEEDS_ATTENTION
                else -> TaskStage.PROCESSING
            }
            val displayMessage = when {
                status.isDownloadable -> "处理完成，可以下载结果"
                isTerminalFailure -> "批次在服务端已不存在或已失效"
                status.code != 200 && status.code != -1 -> "查询异常：${status.msg}"
                else -> userFacingStatus(status.msg, stage)
            }
            task.copy(
                stage = stage,
                processedFiles = status.processedFiles,
                serverTotalFiles = status.totalFiles,
                downloadable = status.isDownloadable,
                message = displayMessage
            )
        }
    }

    private fun flushPendingSource(sourcePath: String) {
        if (closed.get()) return
        val pending = synchronized(pendingLock) { pendingSourceBatches.remove(sourcePath) } ?: return
        prepareExecutor.submit {
            try {
                waitForAuthentication()
                prepareFiles(pending)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                onMessage("准备上传时发生错误：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun prepareFiles(pending: PendingSourceBatch) {
        val files = pending.files.values.filter { it.isFile && it.canRead() }
        if (files.isEmpty()) {
            onMessage("没有找到可读取的上传文件")
            return
        }
        val created = batchGateway.createBatch()
        val batchId = created.batchId
        if (created.code != 200 || batchId.isNullOrBlank()) {
            if (created.code == 401) pauseForAuth("登录已过期，请重新登录")
            onMessage(friendlyNetworkMessage(created.msg, "暂时无法开始上传"))
            return
        }
        val items = files.map { file ->
            UploadFileItem(
                path = file.absolutePath,
                name = file.name,
                stage = FileStage.QUEUED,
                message = "等待上传",
                sizeBytes = file.length()
            )
        }
        synchronized(lock) {
            tasks[batchId] = UploadTask(
                batchId = batchId,
                sourceLabel = pending.sourceLabel,
                sourcePath = pending.sourcePath,
                createdAt = nowText(),
                stage = TaskStage.PREPARING,
                totalFiles = items.size,
                message = "已找到 ${items.size} 个文件",
                files = items
            )
            persistAndEmit(tasks.getValue(batchId))
        }
        items.forEach { uploadQueue.offer(UploadWork(batchId, it.path)) }
    }

    private fun prepareArchive(archive: File) {
        if (!archive.isFile || !ArchiveExtractor.isArchiveFile(archive.name)) {
            onMessage("请选择文件夹或 ZIP 压缩包")
            return
        }
        waitForAuthentication()
        val created = batchGateway.createBatch()
        val batchId = created.batchId
        if (created.code != 200 || batchId.isNullOrBlank()) {
            if (created.code == 401) pauseForAuth("登录已过期，请重新登录")
            onMessage(friendlyNetworkMessage(created.msg, "无法创建上传任务"))
            return
        }
        var tempDirectory: Path? = null
        try {
            tempDirectory = ArchiveExtractor.createTempExtractDir(batchId)
            archiveTempDirectories[batchId] = tempDirectory
            val files = ArchiveExtractor.filterValidFiles(ArchiveExtractor.extractZip(archive, tempDirectory))
                .filter { !AppConfig.isOnlyUploadPdf() || it.extension.equals("pdf", ignoreCase = true) }
            if (files.isEmpty()) {
                onMessage("ZIP 中没有可上传的文件")
                return
            }
            synchronized(lock) {
                tasks[batchId] = UploadTask(
                    batchId = batchId,
                    sourceLabel = archive.name,
                    sourcePath = archive.absolutePath,
                    createdAt = nowText(),
                    stage = TaskStage.PREPARING,
                    totalFiles = files.size,
                    message = "已找到 ${files.size} 个文件",
                    files = files.map {
                        UploadFileItem(
                            path = it.absolutePath,
                            name = it.name,
                            stage = FileStage.QUEUED,
                            message = "等待上传",
                            sizeBytes = it.length()
                        )
                    }
                )
                persistAndEmit(tasks.getValue(batchId))
            }
            files.forEach { uploadQueue.offer(UploadWork(batchId, it.absolutePath)) }
        } catch (e: Exception) {
            onMessage("无法打开 ZIP：${e.message ?: "文件可能已损坏"}")
            tempDirectory?.let(ArchiveExtractor::cleanupTempDir)
            archiveTempDirectories.remove(batchId)
        }
    }

    private fun uploadLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted) {
            try {
                val work = uploadQueue.take()
                waitForAuthentication()
                performUpload(work)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                onMessage("上传任务发生错误：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun performUpload(work: UploadWork) {
        val file = File(work.filePath)
        if (!file.isFile || !file.canRead()) {
            markFailed(work, "文件已被移动或删除", retryable = false)
            return
        }
        updateFile(work.batchId, work.filePath) {
            it.copy(stage = FileStage.UPLOADING, progress = 0, message = "正在上传")
        }
        val response = try {
            uploadGateway.upload(file, work.batchId) { progress ->
                updateFileProgress(work.batchId, work.filePath, progress.coerceIn(0, 100))
            }
        } catch (e: Exception) {
            markFailed(work, "网络连接中断，请稍后重试", retryable = true)
            return
        }

        when {
            response.code == 200 -> {
                updateFile(work.batchId, work.filePath) {
                    it.copy(stage = FileStage.SUCCESS, progress = 100, message = "上传成功", retryable = false)
                }
                val stage = synchronized(lock) { tasks[work.batchId]?.stage }
                if (stage == TaskStage.PROCESSING) batchTracker.track(work.batchId)
            }
            response.code == 401 -> {
                markFailed(work, "登录已过期，请重新登录", retryable = true)
                pauseForAuth("登录已过期，请重新登录")
            }
            else -> markFailed(
                work,
                friendlyNetworkMessage(response.message, "上传失败，请重试"),
                retryable = response.code >= 500 || response.message.contains("网络") || response.message.contains("超时")
            )
        }
    }

    private fun updateFileProgress(batchId: String, filePath: String, progress: Int) {
        synchronized(lock) {
            val task = tasks[batchId] ?: return
            val currentFile = task.files.firstOrNull { it.path == filePath } ?: return
            if (currentFile.progress == progress) return
            val files = task.files.map { if (it.path == filePath) it.copy(progress = progress) else it }
            val updated = task.copy(files = files)
            tasks[batchId] = updated
            emitTasks()
        }
    }

    private fun markFailed(work: UploadWork, message: String, retryable: Boolean) {
        updateFile(work.batchId, work.filePath) {
            it.copy(stage = FileStage.FAILED, progress = null, message = message, retryable = retryable)
        }
    }

    private fun updateFile(batchId: String, filePath: String, transform: (UploadFileItem) -> UploadFileItem) {
        synchronized(lock) {
            val task = tasks[batchId] ?: return
            val files = task.files.map { if (it.path == filePath) transform(it) else it }
            val completed = files.count { it.stage == FileStage.SUCCESS }
            val failed = files.count { it.stage == FileStage.FAILED }
            val hasActive = files.any { it.stage == FileStage.QUEUED || it.stage == FileStage.UPLOADING }
            val stage = when {
                hasActive -> TaskStage.UPLOADING
                failed > 0 -> TaskStage.NEEDS_ATTENTION
                files.isNotEmpty() -> TaskStage.PROCESSING
                else -> TaskStage.PREPARING
            }
            val updated = task.copy(
                stage = stage,
                completedFiles = completed,
                failedFiles = failed,
                message = when (stage) {
                    TaskStage.UPLOADING -> "正在上传 ${completed + failed}/${files.size}"
                    TaskStage.NEEDS_ATTENTION -> "$failed 个文件需要处理"
                    TaskStage.PROCESSING -> "文件已上传，服务器正在处理"
                    else -> task.message
                },
                files = files
            )
            tasks[batchId] = updated
            persistAndEmit(updated)
            if (!hasActive && failed == 0) {
                archiveTempDirectories.remove(batchId)?.let { directory ->
                    cleanupScheduler.schedule({ ArchiveExtractor.cleanupTempDir(directory) }, 1, TimeUnit.MINUTES)
                }
            }
        }
    }

    private fun updateTask(batchId: String, transform: (UploadTask) -> UploadTask) {
        synchronized(lock) {
            val task = tasks[batchId] ?: return
            val updated = transform(task)
            tasks[batchId] = updated
            persistAndEmit(updated)
        }
    }

    private fun persistAndEmit(task: UploadTask) {
        taskRepository.save(task)
        emitTasks()
    }

    private fun emitTasks() {
        val snapshot = synchronized(lock) { tasks.values.toList() }
        onTasksChanged(snapshot)
    }

    private fun waitForAuthentication() {
        while (!closed.get() && (pausedForAuth.get() || !AppConfig.hasAuthToken())) {
            if (!AppConfig.hasAuthToken()) pauseForAuth("请先登录后再上传")
            Thread.sleep(300)
        }
    }

    private fun pauseForAuth(message: String) {
        // 只在暂停状态真正发生变化时通知 UI，避免多个等待线程反复弹出登录框导致界面闪烁
        if (pausedForAuth.compareAndSet(false, true)) onAuthRequired(message)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(pendingLock) {
            pendingSourceBatches.values.forEach { it.scheduledFlush?.cancel(false) }
            pendingSourceBatches.clear()
        }
        batchScheduler.shutdownNow()
        prepareExecutor.shutdownNow()
        archiveExecutor.shutdownNow()
        uploadExecutor.shutdownNow()
        cleanupScheduler.shutdownNow()
        archiveTempDirectories.values.forEach(ArchiveExtractor::cleanupTempDir)
        archiveTempDirectories.clear()
        if (::batchTracker.isInitialized) batchTracker.close()
    }

    private fun nowText() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())

    private fun friendlyNetworkMessage(raw: String?, fallback: String): String {
        val message = raw.orEmpty()
        return when {
            message.contains("401") || message.contains("Token", ignoreCase = true) -> "登录已过期，请重新登录"
            message.contains("timeout", ignoreCase = true) || message.contains("超时") -> "服务器响应较慢，请稍后重试"
            message.contains("connection", ignoreCase = true) || message.contains("网络") -> "无法连接服务器，请检查网络"
            message.isBlank() -> fallback
            else -> message
        }
    }

    private fun userFacingStatus(raw: String, stage: TaskStage): String = when (stage) {
        TaskStage.READY -> "处理完成，可以下载结果"
        TaskStage.PROCESSING -> if (raw.contains("异常") || raw.contains("失败")) "服务器处理暂时异常" else "服务器正在处理"
        TaskStage.NEEDS_ATTENTION -> "部分文件上传失败"
        TaskStage.UPLOADING -> "正在上传"
        TaskStage.PREPARING -> "正在准备"
    }
}
