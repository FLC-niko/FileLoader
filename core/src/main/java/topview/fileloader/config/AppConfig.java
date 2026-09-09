package topview.fileloader.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 应用程序配置类
 * 负责读取、保存和管理应用程序的各项配置参数，如服务器地址、超时时间等
 */
public class AppConfig {
    // 日志记录器
    private static final Logger logger = Logger.getLogger(AppConfig.class.getName());
    // 配置文件路径（迁移到用户目录，避免污染仓库）
    private static final Path LEGACY_CONFIG_FILE = Paths.get("fileLoader.properties");
    // 配置属性集合
    private static final Properties properties = new Properties();

    // 默认配置常量
    private static final String DEFAULT_SERVER_URL = "https://xssc.gdut.edu.cn";
    private static final String SCHOOL_SERVER_HOST = "xssc.gdut.edu.cn";
    private static final String LEGACY_STUDIO_HOST = "10.21.76.73";
    private static final int DEFAULT_CONNECT_TIMEOUT = 30;
    private static final int DEFAULT_READ_TIMEOUT = 60;
    private static final int DEFAULT_WRITE_TIMEOUT = 60;
    private static final String DEFAULT_USER_ID = "20000381";

    // 默认文件过滤配置：false 表示不过滤（上传所有文件），true 表示只上传 PDF
    private static final boolean DEFAULT_ONLY_UPLOAD_PDF = true;

    // 默认用户Id
    public static String userId = DEFAULT_USER_ID;
    // 旧版加密密码字段，仅用于配置兼容，不再参与 v2 桌面认证
    public static String encryptedPassword = "";
    // v2 桌面端 Token 认证信息
    private static String authToken = "";
    private static String authUserId = "";
    private static String authName = "";
    private static int authRole = 0;
    private static String authLoginTime = "";

    static {
        // 类加载时自动读取配置
        loadConfig();
    }

    /**
     * 从属性文件中加载配置信息
     */
    public static void loadConfig() {
        Path configFile = configFile();
        ensureConfigDirectory();
        migrateLegacyConfigIfNeeded();
        properties.clear();

        try (InputStream fis = Files.newInputStream(configFile)) {
            properties.load(fis);
            logger.info("Configuration loaded from " + configFile);
        } catch (IOException e) {
            logger.warning("Could not load config file, using defaults: " + e.getMessage());
            setDefaults();
        }

        userId = properties.getProperty("user.id", DEFAULT_USER_ID);
        encryptedPassword = "";
        authToken = properties.getProperty("auth.token", "");
        authUserId = properties.getProperty("auth.userId", "");
        authName = properties.getProperty("auth.name", "");
        authRole = parseInt(properties.getProperty("auth.role"), 0);
        authLoginTime = properties.getProperty("auth.loginTime", "");
        if ((userId == null || userId.isBlank()) && authUserId != null && !authUserId.isBlank()) {
            userId = authUserId;
        }
        if (migrateServerUrlIfNeeded()) {
            saveConfig();
        }
    }

    /**
     * 将当前配置信息保存到属性文件中
     */
    public static void saveConfig() {
        Path configFile = configFile();
        ensureConfigDirectory();
        properties.setProperty("user.id", userId);
        properties.setProperty("user.encryptedPassword", "");
        properties.setProperty("auth.token", authToken);
        properties.setProperty("auth.userId", authUserId);
        properties.setProperty("auth.name", authName);
        properties.setProperty("auth.role", String.valueOf(authRole));
        properties.setProperty("auth.loginTime", authLoginTime);
        try (OutputStream fos = Files.newOutputStream(configFile)) {
            properties.store(fos, "FileLoader Configuration");
            logger.info("Configuration saved to " + configFile);
        } catch (IOException e) {
            logger.severe("Could not save config file: " + e.getMessage());
        }
    }

    /**
     * 设置配置的默认值
     */
    private static void setDefaults() {
        properties.setProperty("server.url", DEFAULT_SERVER_URL);
        properties.setProperty("timeout.connect", String.valueOf(DEFAULT_CONNECT_TIMEOUT));
        properties.setProperty("timeout.read", String.valueOf(DEFAULT_READ_TIMEOUT));
        properties.setProperty("timeout.write", String.valueOf(DEFAULT_WRITE_TIMEOUT));
        properties.setProperty("filter.onlyPdf", String.valueOf(DEFAULT_ONLY_UPLOAD_PDF));
        properties.setProperty("user.id", DEFAULT_USER_ID);
        properties.setProperty("user.encryptedPassword", "");
        properties.setProperty("auth.token", "");
        properties.setProperty("auth.userId", "");
        properties.setProperty("auth.name", "");
        properties.setProperty("auth.role", "0");
        properties.setProperty("auth.loginTime", "");
        userId = DEFAULT_USER_ID;
        encryptedPassword = "";
        authToken = "";
        authUserId = "";
        authName = "";
        authRole = 0;
        authLoginTime = "";
    }

    // --- Getter 方法，如果配置不存在则返回默认值 ---

    /**
     * 获取服务器URL
     */
    public static String getServerUrl() {
        return properties.getProperty("server.url", DEFAULT_SERVER_URL);
    }

