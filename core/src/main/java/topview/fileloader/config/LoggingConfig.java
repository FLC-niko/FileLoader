package topview.fileloader.config;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * 把 {@code java.util.logging} 的日志落到用户目录下的 logs 文件夹，供管理员排查问题。
 * 桌面应用没有控制台，若不挂 FileHandler，core 各服务的 logger 输出无处可查。
 */
public final class LoggingConfig {
    private static final Logger root = Logger.getLogger("");
    private static final String LOG_FILE_PREFIX = "fileloader.log";
    private static final int MAX_BYTES = 5_000_000;
    private static final int FILE_COUNT = 3;
    private static final int TAIL_BYTES = 65_536;

    private static FileHandler fileHandler;

    private LoggingConfig() {
    }

    public static synchronized void init() {
        if (fileHandler != null) {
            return;
        }
        try {
            Files.createDirectories(getLogDirectory());
            FileHandler handler = new FileHandler(
                    getLogDirectory().resolve(LOG_FILE_PREFIX).toString(),
                    MAX_BYTES, FILE_COUNT, true);
            handler.setFormatter(new SimpleFormatter());
            handler.setLevel(Level.INFO);
            root.addHandler(handler);
            fileHandler = handler;
        } catch (IOException e) {
            // 日志系统尚未就绪，只能走 stderr
            System.err.println("Could not initialize file logging: " + e.getMessage());
        }
    }

    public static Path getLogDirectory() {
        return AppPaths.homeDirectory().resolve("logs");
    }

    /** 读取最新日志文件的尾部，约 {@code maxChars} 个字符。文件不存在或为空时返回空串。 */
    public static String readTail(int maxChars) {
        Path latest = findLatestLogFile();
        if (latest == null) {
            return "";
        }
        try (RandomAccessFile raf = new RandomAccessFile(latest.toFile(), "r")) {
            long length = raf.length();
            if (length <= 0) {
                return "";
            }
            long start = Math.max(0, length - TAIL_BYTES);
            raf.seek(start);
            byte[] buffer = new byte[(int) (length - start)];
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            if (text.length() > maxChars) {
                text = text.substring(text.length() - maxChars);
            }
            return text;
        } catch (IOException e) {
            return "";
        }
    }

    /** 清空所有日志文件并重建 handler，保证清空后继续追加到干净文件。 */
    public static synchronized void clear() {
        if (fileHandler != null) {
            root.removeHandler(fileHandler);
            fileHandler.close();
            fileHandler = null;
        }
        deleteLogFiles();
        init();
    }

    private static Path findLatestLogFile() {
        Path dir = getLogDirectory();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path latest = null;
        long latestModified = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, LOG_FILE_PREFIX + "*")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(entry).toMillis();
                if (modified > latestModified) {
                    latestModified = modified;
                    latest = entry;
                }
            }
        } catch (IOException ignored) {
        }
        return latest;
    }

    private static void deleteLogFiles() {
        Path dir = getLogDirectory();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, LOG_FILE_PREFIX + "*")) {
            for (Path entry : stream) {
                Files.deleteIfExists(entry);
            }
        } catch (IOException ignored) {
        }
    }
}
