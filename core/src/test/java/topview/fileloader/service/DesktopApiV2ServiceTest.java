package topview.fileloader.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import topview.fileloader.config.AppConfig;
import topview.fileloader.config.AppPaths;
import topview.fileloader.model.UploadResponse;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DesktopApiV2ServiceTest {
    private static Path configHome;

    @TempDir
    Path tempDir;

    private HttpServer server;

    @BeforeAll
    static void isolateConfigHome(@TempDir Path home) {
        configHome = home.resolve(".fileloader");
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, configHome.toString());
    }

    @AfterAll
    static void restoreConfigHome() {
        System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY);
        AppConfig.loadConfig();
    }

    @Test
    void freshInstallDefaultsToSchoolServer() {
        Path isolatedHome = tempDir.resolve("fresh-config");
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, isolatedHome.toString());
        try {
            AppConfig.loadConfig();
            assertEquals("https://xssc.gdut.edu.cn", AppConfig.getServerUrl());
        } finally {
            // 恢复本测试类的临时配置，避免影响后续用例。
            System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, configHome.toString());
            AppConfig.loadConfig();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        AppConfig.loadConfig();
        AppConfig.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        AppConfig.clearAuthSession();
    }

    @AfterEach
    void tearDown() {
        AppConfig.clearAuthSession();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginPersistsDesktopTokenAndUserInfo() {
        AtomicReference<String> requestPath = new AtomicReference<>();
        server.createContext("/api/desktop/auth/login", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            respond(exchange, 200, """
                    {"code":200,"msg":"操作成功","data":{"loggedIn":true,"userId":"00005625","name":"测试用户","role":1,"token":"abc123","loginTime":"2026-06-17T17:57:14.823"},"success":true}
                    """);
        });

        AuthStatusService.LoginStatusResult result = AuthStatusService.login("00005625", "123456");

        assertEquals(AuthStatusService.State.LOGGED_IN, result.getState());
        assertTrue(requestPath.get().startsWith("/api/desktop/auth/login?"));
        assertTrue(requestPath.get().contains("userId=00005625"));
        assertTrue(requestPath.get().contains("password=123456"));
        assertEquals("abc123", AppConfig.getAuthToken());
        assertEquals("00005625", AppConfig.getAuthUserId());
        assertEquals("测试用户", AppConfig.getAuthName());
        assertEquals(1, AppConfig.getAuthRole());
    }

    @Test
    void studioUrlPreservedAndInsecureSchoolUrlsNormalizeToSchoolHttps() {
        AppConfig.setAuthSession("studio-token", "00005625", "测试用户", 1, "now");
        AppConfig.setServerUrl("http://10.21.76.73:8081/");
        assertEquals("http://10.21.76.73:8081/", AppConfig.getServerUrl());
        assertFalse(AppConfig.hasAuthToken(), "switching servers must discard the previous environment token");

        AppConfig.setServerUrl("http://xssc.gdut.edu.cn");
        assertEquals("https://xssc.gdut.edu.cn", AppConfig.getServerUrl());

        String custom = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        AppConfig.setServerUrl(custom);
        assertEquals(custom, AppConfig.getServerUrl());
    }

    @Test
    void blankServerUrlRestoresSchoolDefault() {
        AppConfig.setAuthSession("local-token", "00005625", "测试用户", 1, "now");

        AppConfig.setServerUrl("   ");

        assertEquals("https://xssc.gdut.edu.cn", AppConfig.getServerUrl());
        assertFalse(AppConfig.hasAuthToken(), "切换回学校服务器后必须清除其他环境的 Token");
    }

    @Test
    void unauthorizedBusinessResponseClearsPersistedToken() {
        AppConfig.setAuthSession("expired-token", "00005625", "测试用户", 1, "now");
        server.createContext("/api/desktop/batch/getBatchId", exchange -> {
            assertEquals("Bearer expired-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 401, "{\"code\":401,\"msg\":\"Token无效或已过期\",\"data\":null,\"success\":false}");
        });

        BatchIdService.BatchIdResult result = BatchIdService.fetchBatchId();

        assertEquals(401, result.getCode());
        assertFalse(AppConfig.hasAuthToken());
    }

    @Test
    void batchServicesUseDesktopPathsAndBearerHeader() {
        AppConfig.setAuthSession("token-1", "00005625", "测试用户", 1, "now");
        AtomicReference<String> statusPath = new AtomicReference<>();
        AtomicReference<String> downloadPath = new AtomicReference<>();
        server.createContext("/api/desktop/batch/batch-001/batchStatus", exchange -> {
            statusPath.set(exchange.getRequestURI().toString());
            assertEquals("Bearer token-1", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"code":200,"msg":"操作成功","data":{"batchId":"batch-001","unprocessedFiles":0,"processingFiles":0,"totalFiles":2}}
                    """);
        });
        server.createContext("/api/desktop/batch/batch-001/batchDownloadWithWrong", exchange -> {
            downloadPath.set(exchange.getRequestURI().toString());
            assertEquals("Bearer token-1", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"wrong.zip\"");
            byte[] bytes = "zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        BatchStatusService.BatchStatusResult status = BatchStatusService.fetchBatchStatus("batch-001");
        String downloadResult = DownloadService.downloadBatch("batch-001", tempDir.toFile(), true);

        assertEquals(200, status.getCode());
        assertTrue(status.isDownloadable());
        assertEquals("/api/desktop/batch/batch-001/batchStatus", statusPath.get());
        assertEquals("/api/desktop/batch/batch-001/batchDownloadWithWrong", downloadPath.get());
        assertTrue(downloadResult.startsWith("下载成功"));
        assertTrue(Files.exists(tempDir.resolve("wrong.zip")));
    }

    @Test
    void downloadCannotEscapeSelectedDirectory() {
        AppConfig.setAuthSession("download-token", "00005625", "测试用户", 1, "now");
        server.createContext("/api/desktop/batch/batch-safe/batchDownload", exchange -> {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"../../outside.zip\"");
            byte[] bytes = "zip".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        String result = DownloadService.downloadBatch("batch-safe", tempDir.toFile(), false);

        assertTrue(result.startsWith("下载成功"));
        assertTrue(Files.exists(tempDir.resolve("outside.zip")));
        assertFalse(Files.exists(tempDir.getParent().resolve("outside.zip")));
    }

    @Test
    void batchRecordsProvidesReadOnlyRemoteSessionValidation() {
        AppConfig.setAuthSession("token-check", "00005625", "测试用户", 1, "now");
        server.createContext("/api/desktop/batch/batchRecords", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer token-check", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"code":200,"msg":"操作成功","data":{"userId":"00005625","batchIds":["b1","b2"]}}
                    """);
        });

        BatchRecordsService.Result records = BatchRecordsService.fetch();
        AuthStatusService.LoginStatusResult session = AuthStatusService.queryLoginStatus();

        assertEquals(BatchRecordsService.State.AVAILABLE, records.getState());
        assertEquals(java.util.List.of("b1", "b2"), records.getBatchIds());
        assertEquals(AuthStatusService.State.LOGGED_IN, session.getState());
    }

    @Test
    void logoutUsesLatestDesktopPostEndpointAndBearerToken() {
        AppConfig.setAuthSession("logout-token", "00005625", "测试用户", 1, "now");
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/api/desktop/auth/logout", exchange -> {
            method.set(exchange.getRequestMethod());
            assertEquals("Bearer logout-token", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"code\":200,\"msg\":\"退出成功\",\"data\":null}");
        });

        AuthStatusService.LogoutResult result = AuthStatusService.logout();

        assertEquals(AuthStatusService.LogoutState.SUCCESS, result.getState());
        assertEquals("POST", method.get());
        assertFalse(AppConfig.hasAuthToken());
    }

    @Test
    void selfCheckSeparatesReachableServerFromExpiredLogin() {
        AppConfig.setAuthSession("expired", "00005625", "测试用户", 1, "now");
        server.createContext("/api/desktop/batch/batchRecords", exchange ->
                respond(exchange, 401, "{\"code\":401,\"msg\":\"Token无效或已过期\",\"data\":null}"));

        SelfCheckService.Result result = SelfCheckService.runCheck();

        assertEquals(SelfCheckService.Status.OK, result.getServer().getStatus());
        assertEquals(SelfCheckService.Status.ERROR, result.getLogin().getStatus());
        assertEquals(SelfCheckService.Status.OK, result.getStorage().getStatus());
        assertFalse(AppConfig.hasAuthToken());
    }

    @Test
    void selfCheckRejectsMalformedBatchRecordsResponse() {
        AppConfig.setAuthSession("token", "00005625", "测试用户", 1, "now");
        server.createContext("/api/desktop/batch/batchRecords", exchange ->
                respond(exchange, 200, "{\"code\":200,\"msg\":\"操作成功\",\"data\":{}}"));

        SelfCheckService.Result result = SelfCheckService.runCheck();

        assertEquals(SelfCheckService.Status.ERROR, result.getServer().getStatus());
        assertEquals(SelfCheckService.Status.WARNING, result.getLogin().getStatus());
    }

    @Test
    void uploadUsesDesktopPathBearerHeaderAndMultipartFields() throws Exception {
        AppConfig.setAuthSession("upload-token", "00005625", "测试用户", 1, "now");
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/desktop/batch/upload", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            assertEquals("Bearer upload-token", exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"code":200,"msg":"操作成功","data":{"fileId":"f1","uploadTime":"2026-06-17T18:00:00","batchId":"batch-001"}}
                    """);
        });
        File file = Files.writeString(tempDir.resolve("report.pdf"), "pdf-content").toFile();

        UploadResponse response = ProgressUploadService.uploadFile(file, "batch-001", ignored -> {
        });

        assertEquals(200, response.getCode());
        assertEquals("/api/desktop/batch/upload?batchId=batch-001", requestPath.get());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        assertTrue(requestBody.get().contains("name=\"file\"; filename=\"report.pdf\""));
        assertTrue(requestBody.get().contains("name=\"fileName\""));
        assertTrue(requestBody.get().contains("report.pdf"));
        assertFalse(requestBody.get().contains("name=\"batchId\""));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
