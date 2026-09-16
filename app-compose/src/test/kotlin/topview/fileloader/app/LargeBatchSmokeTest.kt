package topview.fileloader.app

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import topview.fileloader.config.AppConfig
import topview.fileloader.config.AppPaths
import topview.fileloader.persistence.BatchDatabase
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 大批量冒烟测试：模拟 300+ 篇论文文件的扫描、排队、并发上传、状态维护与 SQLite 持久化全流程。
 */
class LargeBatchSmokeTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private var previousHome: String? = null

    @BeforeEach
    fun setUp() {
        previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
        val isolatedHome = tempDir.resolve("stress-home")
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, isolatedHome.toString())
        AppConfig.loadConfig()
        BatchDatabase.init()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = server.address.port
        server.start()

        AppConfig.setServerUrl("http://127.0.0.1:$serverPort/")
        AppConfig.clearAuthSession()
    }

    @AfterEach
    fun tearDown() {
        AppConfig.clearAuthSession()
        server.stop(0)
        if (previousHome != null) {
            System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, previousHome)
        } else {
            System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
        }
        AppConfig.loadConfig()
    }

    @Test
    fun `smoke test 300 papers upload pipeline under high volume`() {
        val fileCount = 300
        val token = "stress-test-token"
        val batchId = "batch-stress-300"
        AppConfig.setAuthSession(token, "teacher-01", "压力测试审查员", 1, "now")

        val uploadedFileNames = ConcurrentHashMap.newKeySet<String>()
        val uploadCounter = AtomicInteger(0)
        val allDoneLatch = CountDownLatch(fileCount)

        // Mock 服务端接口
        server.createContext("/api/desktop/batch/getBatchId") { exchange ->
            val json = """{"code":200,"msg":"ok","data":{"batchId":"$batchId"},"success":true}"""
            respondJson(exchange, 200, json)
        }

        server.createContext("/api/desktop/batch/upload") { exchange ->
            val raw = exchange.requestBody.readAllBytes()
            val text = String(raw, StandardCharsets.UTF_8)
            // 简单提取文件名
            val regex = Regex("""filename="([^"]+)"""")
            val match = regex.find(text)
            val name = match?.groupValues?.get(1) ?: "unknown-${uploadCounter.get()}"
            uploadedFileNames.add(name)
            val count = uploadCounter.incrementAndGet()
            allDoneLatch.countDown()

            val json = """{"code":200,"msg":"上传成功","data":{"fileId":"fid-$count","batchId":"$batchId"},"success":true}"""
            respondJson(exchange, 200, json)
        }

        server.createContext("/api/desktop/batch/$batchId/batchStatus") { exchange ->
            val done = uploadCounter.get()
            val json = """{"code":200,"msg":"ok","data":{"batchId":"$batchId","processedFiles":$done,"totalFiles":$fileCount},"success":true}"""
            respondJson(exchange, 200, json)
        }

        // 生成 300 个模拟论文文件
        val sourceFolder = tempDir.resolve("papers_300").also { Files.createDirectories(it) }
        val generatedFiles = mutableListOf<Path>()
        val dummyContent = "%PDF-1.4 stress test dummy content \n".repeat(20).toByteArray(StandardCharsets.UTF_8)
        for (i in 1..fileCount) {
            val fileName = String.format("论文_%03d_关于人工智能在审查中的应用.pdf", i)
            val file = sourceFolder.resolve(fileName)
            Files.write(file, dummyContent)
            generatedFiles.add(file)
        }
        assertEquals(fileCount, generatedFiles.size)

        val batchGateway = DefaultBatchGateway()
        val uploadGateway = DefaultUploadGateway()
        val taskRepo = DatabaseTaskRepository()

        val observedStates = Collections.synchronizedList(mutableListOf<List<UploadTask>>())
        val coordinator = UploadCoordinator(
            batchGateway = batchGateway,
            uploadGateway = uploadGateway,
            taskRepository = taskRepo,
            onTasksChanged = { observedStates.add(it) },
            onAuthRequired = {},
            onMessage = {},
            sourceSettleMillis = 100
        )
        val tracker = BatchTracker(
            gateway = batchGateway,
            onStatus = { bId, status -> coordinator.applyBatchStatus(bId, status) },
            onAuthExpired = {}
        )
        coordinator.batchTracker = tracker

        val startTime = System.currentTimeMillis()
        try {
            // 批量 enqueue 300 个文件
            generatedFiles.forEach { file ->
                coordinator.enqueue(file, sourceFolder)
            }

            // 等待全部 300 个文件上传完成（在本地多线程 mock 环境下预期 10 秒内完成）
            val completedInTime = allDoneLatch.await(30, TimeUnit.SECONDS)
            val durationMs = System.currentTimeMillis() - startTime

            assertTrue(completedInTime, "300 个文件应在 30 秒内全部完成上传，实际完成: ${uploadCounter.get()}/$fileCount")
            assertEquals(fileCount, uploadedFileNames.size, "服务端接收到的不重复文件名数量应为 300")
            println("300 篇论文批量上传测试耗时: ${durationMs}ms, 平均单个文件: ${durationMs.toDouble() / fileCount}ms")

            // 检查 Coordinator 状态
            val tasks = coordinator.currentTasks()
            assertEquals(1, tasks.size, "应收敛为一个完整批次")
            val task = tasks.first()
            assertEquals(fileCount, task.totalFiles)
            assertEquals(fileCount, task.completedFiles)
            assertEquals(0, task.failedFiles)
            assertEquals(TaskStage.PROCESSING, task.stage)

            // 验证 SQLite 数据库中的记录
            val dbRecords = BatchDatabase.getAll()
            assertEquals(1, dbRecords.size)
            val dbRecord = dbRecords.first()
            assertEquals(batchId, dbRecord.batchId)
            assertEquals(fileCount, dbRecord.totalFiles)
            assertEquals(fileCount, dbRecord.completedFiles)
            assertEquals(0, dbRecord.failedFiles)

        } finally {
            coordinator.close()
        }
    }

    @Test
    fun `smoke test SourceMonitor scanning 300 existing papers in folder`() {
        val fileCount = 300
        val folder = tempDir.resolve("monitor_300").also { Files.createDirectories(it) }
        val dummyContent = "%PDF-1.4 paper dummy content".toByteArray(StandardCharsets.UTF_8)
        for (i in 1..fileCount) {
            Files.write(folder.resolve(String.format("paper_%03d.pdf", i)), dummyContent)
        }

        val readyFiles = ConcurrentHashMap.newKeySet<Path>()
        val readyLatch = CountDownLatch(fileCount)

        val listener = object : topview.fileloader.monitor.SourceMonitor.Listener {
            override fun onFileReady(file: Path, sourceFolder: Path) {
                readyFiles.add(file)
                readyLatch.countDown()
            }
        }

        val monitor = topview.fileloader.monitor.SourceMonitor(listener)
        val startTime = System.currentTimeMillis()
        try {
            val started = monitor.startMonitoring(folder)
            assertTrue(started)
            val allReady = readyLatch.await(10, TimeUnit.SECONDS)
            val elapsed = System.currentTimeMillis() - startTime
            println("SourceMonitor 扫描 300 篇论文耗时: ${elapsed}ms, 已准备就绪文件数: ${readyFiles.size}/$fileCount")
            assertTrue(allReady, "300 个文件应在 10 秒内全部完成扫描就绪，实际耗时 ${elapsed}ms 就绪 ${readyFiles.size}")
            assertEquals(fileCount, readyFiles.size)
        } finally {
            monitor.close()
        }
    }

    private fun respondJson(exchange: com.sun.net.httpserver.HttpExchange, status: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}
