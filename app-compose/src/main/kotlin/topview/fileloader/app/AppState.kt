package topview.fileloader.app

import java.io.File

internal enum class TaskStage {
    PREPARING,
    UPLOADING,
    PROCESSING,
    NEEDS_ATTENTION,
    READY
}

internal enum class FileStage {
    QUEUED,
    UPLOADING,
    SUCCESS,
    FAILED
}

internal data class UploadFileItem(
    val path: String,
    val name: String,
    val stage: FileStage,
    val progress: Int? = null,
    val message: String = "",
    val retryable: Boolean = false,
    val sizeBytes: Long = 0
)

internal data class UploadTask(
    val batchId: String,
    val sourceLabel: String,
    val sourcePath: String,
    val createdAt: String,
    val stage: TaskStage,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val processedFiles: Int = -1,
    val serverTotalFiles: Int = -1,
    val downloadable: Boolean = false,
    val message: String = "",
    val files: List<UploadFileItem> = emptyList()
) {
    /** Progress for the current stage; null means the stage has no reliable numeric progress yet. */
    val progress: Float?
        get() = when (stage) {
            TaskStage.UPLOADING -> if (totalFiles > 0) {
                val totalBytes = files.sumOf { it.sizeBytes.coerceAtLeast(0) }
                if (totalBytes > 0) {
                    val uploadedBytes = files.sumOf { file ->
                        val percent = when (file.stage) {
                            FileStage.SUCCESS -> 100
                            FileStage.UPLOADING -> file.progress?.coerceIn(0, 100) ?: 0
                            FileStage.QUEUED, FileStage.FAILED -> 0
                        }
                        file.sizeBytes.coerceAtLeast(0).toDouble() * percent / 100.0
                    }
                    (uploadedBytes / totalBytes).toFloat().coerceIn(0f, 1f)
                } else {
                    val partial = files.sumOf { file ->
                        when (file.stage) {
                            FileStage.SUCCESS -> 100
                            FileStage.UPLOADING -> file.progress?.coerceIn(0, 100) ?: 0
                            FileStage.QUEUED, FileStage.FAILED -> 0
                        }
                    }
                    (partial.toFloat() / (totalFiles * 100f)).coerceIn(0f, 1f)
                }
            } else null
            TaskStage.PROCESSING -> if (serverTotalFiles > 0 && processedFiles >= 0) {
                (processedFiles.toFloat() / serverTotalFiles).coerceIn(0f, 1f)
            } else null
            TaskStage.PREPARING -> null
            TaskStage.NEEDS_ATTENTION, TaskStage.READY -> null
        }
}

internal data class MonitoredSource(
    val path: String,
    val name: String,
    val existingFileCount: Int = 0
)

internal enum class CheckStatus { OK, WARNING, ERROR }

internal data class SelfCheckItemUi(
    val status: CheckStatus,
    val title: String,
    val message: String,
    val detail: String = ""
)

internal data class SelfCheckResultUi(
    val server: SelfCheckItemUi,
    val login: SelfCheckItemUi,
    val storage: SelfCheckItemUi
) {
    fun supportText(): String = listOf(server, login, storage).joinToString("\n") {
        "${it.title}: ${it.message}${if (it.detail.isBlank()) "" else " (${it.detail})"}"
    }
}

internal data class SettingsDraft(
    val onlyPdf: Boolean = true,
    val serverUrl: String = "",
    val connectTimeout: String = "30",
    val readTimeout: String = "60",
    val advancedVisible: Boolean = false,
    val error: String = ""
)