    /**
     * 获取连接超时时间
     */
    public static int getConnectTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.connect", String.valueOf(DEFAULT_CONNECT_TIMEOUT)));
    }

    /**
     * 获取读取超时时间
     */
    public static int getReadTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.read", String.valueOf(DEFAULT_READ_TIMEOUT)));
    }

    /**
     * 获取写入超时时间
     */
    public static int getWriteTimeout() {
        return Integer.parseInt(properties.getProperty("timeout.write", String.valueOf(DEFAULT_WRITE_TIMEOUT)));
    }

    /**
     * 获取是否只上传 PDF 文件的配置
     */
    public static boolean isOnlyUploadPdf() {
        return Boolean.parseBoolean(properties.getProperty("filter.onlyPdf", String.valueOf(DEFAULT_ONLY_UPLOAD_PDF)));
    }

    /**
     * 设置并保存“仅上传 PDF”开关
     */
    public static void setOnlyUploadPdf(boolean value) {
        properties.setProperty("filter.onlyPdf", String.valueOf(value));
        saveConfig();
    }

    // --- Setter 方法，修改后自动保存 ---

    /**
     * 设置并保存服务器URL
     */
    public static void setServerUrl(String url) {
        String normalized = normalizeServerUrl(url);
        String previous = properties.getProperty("server.url", DEFAULT_SERVER_URL);
        if (!normalized.equals(previous)) {
            clearAuthFields();
        }
        properties.setProperty("server.url", normalized);
        saveConfig();
    }

    public static void setConnectTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.connect", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static void setReadTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.read", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static void setWriteTimeout(int timeoutSeconds) {
        properties.setProperty("timeout.write", String.valueOf(timeoutSeconds));
        saveConfig();
    }

    public static String getUserId() {
        return userId;
    }

    public static void setUserId(String value) {
        userId = (value == null || value.isBlank()) ? DEFAULT_USER_ID : value.trim();
        properties.setProperty("user.id", userId);
        saveConfig();
    }

    @Deprecated
    public static String getEncryptedPassword() {
        return encryptedPassword;
    }

    @Deprecated
    public static void setEncryptedPassword(String value) {
        encryptedPassword = "";
        properties.setProperty("user.encryptedPassword", "");
        saveConfig();
    }

    @Deprecated
    public static void clearEncryptedPassword() {
        encryptedPassword = "";
        properties.setProperty("user.encryptedPassword", "");
        saveConfig();
    }

    public static synchronized boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }

    public static synchronized String getAuthToken() {
        return authToken == null ? "" : authToken;
    }

    public static synchronized String getAuthUserId() {
        return authUserId == null ? "" : authUserId;
    }

    public static synchronized String getAuthName() {
        return authName == null ? "" : authName;
    }

    public static synchronized int getAuthRole() {
        return authRole;
    }

    public static synchronized String getAuthLoginTime() {
        return authLoginTime == null ? "" : authLoginTime;
    }

    public static synchronized void setAuthSession(
            String token,
            String authenticatedUserId,
            String name,
            int role,
            String loginTime) {
        authToken = token == null ? "" : token.trim();
        authUserId = authenticatedUserId == null ? "" : authenticatedUserId.trim();
        authName = name == null ? "" : name.trim();
        authRole = role;
        authLoginTime = loginTime == null ? "" : loginTime.trim();
        if (!authUserId.isBlank()) {
            userId = authUserId;
            properties.setProperty("user.id", userId);
        }
        encryptedPassword = "";
        saveConfig();
    }

    public static synchronized void clearAuthSession() {
        clearAuthFields();
        saveConfig();
    }

    private static void clearAuthFields() {
        authToken = "";
        authUserId = "";
        authName = "";
        authRole = 0;
        authLoginTime = "";
        encryptedPassword = "";
    }

    private static void ensureConfigDirectory() {
        Path appHomeDir = AppPaths.homeDirectory();
        try {
            Files.createDirectories(appHomeDir);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not create app home directory: " + appHomeDir, e);
        }
    }

    private static void migrateLegacyConfigIfNeeded() {
        Path configFile = configFile();
        if (!Files.exists(LEGACY_CONFIG_FILE) || Files.exists(configFile)) {
            return;
        }
        try {
            Files.copy(LEGACY_CONFIG_FILE, configFile, StandardCopyOption.COPY_ATTRIBUTES);
            logger.info("Migrated legacy config to " + configFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not migrate legacy config file", e);
        }
    }

    private static Path configFile() {
        return AppPaths.homeDirectory().resolve("fileLoader.properties");
    }

    private static int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean migrateServerUrlIfNeeded() {
        String configured = properties.getProperty("server.url", DEFAULT_SERVER_URL);
        String normalized = normalizeServerUrl(configured);
        if (normalized.equals(configured)) {
            return false;
        }
        clearAuthFields();
        properties.setProperty("server.url", normalized);
        logger.info("Migrated server URL to " + normalized);
        return true;
    }

    private static String normalizeServerUrl(String value) {
        String configured = value == null ? "" : value.trim();
        if (configured.isBlank()) {
            return DEFAULT_SERVER_URL;
        }
        try {
            URI uri = URI.create(configured);
            String host = uri.getHost();
            // Removed legacy studio host forced migration so test environments can be used.
            if (SCHOOL_SERVER_HOST.equalsIgnoreCase(host)
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                return DEFAULT_SERVER_URL;
            }
        } catch (IllegalArgumentException ignored) {
            // Keep invalid custom values so the settings validation/self-check can explain the problem.
        }
        return configured;
    }
}
