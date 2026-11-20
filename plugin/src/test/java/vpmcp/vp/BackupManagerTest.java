package vpmcp.vp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Restore-point contracts: one copy per saved state, exact bytes, bounded history. */
class BackupManagerTest {

    @TempDir
    File temp;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    private Clock clock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        };
    }

    private File project(String content) throws IOException {
        File file = new File(temp, "wash watch.vpp");
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void firstApplyCopiesTheSavedFileByteForByte() throws IOException {
        File vpp = project("saved state");
        BackupManager backups = new BackupManager(true, new File(temp, "backups"), 5, clock());

        JsonObject report = backups.ensureBackup(vpp);

        assertTrue(report.get("created").getAsBoolean(), report.toString());
        File copy = new File(report.get("file").getAsString());
        assertArrayEquals(Files.readAllBytes(vpp.toPath()), Files.readAllBytes(copy.toPath()));
    }

    @Test
    void unchangedFileIsNotCopiedTwiceButASaveTriggersANewCopy() throws IOException {
        File vpp = project("v1");
        BackupManager backups = new BackupManager(true, new File(temp, "backups"), 5, clock());
        File first = new File(backups.ensureBackup(vpp).get("file").getAsString());

        JsonObject again = backups.ensureBackup(vpp);
        assertFalse(again.get("created").getAsBoolean());
        assertEquals(first.getAbsolutePath(), again.get("file").getAsString());

        Files.write(vpp.toPath(), "v2".getBytes(StandardCharsets.UTF_8));
        assertTrue(vpp.setLastModified(vpp.lastModified() + 5_000));
        now.addAndGet(60_000);
        JsonObject afterSave = backups.ensureBackup(vpp);

        assertTrue(afterSave.get("created").getAsBoolean());
        assertEquals("v2", new String(Files.readAllBytes(
                new File(afterSave.get("file").getAsString()).toPath()), StandardCharsets.UTF_8));
    }

    @Test
    void historyIsPrunedToTheKeepLimitNewestFirst() throws IOException {
        File vpp = project("x");
        int keep = 2;
        BackupManager backups = new BackupManager(true, new File(temp, "backups"), keep, clock());
        File newest = null;
        for (int save = 0; save < keep + 3; save++) {
            assertTrue(vpp.setLastModified(1_600_000_000_000L + save * 10_000L));
            now.addAndGet(60_000);
            newest = new File(backups.ensureBackup(vpp).get("file").getAsString());
        }

        File[] kept = newest.getParentFile().listFiles((d, n) -> n.endsWith(".vpp"));
        assertEquals(keep, kept.length);
        assertTrue(newest.isFile());
    }

    @Test
    void neverSavedProjectIsReportedNotFailed() throws IOException {
        BackupManager backups = new BackupManager(true, new File(temp, "backups"), 5, clock());

        JsonObject report = backups.ensureBackup(new File(temp, "missing.vpp"));

        assertFalse(report.get("created").getAsBoolean());
        assertTrue(report.has("note"));
    }

    @Test
    void disabledManagerNeverTouchesDisk() throws IOException {
        File root = new File(temp, "backups");
        JsonObject report = new BackupManager(false, root, 5, clock()).ensureBackup(project("x"));

        assertFalse(report.get("enabled").getAsBoolean());
        assertFalse(root.exists());
    }

    @Test
    void unwritableBackupFolderFailsLoudly() throws IOException {
        File blocker = new File(temp, "not-a-dir");
        Files.write(blocker.toPath(), new byte[] {1});
        BackupManager backups = new BackupManager(true, blocker, 5, clock());

        assertThrows(IOException.class, () -> backups.ensureBackup(project("x")));
    }

    @Test
    void propertiesCanDisableOrRelocateBackups() throws IOException {
        Files.write(new File(temp, "mcp.properties").toPath(),
                ("backup=false\n").getBytes(StandardCharsets.UTF_8));
        assertFalse(BackupManager.load(temp).isEnabled());

        File elsewhere = new File(temp, "elsewhere");
        Files.write(new File(temp, "mcp.properties").toPath(),
                ("backup_dir=" + elsewhere.getAbsolutePath().replace("\\", "/") + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        BackupManager relocated = BackupManager.load(temp);
        JsonObject report = relocated.ensureBackup(project("x"));
        assertTrue(new File(report.get("file").getAsString()).toPath().startsWith(elsewhere.toPath()));
    }
}
