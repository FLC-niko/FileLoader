package topview.fileloader.monitor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import topview.fileloader.config.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SourceMonitorTest {
    @TempDir
    Path tempDir;

    private SourceMonitor monitor;

    @AfterEach
    void tearDown() {
        if (monitor != null) {
            monitor.close();
        }
    }

    @Test
    void monitorsMoreThanFiveFoldersWithoutStarvingReadinessChecks() throws Exception {
        AppConfig.setOnlyUploadPdf(false);
        CountDownLatch ready = new CountDownLatch(8);
        Set<Path> received = ConcurrentHashMap.newKeySet();
        monitor = new SourceMonitor((file, sourceFolder) -> {
            received.add(file);
            ready.countDown();
        });

        for (int index = 0; index < 8; index++) {
            Path folder = Files.createDirectory(tempDir.resolve("folder-" + index));
            assertTrue(monitor.startMonitoring(folder));
            Files.writeString(folder.resolve("same-name.txt"), "content-" + index);
        }

        assertTrue(ready.await(12, TimeUnit.SECONDS));
        assertEquals(8, received.size(), "same file names in different folders must remain distinct");
        assertEquals(8, monitor.monitoredFolders().size());
    }

    @Test
    void initialScanUsesTopLevelFilesAndHonoursPdfFilter() throws Exception {
        AppConfig.setOnlyUploadPdf(true);
        Path folder = Files.createDirectory(tempDir.resolve("source"));
        Files.writeString(folder.resolve("accepted.pdf"), "pdf");
        Files.writeString(folder.resolve("ignored.docx"), "docx");
        Path nested = Files.createDirectory(folder.resolve("nested"));
        Files.writeString(nested.resolve("nested.pdf"), "nested");

        CountDownLatch ready = new CountDownLatch(1);
        Set<Path> received = ConcurrentHashMap.newKeySet();
        monitor = new SourceMonitor((file, sourceFolder) -> {
            received.add(file);
            ready.countDown();
        });

        assertTrue(monitor.startMonitoring(folder));
        assertTrue(ready.await(6, TimeUnit.SECONDS));
        Thread.sleep(500);
        assertEquals(Set.of(folder.resolve("accepted.pdf").toAbsolutePath().normalize()), received);
    }
}
