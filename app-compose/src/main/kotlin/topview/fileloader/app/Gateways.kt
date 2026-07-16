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
        UploadTask(
            batchId = record.batchId,
            sourceLabel = record.sourceLabel.ifBlank { "历史任务" },
            sourcePath = record.sourcePath,
            createdAt = record.createdAt,
            stage = runCatching { TaskStage.valueOf(record.stage) }.getOrDefault(TaskStage.PROCESSING),
            totalFiles = record.totalFiles,
            completedFiles = record.completedFiles,
            failedFiles = record.failedFiles,
            message = record.status,
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
