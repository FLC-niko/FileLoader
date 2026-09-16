package topview.fileloader.monitor;

import topview.fileloader.config.AppConfig;
import topview.fileloader.util.ArchiveExtractor;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches any number of top-level folders with one WatchService. Long-running
 * watch work and file-readiness work use separate executors so neither can
 * starve the other.
 */
public final class SourceMonitor implements AutoCloseable {
    public interface Listener {
        void onFileReady(Path file, Path sourceFolder);

        default void onSourceStarted(Path sourceFolder, int existingFileCount) {
        }

        default void onSourceStopped(Path sourceFolder) {
        }

        default void onSourceError(Path sourceFolder, String message) {
        }
    }

    private static final Logger logger = Logger.getLogger(SourceMonitor.class.getName());

    private final WatchService watchService;
    private final Listener listener;
    private final ExecutorService watchExecutor;
    private final ExecutorService readinessExecutor;
    private final Map<WatchKey, Path> foldersByKey = new ConcurrentHashMap<>();
    private final Map<Path, WatchKey> keysByFolder = new ConcurrentHashMap<>();
    private final Set<Path> knownFiles = ConcurrentHashMap.newKeySet();
    private final Set<Path> checkingFiles = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SourceMonitor(Listener listener) {
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件监控服务", e);
        }
        this.listener = listener;
        this.watchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "source-monitor-watch");
            thread.setDaemon(true);
            return thread;
        });
        int readinessThreads = Math.max(4, Math.min(32, Runtime.getRuntime().availableProcessors() * 4));
        this.readinessExecutor = Executors.newFixedThreadPool(readinessThreads, r -> {
            Thread thread = new Thread(r, "source-monitor-readiness");
            thread.setDaemon(true);
            return thread;
        });
        watchExecutor.submit(this::watchLoop);
    }

    public boolean startMonitoring(Path requestedFolder) {
        if (closed.get()) {
            return false;
        }
        Path folder = normalize(requestedFolder);
        if (!Files.isDirectory(folder) || keysByFolder.containsKey(folder)) {
            return false;
        }
        try {
            WatchKey key = folder.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            keysByFolder.put(folder, key);
            foldersByKey.put(key, folder);

            List<Path> existing = new ArrayList<>();
            try (var stream = Files.list(folder)) {
                stream.filter(Files::isRegularFile).map(SourceMonitor::normalize).forEach(existing::add);
            }
            listener.onSourceStarted(folder, existing.size());
            existing.forEach(file -> discover(file, folder, true));
            return true;
        } catch (Exception e) {
            WatchKey registered = keysByFolder.remove(folder);
            if (registered != null) {
                foldersByKey.remove(registered);
                registered.cancel();
            }
            logger.log(Level.WARNING, "Failed to monitor " + folder, e);
            listener.onSourceError(folder, userMessage(e));
            return false;
        }
    }

    public boolean stopMonitoring(Path requestedFolder) {
        Path folder = normalize(requestedFolder);
        WatchKey key = keysByFolder.remove(folder);
        if (key == null) {
            return false;
        }
        foldersByKey.remove(key);
        key.cancel();
        knownFiles.removeIf(path -> path.getParent() != null && path.getParent().equals(folder));
        listener.onSourceStopped(folder);
        return true;
    }

    public boolean isMonitoring(Path folder) {
        return keysByFolder.containsKey(normalize(folder));
    }

    public List<Path> monitoredFolders() {
        return keysByFolder.keySet().stream().sorted().toList();
    }

    private void watchLoop() {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                Path folder = foldersByKey.get(key);
                if (folder == null) {
                    key.reset();
                    continue;
                }
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }
                    Path relative = (Path) event.context();
                    Path file = normalize(folder.resolve(relative));
                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        knownFiles.remove(file);
                        checkingFiles.remove(file);
                    } else if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                        discover(file, folder);
                    }
                }
                if (!key.reset()) {
                    keysByFolder.remove(folder, key);
                    foldersByKey.remove(key);
                    listener.onSourceStopped(folder);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.nio.file.ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                logger.log(Level.WARNING, "Source monitor loop failed", e);
            }
        }
    }

    private void discover(Path file, Path folder) {
        discover(file, folder, false);
    }

    private void discover(Path file, Path folder, boolean isInitialScan) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (name.startsWith("~$") || ArchiveExtractor.isArchiveFile(name)) {
            return;
        }
        if (AppConfig.isOnlyUploadPdf() && !name.toLowerCase().endsWith(".pdf")) {
            return;
        }
        if (!knownFiles.add(file) || !checkingFiles.add(file)) {
            return;
        }
        if (isInitialScan) {
            try {
                if (Files.isRegularFile(file) && Files.isReadable(file)) {
                    long size = Files.size(file);
                    long lastModified = Files.getLastModifiedTime(file).toMillis();
                    // 文件夹初始扫描的既有文件：若非空且最近 1 秒内无写入修改，说明是已经写毕的稳定文件，立即就绪
                    if (size > 0 && (System.currentTimeMillis() - lastModified) > 1000) {
                        checkingFiles.remove(file);
                        listener.onFileReady(file, folder);
                        return;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        readinessExecutor.submit(() -> waitUntilReady(file, folder));
    }

    private void waitUntilReady(Path file, Path folder) {
        try {
            long previousSize = -1;
            int stableCount = 0;
            for (int attempt = 0; attempt < 20 && !closed.get(); attempt++) {
                if (!Files.exists(file)) {
                    if (attempt < 5) {
                        Thread.sleep(300);
                        continue;
                    }
                    knownFiles.remove(file);
                    return;
                }
                if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                    Thread.sleep(300);
                    continue;
                }
                long size = Files.size(file);
                long lastModified = 0;
                try {
                    lastModified = Files.getLastModifiedTime(file).toMillis();
                } catch (Exception ignored) {
                }
                if (size > 0 && lastModified > 0 && (System.currentTimeMillis() - lastModified) > 1500) {
                    listener.onFileReady(file, folder);
                    return;
                }
                if (size > 0 && size == previousSize) {
                    stableCount++;
                    if (stableCount >= 2) {
                        listener.onFileReady(file, folder);
                        return;
                    }
                } else {
                    previousSize = size;
                    stableCount = 0;
                }
                Thread.sleep(400);
            }
            listener.onSourceError(folder, "文件尚未写入完成：" + file.getFileName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Readiness check failed for " + file, e);
            listener.onSourceError(folder, "无法读取文件：" + file.getFileName());
        } finally {
            checkingFiles.remove(file);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        keysByFolder.values().forEach(WatchKey::cancel);
        keysByFolder.clear();
        foldersByKey.clear();
        try {
            watchService.close();
        } catch (IOException ignored) {
        }
        watchExecutor.shutdownNow();
        readinessExecutor.shutdownNow();
        awaitBriefly(watchExecutor);
        awaitBriefly(readinessExecutor);
    }

    private static void awaitBriefly(ExecutorService executor) {
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String userMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "无法监控此文件夹" : message;
    }
}
