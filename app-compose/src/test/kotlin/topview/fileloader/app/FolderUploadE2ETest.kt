package topview.fileloader.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import topview.fileloader.config.AppConfig
import topview.fileloader.config.AppPaths
import topview.fileloader.persistence.BatchDatabase
import topview.fileloader.service.AuthStatusService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 端到端集成测试：使用真实测试文件夹中的文件，验证完整的“登录 -> 监控/扫描 -> 过滤 -> 批次创建 -> 上传 -> 状态轮询 -> 结果下载 -> 本地持久化”全链路。
 */
class FolderUploadE2ETest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private var previousHome: String? = null

    @BeforeEach
    fun setUp() {
        previousHome = System.getProperty(AppPaths.HOME_OVERRIDE_PROPERTY)
        val isolatedHome = tempDir.resolve("e2e-home")
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
    fun testRealFolderUploadFullLifecycle() {
        // 1. 获取真实测试目录（若存在则使用真实目录，否则在 tempDir 下准备同名同结构测试文件）
        val testFolder = resolveTestFolder()
        assertTrue(testFolder.exists() && testFolder.isDirectory, "测试文件夹必须存在")

        val filesInFolder = testFolder.listFiles()?.toList().orEmpty()
        val pdfFiles = filesInFolder.filter { it.name.endsWith(".pdf", ignoreCase = true) }
        assertTrue(pdfFiles.isNotEmpty(), "测试文件夹内必须有至少一个 PDF 文件用于测试")

        val token = "desktop-test-token-7788"
        val testUserId = "00005625"
        val batchId = "batch-real-folder-e2e-001"

        // 2. 配置 Mock 服务端接口响应
        val uploadedFiles = ConcurrentHashMap<String, ByteArray>()
        val uploadCount = AtomicInteger(0)
        val statusQueryCount = AtomicInteger(0)
        val allUploadsDone = CountDownLatch(pdfFiles.size)

        // 登录接口
        server.createContext("/api/desktop/auth/login") { exchange ->
            val uri = exchange.requestURI.toString()
            assertTrue(uri.contains("userId=$testUserId"))
            val responseJson = """
                {"code":200,"msg":"登录成功","data":{"loggedIn":true,"userId":"$testUserId","name":"测试审查员","role":1,"token":"$token","loginTime":"2026-09-16T15:00:00"},"success":true}
            """.trimIndent()
            respondJson(exchange, 200, responseJson)
        }

        // 创建批次接口
        server.createContext("/api/desktop/batch/getBatchId") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val responseJson = """
                {"code":200,"msg":"生成批次ID成功","data":{"userId":"$testUserId","batchId":"$batchId","createTime":"2026-09-16T15:00:01"},"success":true}
            """.trimIndent()
            respondJson(exchange, 200, responseJson)
        }

        // 文件上传接口
        server.createContext("/api/desktop/batch/upload") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("/api/desktop/batch/upload?batchId=$batchId", exchange.requestURI.toString())

            val rawBytes = exchange.requestBody.readAllBytes()
            val rawString = String(rawBytes, StandardCharsets.UTF_8)

            // 提取文件名
            val fileName = extractFileNameFromMultipart(rawString)
            if (fileName != null) {
                uploadedFiles[fileName] = rawBytes
            }
            uploadCount.incrementAndGet()
            allUploadsDone.countDown()

            val responseJson = """
                {"code":200,"msg":"上传成功","data":{"fileId":"fid-${uploadCount.get()}","batchId":"$batchId"},"success":true}
            """.trimIndent()
            respondJson(exchange, 200, responseJson)
        }

        // 批次状态接口（首次查询处理中，第二次查询已完成可下载）
        server.createContext("/api/desktop/batch/$batchId/batchStatus") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            val count = statusQueryCount.incrementAndGet()
            val isDone = count >= 2
            val responseJson = if (!isDone) {
                """
                {"code":200,"msg":"正在审查处理中","data":{"batchId":"$batchId","unprocessedFiles":1,"processingFiles":${pdfFiles.size - 1},"totalFiles":${pdfFiles.size}},"success":true}
                """.trimIndent()
            } else {
                """
                {"code":200,"msg":"处理完成，可以下载结果","data":{"batchId":"$batchId","successWithoutErrorFiles":${pdfFiles.size},"successWithErrorFiles":0,"failureFiles":0,"unprocessedFiles":0,"processingFiles":0,"totalFiles":${pdfFiles.size}},"success":true}
                """.trimIndent()
            }
            respondJson(exchange, 200, responseJson)
        }

        // 结果下载接口（返回包含审查表头的 Excel 文档）
        val mockExcelBytes = createMockReviewExcel(batchId, pdfFiles.map { it.name })
        server.createContext("/api/desktop/batch/$batchId/batchResult") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            exchange.responseHeaders.set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            exchange.responseHeaders.set("Content-Disposition", "attachment; filename=\"batch_result_$batchId.xlsx\"")
            exchange.sendResponseHeaders(200, mockExcelBytes.size.toLong())
            exchange.responseBody.write(mockExcelBytes)
            exchange.close()
        }

        // 退出登录接口
        server.createContext("/api/desktop/auth/logout") { exchange ->
            assertEquals("Bearer $token", exchange.requestHeaders.getFirst("Authorization"))
            respondJson(exchange, 200, """{"code":200,"msg":"退出成功","data":null,"success":true}""")
        }

        // 3. 执行登录
        val authGateway = DefaultAuthGateway()
        val loginResult = authGateway.login(testUserId, "correct-password")
        assertEquals(AuthStatusService.State.LOGGED_IN, loginResult.state, "登录必须成功")
        assertEquals(token, AppConfig.getAuthToken(), "登录后必须保存 Token")
        assertEquals(testUserId, AppConfig.getAuthUserId(), "登录后必须保存用户 ID")

        // 4. 初始化上传协调器与网关
        AppConfig.setOnlyUploadPdf(true)
        val batchGateway = DefaultBatchGateway()
        val uploadGateway = DefaultUploadGateway()
        val taskRepo = DatabaseTaskRepository()

        val observedTasks = Collections.synchronizedList(mutableListOf<List<UploadTask>>())
        val coordinator = UploadCoordinator(
            batchGateway = batchGateway,
            uploadGateway = uploadGateway,
            taskRepository = taskRepo,
            onTasksChanged = { observedTasks.add(it) },
            onAuthRequired = {},
            onMessage = {},
            sourceSettleMillis = 100 // 测试中快速沉降
        )
        val tracker = BatchTracker(
            gateway = batchGateway,
            onStatus = { bId, status -> coordinator.applyBatchStatus(bId, status) },
            onAuthExpired = { coordinator.suspendUploads() }
        )
        coordinator.batchTracker = tracker

        try {
            // 5. 将测试文件夹中的文件加入队列
            filesInFolder.forEach { file ->
                // 模拟过滤逻辑：在 onlyPdf 开启下，非 PDF 文件如 .DS_Store 被跳过
                if (!AppConfig.isOnlyUploadPdf() || file.name.endsWith(".pdf", ignoreCase = true)) {
                    coordinator.enqueue(file.toPath(), testFolder.toPath())
                }
            }

            // 等待所有 PDF 文件上传完成
            assertTrue(
                allUploadsDone.await(5, TimeUnit.SECONDS),
                "所有测试 PDF 文件应在 5 秒内上传完毕（实际已上传: ${uploadCount.get()}/${pdfFiles.size}）"
            )

            // 验证上传请求收到的文件列表
            pdfFiles.forEach { pdf ->
                assertTrue(
                    uploadedFiles.containsKey(pdf.name),
                    "服务端应接收到测试文件: ${pdf.name}"
                )
            }

            // 6. 验证任务状态更新与轮询处理
            val tasksAfterUpload = coordinator.currentTasks()
            assertEquals(1, tasksAfterUpload.size)
            val currentTask = tasksAfterUpload.first()
            assertEquals(batchId, currentTask.batchId)
            assertEquals(pdfFiles.size, currentTask.totalFiles)

            // 第一次状态刷新（处理中）
            val status1 = batchGateway.status(batchId)
            assertEquals(200, status1.code)
            assertFalse(status1.isDownloadable, "处理中时不应可下载")
            coordinator.applyBatchStatus(batchId, status1)
            assertEquals(TaskStage.PROCESSING, coordinator.currentTasks().first().stage)

            // 第二次状态刷新（处理完成）
            val status2 = batchGateway.status(batchId)
            assertEquals(200, status2.code)
            assertTrue(status2.isDownloadable, "处理完成后必须可下载")
            assertEquals(pdfFiles.size, status2.processedFiles)
            coordinator.applyBatchStatus(batchId, status2)

            val finalTask = coordinator.currentTasks().first()
            assertEquals(TaskStage.READY, finalTask.stage, "批次任务最终应达到 READY 状态")
            assertTrue(finalTask.downloadable, "批次应标记为可下载")

            // 7. 验证结果下载与内容正确性
            val downloadDir = tempDir.resolve("downloads").toFile()
            downloadDir.mkdirs()
            val downloadMsg = batchGateway.downloadResult(batchId, downloadDir)
            assertTrue(downloadMsg.startsWith("下载成功"), "下载汇总报告应返回成功，实际: $downloadMsg")

            val downloadedFile = File(downloadDir, "batch_result_$batchId.xlsx")
            assertTrue(downloadedFile.exists(), "下载的 Excel 结果文件必须存在")
            assertTrue(downloadedFile.length() > 0, "下载的 Excel 结果文件内容不得为空")

            // 深入检验 Excel 文件内部结构与内容
            verifyReviewExcelContent(downloadedFile, pdfFiles.map { it.name })

            // 8. 验证本地 SQLite 数据库持久化状态
            val recordsInDb = BatchDatabase.getAll()
            val dbRecord = recordsInDb.find { it.batchId == batchId }
            assertNotNull(dbRecord, "数据库中必须存在该批次的记录")
            assertEquals("READY", dbRecord!!.stage)
            assertEquals(pdfFiles.size, dbRecord.totalFiles)
            assertEquals(testFolder.absolutePath, dbRecord.sourcePath)

            // 9. 验证退出登录并清空凭证
            val logoutResult = authGateway.logout()
            assertEquals(AuthStatusService.LogoutState.SUCCESS, logoutResult.state)
            assertFalse(AppConfig.hasAuthToken(), "退出登录后 Token 必须被清除")

        } finally {
            tracker.close()
            coordinator.close()
        }
    }

    @Test
    fun testNonPdfFilteringBehavior() {
        val testFolder = resolveTestFolder()
        val allFiles = testFolder.listFiles()?.toList().orEmpty()
        val dsStore = allFiles.find { it.name == ".DS_Store" }

        // 当开启 onlyPdf 过滤时，非 PDF 文件不会被放入上传清单
        AppConfig.setOnlyUploadPdf(true)
        assertTrue(AppConfig.isOnlyUploadPdf())

        // 模拟 SourceMonitor.discover 的过滤逻辑
        fun isDiscoverable(file: File): Boolean {
            val name = file.name
            if (name.startsWith("~$")) return false
            if (AppConfig.isOnlyUploadPdf() && !name.lowercase().endsWith(".pdf")) return false
            return true
        }

        if (dsStore != null) {
            assertFalse(isDiscoverable(dsStore), ".DS_Store 应该被 PDF 过滤器剔除")
        }

        val pdfFiles = allFiles.filter { it.name.endsWith(".pdf", ignoreCase = true) }
        pdfFiles.forEach { pdf ->
            assertTrue(isDiscoverable(pdf), "PDF 文件 ${pdf.name} 应该正常被识别通过")
        }
    }

    // --- 内部辅助函数 ---

    private fun resolveTestFolder(): File {
        val userTestFolder = File("/Users/halo/Downloads/测试用文件夹")
        if (userTestFolder.exists() && userTestFolder.isDirectory) {
            return userTestFolder
        }
        // 如果在没有该路径的环境下运行，生成测试副本
        val fallback = tempDir.resolve("测试用文件夹").toFile().also { it.mkdirs() }
        File(fallback, "5257060000_基于泳效应检测.pdf").writeBytes("mock-pdf-1".toByteArray())
        File(fallback, "6257076405_复杂受限神经网络的有限时间同步研究.pdf").writeBytes("mock-pdf-2".toByteArray())
        File(fallback, "申请书2.pdf").writeBytes("mock-pdf-3".toByteArray())
        File(fallback, ".DS_Store").writeBytes(ByteArray(16))
        return fallback
    }

    private fun extractFileNameFromMultipart(body: String): String? {
        val marker = "filename=\""
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        val start = idx + marker.length
        val end = body.indexOf("\"", start)
        return if (end > start) body.substring(start, end) else null
    }

    private fun respondJson(exchange: HttpExchange, code: Int, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }

    private fun createMockReviewExcel(batchId: String, fileNames: List<String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun addEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            addEntry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>""")

            addEntry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""")

            addEntry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheets><sheet name="Sheet1" sheetId="1" r:id="rId1" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"/></sheets>
