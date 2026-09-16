package topview.fileloader.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import topview.fileloader.model.UploadResponse
import topview.fileloader.service.BatchIdService
import topview.fileloader.service.BatchStatusService
import java.io.File

class UploadCoordinatorDeleteTest {
    @Test
    fun `deletion removes history but protects active uploads`() {
        val ready = task("ready", TaskStage.READY)
        val uploading = task("uploading", TaskStage.UPLOADING)
        val deleted = mutableSetOf<String>()
        val repository = object : TaskRepository {
            override fun load() = listOf(ready, uploading)
            override fun save(task: UploadTask) = Unit
            override fun delete(batchIds: Set<String>) { deleted += batchIds }
            override fun clearAll() = Unit
        }
        val batchGateway = object : BatchGateway {
            override fun createBatch() = BatchIdService.BatchIdResult(200, "ok", "new")
            override fun status(batchId: String) =
                BatchStatusService.BatchStatusResult(200, "ok", false, -1, -1, -1, 0, 1, 1)
            override fun downloadResult(batchId: String, directory: File) = ""
            override fun downloadWrongFiles(batchId: String, directory: File) = ""
        }
        val coordinator = UploadCoordinator(
            batchGateway,
            object : UploadGateway {
                override fun upload(file: File, batchId: String, onProgress: (Int) -> Unit) = UploadResponse()
            },
            repository,
            {},
            {},
            {}
        )
        coordinator.batchTracker = BatchTracker(batchGateway, { _, _ -> }, {})
        try {
            val result = coordinator.deleteTasks(setOf("ready", "uploading"))

            assertEquals(1, result.deleted)
            assertEquals(1, result.protected)
            assertEquals(setOf("ready"), deleted)
            assertEquals(listOf("uploading"), coordinator.currentTasks().map { it.batchId })
        } finally {
            coordinator.close()
        }
    }

    private fun task(id: String, stage: TaskStage) = UploadTask(
        batchId = id,
        sourceLabel = id,
        sourcePath = "",
        createdAt = "now",
        stage = stage
    )
}
