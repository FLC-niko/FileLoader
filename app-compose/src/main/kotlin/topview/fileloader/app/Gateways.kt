package topview.fileloader.app

import topview.fileloader.config.AppConfig
import topview.fileloader.config.LoggingConfig
import topview.fileloader.model.UploadResponse
import topview.fileloader.persistence.BatchDatabase
import topview.fileloader.service.AuthStatusService
import topview.fileloader.service.BatchIdService
import topview.fileloader.service.BatchStatusService
import topview.fileloader.service.DownloadService
import topview.fileloader.service.ProgressUploadService
import topview.fileloader.service.SelfCheckService

import java.io.File

internal interface AuthGateway {
    fun login(userId: String, password: String): AuthStatusService.LoginStatusResult
    fun validateSession(): AuthStatusService.LoginStatusResult
    fun logout(): AuthStatusService.LogoutResult
}

internal interface BatchGateway {
    fun createBatch(): BatchIdService.BatchIdResult
    fun status(batchId: String): BatchStatusService.BatchStatusResult
    fun downloadResult(batchId: String, directory: File): String
    fun downloadWrongFiles(batchId: String, directory: File): String
}

internal interface UploadGateway {
    fun upload(file: File, batchId: String, onProgress: (Int) -> Unit): UploadResponse
}

internal interface TaskRepository {
    fun load(): List<UploadTask>
    fun save(task: UploadTask)
    fun delete(batchIds: Set<String>)
    fun clearAll()
}

internal interface SupportGateway {
    fun runCheck(): SelfCheckResultUi
}

internal interface LogGateway {
    fun readTail(): String
    fun clear()
    fun logDirectory(): String
}

internal class DefaultAuthGateway : AuthGateway {
    override fun login(userId: String, password: String) = AuthStatusService.login(userId, password)
    override fun validateSession() = AuthStatusService.queryLoginStatus()
    override fun logout() = AuthStatusService.logout()
}

internal class DefaultBatchGateway : BatchGateway {
    override fun createBatch() = BatchIdService.fetchBatchId()
    override fun status(batchId: String) = BatchStatusService.fetchBatchStatus(batchId)
    override fun downloadResult(batchId: String, directory: File) =
        BatchStatusService.downloadBatchResult(batchId, directory)
    override fun downloadWrongFiles(batchId: String, directory: File) =
        DownloadService.downloadBatch(batchId, directory, true)
}

internal class DefaultUploadGateway : UploadGateway {
    override fun upload(file: File, batchId: String, onProgress: (Int) -> Unit): UploadResponse =
        ProgressUploadService.uploadFile(file, batchId, onProgress)
}

internal class DatabaseTaskRepository : TaskRepository {
    override fun load(): List<UploadTask> = BatchDatabase.getAll().map { record ->
        val rawStage = runCatching { TaskStage.valueOf(record.stage) }.getOrDefault(TaskStage.PROCESSING)
        // 兜底：程序重启后，历史如果遗留了 PREPARING 或 UPLOADING，内存中并未在执行，标记为 NEEDS_ATTENTION，避免假死或无法删除
        val normalizedStage = when (rawStage) {
            TaskStage.PREPARING, TaskStage.UPLOADING -> TaskStage.NEEDS_ATTENTION
            else -> rawStage
        }
        val normalizedMessage = when {
            rawStage == TaskStage.PREPARING || rawStage == TaskStage.UPLOADING -> "上次上传未完成（程序重启已中断）"
            record.status.isNotBlank() -> record.status
            normalizedStage == TaskStage.READY -> "处理完成，可以下载结果"
            else -> "等待服务器返回处理进度"
        }
        val label = when {
            record.sourceLabel.isNotBlank() -> record.sourceLabel
            record.sourcePath.isNotBlank() -> File(record.sourcePath).name.ifBlank { "历史任务" }
            else -> "历史任务"
        }
        UploadTask(
            batchId = record.batchId,
            sourceLabel = label,
            sourcePath = record.sourcePath,
            createdAt = record.createdAt,
            stage = normalizedStage,
            totalFiles = record.totalFiles,
            completedFiles = record.completedFiles,
            failedFiles = record.failedFiles,
            message = normalizedMessage,
            downloadable = record.stage == TaskStage.READY.name
        )
    }

    override fun save(task: UploadTask) {
        BatchDatabase.upsertTask(
            task.batchId,
            task.message,
            task.sourceLabel,
            task.sourcePath,
            task.stage.name,
            task.totalFiles,
            task.completedFiles,
            task.failedFiles
        )
    }

    override fun delete(batchIds: Set<String>) {
        BatchDatabase.deleteByIds(batchIds)
    }

    override fun clearAll() {
        BatchDatabase.clearAll()
    }
}

internal class DefaultSupportGateway : SupportGateway {
    override fun runCheck(): SelfCheckResultUi {
        val result = SelfCheckService.runCheck()
        fun SelfCheckService.CheckItem.toUi() = SelfCheckItemUi(
            status = when (status) {
                SelfCheckService.Status.OK -> CheckStatus.OK
                SelfCheckService.Status.WARNING -> CheckStatus.WARNING
                SelfCheckService.Status.ERROR -> CheckStatus.ERROR
                null -> CheckStatus.ERROR
            },
            title = title,
            message = message,
            detail = technicalDetail
        )
        return SelfCheckResultUi(result.server.toUi(), result.login.toUi(), result.storage.toUi())
    }
}

internal class DefaultLogGateway : LogGateway {
    override fun readTail(): String = LoggingConfig.readTail(20000)
    override fun clear() = LoggingConfig.clear()
    override fun logDirectory(): String = LoggingConfig.getLogDirectory().toString()
}
