package topview.fileloader.persistence;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import topview.fileloader.config.AppPaths;

import static org.junit.jupiter.api.Assertions.*;

class BatchDatabaseMigrationTest {
    private static Path home;

    @BeforeAll
    static void createLegacySchema(@TempDir Path tempHome) throws Exception {
        home = tempHome;
        System.setProperty(AppPaths.HOME_OVERRIDE_PROPERTY, home.resolve(".fileloader").toString());
        Class.forName("org.sqlite.JDBC");
        Path appHome = Files.createDirectories(home.resolve(".fileloader"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + appHome.resolve("batches.db"));
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE batch_records (
                        batch_id TEXT PRIMARY KEY,
                        status TEXT NOT NULL DEFAULT '',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    INSERT INTO batch_records(batch_id, status, created_at, updated_at)
                    VALUES ('legacy-batch', '处理中', '2026-01-01 10:00:00', '2026-01-01 10:00:00')
                    """);
        }
    }

    @AfterAll
    static void restoreDataHome() {
        System.clearProperty(AppPaths.HOME_OVERRIDE_PROPERTY);
    }

    @Test
    void addsTaskColumnsWithoutDeletingLegacyRows() {
        BatchDatabase.init();
        var records = BatchDatabase.getAll();
        assertEquals(1, records.size());
        assertEquals("legacy-batch", records.get(0).batchId);
        assertEquals("PROCESSING", records.get(0).stage);

        BatchDatabase.upsertTask("legacy-batch", "完成", "资料目录", "/tmp/source",
                "READY", 4, 4, 0);
        var migrated = BatchDatabase.getAll().get(0);
        assertEquals("资料目录", migrated.sourceLabel);
        assertEquals("READY", migrated.stage);
        assertEquals(4, migrated.totalFiles);

        BatchDatabase.deleteByIds(java.util.Set.of("legacy-batch"));
        assertTrue(BatchDatabase.getAll().isEmpty());

        BatchDatabase.upsert("batch-1", "状态1");
        BatchDatabase.upsert("batch-2", "状态2");
        assertEquals(2, BatchDatabase.getAll().size());
        BatchDatabase.clearAll();
        assertTrue(BatchDatabase.getAll().isEmpty());
    }
}