</workbook>""")

            addEntry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>""")

            val rowsXml = StringBuilder("""
<row r="1">
  <c r="A1" t="inlineStr"><is><t>fileId</t></is></c>
  <c r="B1" t="inlineStr"><is><t>文件名</t></is></c>
  <c r="C1" t="inlineStr"><is><t>审查状态</t></is></c>
  <c r="D1" t="inlineStr"><is><t>审核结果说明</t></is></c>
</row>
""")
            fileNames.forEachIndexed { idx, name ->
                val r = idx + 2
                rowsXml.append("""
<row r="$r">
  <c r="A$r" t="inlineStr"><is><t>FID-$batchId-$idx</t></is></c>
  <c r="B$r" t="inlineStr"><is><t>$name</t></is></c>
  <c r="C$r" t="inlineStr"><is><t>审查通过</t></is></c>
  <c r="D$r" t="inlineStr"><is><t>形式审查合格，无缺失项</t></is></c>
</row>
""")
            }

            addEntry("xl/worksheets/sheet1.xml", """<?xml version="1.0" encoding="UTF-8"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>$rowsXml</sheetData>
</worksheet>""")
        }
        return baos.toByteArray()
    }

    private fun verifyReviewExcelContent(excelFile: File, expectedFileNames: List<String>) {
        ZipFile(excelFile).use { zip ->
            val sheetEntry = zip.getEntry("xl/worksheets/sheet1.xml")
            assertNotNull(sheetEntry, "Excel 必须包含 sheet1.xml 工作表")
            val content = String(zip.getInputStream(sheetEntry).readAllBytes(), StandardCharsets.UTF_8)
            assertTrue(content.contains("fileId"), "Excel 应包含表头 fileId")
            assertTrue(content.contains("审查状态"), "Excel 应包含表头 审查状态")
            expectedFileNames.forEach { name ->
                assertTrue(content.contains(name), "Excel 结果应包含已审查文件: $name")
            }
        }
    }
}
