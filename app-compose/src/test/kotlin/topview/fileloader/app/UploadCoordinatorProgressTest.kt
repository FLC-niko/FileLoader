package topview.fileloader.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import topview.fileloader.config.AppConfig
import topview.fileloader.config.AppPaths
import topview.fileloader.model.UploadResponse
import topview.fileloader.service.BatchIdService
import topview.fileloader.service.BatchStatusService
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class UploadCoordinatorProgressTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `source batch fixes total file count before concurrent uploads begin`() {
        val previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, tempDir.resolve("config").toString())
        AppConfig.loadConfig()
        AppConfig.setAuthSession("test-token", "teacher", "测试用户", 1, "now")

        val source = tempDir.resolve("source").also { it.toFile().mkdirs() }
        val files = (1..3).map { index ->
            source.resolve("$index.pdf").also { it.toFile().writeBytes(ByteArray(32)) }
        }
        val createBatchCalls = AtomicInteger()
        val uploadsStarted = CountDownLatch(files.size)
        val releaseUploads = CountDownLatch(1)
        val observedTotals = Collections.synchronizedList(mutableListOf<Int>())
        val progressHistory = Collections.synchronizedList(mutableListOf<Float>())
        val latestTasks = AtomicReference<List<UploadTask>>(emptyList())

        val batchGateway = object : BatchGateway {
            override fun createBatch(): BatchIdService.BatchIdResult {
                createBatchCalls.incrementAndGet()
                return BatchIdService.BatchIdResult(200, "ok", "batch-1")
            }

            override fun status(batchId: String) =
                BatchStatusService.BatchStatusResult(200, "ok", false, -1, -1, -1, 0, 3, 3)

            override fun downloadResult(batchId: String, directory: File) = ""
            override fun downloadWrongFiles(batchId: String, directory: File) = ""
        }
        val coordinator = UploadCoordinator(
            batchGateway = batchGateway,
            uploadGateway = object : UploadGateway {
                override fun upload(file: File, batchId: String, onProgress: (Int) -> Unit): UploadResponse {
                    observedTotals += latestTasks.get().single().totalFiles
                    onProgress(99)
                    uploadsStarted.countDown()
                    releaseUploads.await(3, TimeUnit.SECONDS)
                    return UploadResponse(200, "ok", true)
                }
            },
            taskRepository = object : TaskRepository {
                override fun load() = emptyList<UploadTask>()
                override fun save(task: UploadTask) = Unit
                override fun delete(batchIds: Set<String>) = Unit
            },
            onTasksChanged = { tasks ->
                latestTasks.set(tasks)
                tasks.singleOrNull()?.progress?.let(progressHistory::add)
            },
            onAuthRequired = {},
            onMessage = {},
            sourceSettleMillis = 50
        )
        coordinator.batchTracker = BatchTracker(batchGateway, { _, _ -> }, {})

        try {
            files.forEach { coordinator.enqueue(it, source) }
            assertTrue(uploadsStarted.await(3, TimeUnit.SECONDS), "all uploads should start after the source batch settles")

            assertEquals(1, createBatchCalls.get())
            assertEquals(listOf(3, 3, 3), observedTotals.sorted())
            assertTrue(
                progressHistory.any { it in 0.32f..0.34f },
                "one file at 99% should be about one third of a three-file task"
            )
        } finally {
            releaseUploads.countDown()
            coordinator.close()
            AppConfig.clearAuthSession()
            if (previousHome == null) System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
            else System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
            AppConfig.loadConfig()
        }
    }
}
