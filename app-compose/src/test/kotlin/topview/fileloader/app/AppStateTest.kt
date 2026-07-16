package topview.fileloader.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppStateTest {
    @Test
    fun `tasks are ordered by actionable stage then newest first`() {
        fun task(id: String, stage: TaskStage, time: String) = UploadTask(
            batchId = id,
            sourceLabel = id,
            sourcePath = "",
            createdAt = time,
            stage = stage
        )
        val state = AppUiState(
            tasks = listOf(
                task("ready", TaskStage.READY, "2026-07-14 10:00:00"),
                task("failed", TaskStage.NEEDS_ATTENTION, "2026-07-14 09:00:00"),
                task("active-old", TaskStage.UPLOADING, "2026-07-14 08:00:00"),
                task("active-new", TaskStage.PROCESSING, "2026-07-14 11:00:00")
            )
        )

        assertEquals(listOf("active-new", "active-old", "failed", "ready"), state.sortedTasks.map { it.batchId })
    }

    @Test
    fun `upload and server processing use separate progress sources`() {
        val uploading = UploadTask(
            batchId = "batch",
            sourceLabel = "source",
            sourcePath = "",
            createdAt = "now",
            stage = TaskStage.UPLOADING,
            totalFiles = 2,
            files = listOf(
                UploadFileItem("a", "a", FileStage.SUCCESS, 100),
                UploadFileItem("b", "b", FileStage.UPLOADING, 50)
            )
        )
        val processing = uploading.copy(
            stage = TaskStage.PROCESSING,
            processedFiles = 3,
            serverTotalFiles = 4
        )

        assertEquals(0.75f, uploading.progress)
        assertEquals(0.75f, processing.progress)
        assertEquals(null, uploading.copy(stage = TaskStage.PROCESSING).progress)
    }

    @Test
    fun `failed files do not count as uploaded progress`() {
        val task = UploadTask(
            batchId = "batch",
            sourceLabel = "source",
            sourcePath = "",
            createdAt = "now",
            stage = TaskStage.UPLOADING,
            totalFiles = 2,
            files = listOf(
                UploadFileItem("a", "a", FileStage.SUCCESS, 100),
                UploadFileItem("b", "b", FileStage.FAILED, null)
            )
        )

        assertEquals(0.5f, task.progress)
    }

    @Test
    fun `upload progress is weighted by actual file bytes`() {
        val task = UploadTask(
            batchId = "batch",
            sourceLabel = "source",
            sourcePath = "",
            createdAt = "now",
            stage = TaskStage.UPLOADING,
            totalFiles = 2,
            files = listOf(
                UploadFileItem("large", "large", FileStage.UPLOADING, progress = 50, sizeBytes = 900),
                UploadFileItem("small", "small", FileStage.SUCCESS, progress = 100, sizeBytes = 100)
            )
        )

        assertEquals(0.55f, task.progress)
    }
}