internal data class AppUiState(
    val isStarting: Boolean = true,
    val isDragOver: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isAuthenticating: Boolean = false,
    val userName: String = "",
    val userId: String = "",
    val loginUserId: String = "",
    val loginPassword: String = "",
    val loginError: String = "",
    val showLogin: Boolean = false,
    val showAccountMenu: Boolean = false,
    val showSettings: Boolean = false,
    val showStopConfirmationFor: MonitoredSource? = null,
    val settings: SettingsDraft = SettingsDraft(),
    val monitoredSources: List<MonitoredSource> = emptyList(),
    val tasks: List<UploadTask> = emptyList(),
    val expandedTaskIds: Set<String> = emptySet(),
    val isTaskSelectionMode: Boolean = false,
    val selectedTaskIds: Set<String> = emptySet(),
    val showDeleteTaskConfirmation: Boolean = false,
    val isChecking: Boolean = false,
    val selfCheck: SelfCheckResultUi? = null,
    val showSystemLog: Boolean = false,
    val systemLogContent: String = "",
    val systemLogLoading: Boolean = false
) {
    val sortedTasks: List<UploadTask>
        get() = tasks.sortedWith(
            compareBy<UploadTask> {
                when (it.stage) {
                    TaskStage.UPLOADING, TaskStage.PREPARING, TaskStage.PROCESSING -> 0
                    TaskStage.NEEDS_ATTENTION -> 1
                    TaskStage.READY -> 2
                }
            }.thenByDescending { it.createdAt }
        )
}

internal sealed interface AppAction {
    data object Started : AppAction
    data class LoginUserChanged(val value: String) : AppAction
    data class LoginPasswordChanged(val value: String) : AppAction
    data object Login : AppAction
    data object Logout : AppAction
    data object OpenLogin : AppAction
    data object CloseLogin : AppAction
    data object ToggleAccountMenu : AppAction
    data class DragChanged(val value: Boolean) : AppAction
    data object RequestAddSource : AppAction
    data class SourcesSelected(val files: List<File>) : AppAction
    data class OpenSource(val path: String) : AppAction
    data class RequestStopSource(val source: MonitoredSource) : AppAction
    data object CancelStopSource : AppAction
    data object ConfirmStopSource : AppAction
    data class RetryTask(val batchId: String) : AppAction
    data object RefreshTasks : AppAction
    data object EnterTaskSelectionMode : AppAction
    data object ExitTaskSelectionMode : AppAction
    data object ToggleAllTaskSelection : AppAction
    data class ToggleTaskSelection(val batchId: String) : AppAction
    data object RequestDeleteSelectedTasks : AppAction
    data object CancelDeleteSelectedTasks : AppAction
    data object ConfirmDeleteSelectedTasks : AppAction
    data class DownloadResult(val batchId: String) : AppAction
    data class DownloadWrongFiles(val batchId: String) : AppAction
    data class ToggleTaskDetails(val batchId: String) : AppAction
    data object OpenSettings : AppAction
    data object CloseSettings : AppAction
    data class OnlyPdfChanged(val value: Boolean) : AppAction
    data class ServerUrlChanged(val value: String) : AppAction
    data class ConnectTimeoutChanged(val value: String) : AppAction
    data class ReadTimeoutChanged(val value: String) : AppAction
    data object ToggleAdvancedSettings : AppAction
    data object SaveSettings : AppAction
    data object RunSelfCheck : AppAction
    data object CopySelfCheck : AppAction
    data object OpenSystemLog : AppAction
    data object CloseSystemLog : AppAction
    data object RefreshSystemLog : AppAction
    data object ClearSystemLog : AppAction
    data object OpenLogFolder : AppAction
    data object CopySystemLog : AppAction
    data class DownloadDirectorySelected(val batchId: String, val wrongFiles: Boolean, val directory: File) : AppAction
}

internal sealed interface UiEffect {
    data object ChooseSource : UiEffect
    data class ChooseDownloadDirectory(val batchId: String, val wrongFiles: Boolean) : UiEffect
    data class OpenFolder(val path: String) : UiEffect
    data class ShowMessage(val message: String) : UiEffect
    data class CopyText(val text: String, val toast: String = "已复制") : UiEffect
}
