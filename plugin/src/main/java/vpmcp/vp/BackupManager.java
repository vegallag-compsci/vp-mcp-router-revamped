package vpmcp.vp;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Copies the project's .vpp before agent writes land (ADR-0031). VP's API has
 * no transactions and deletions are final, so the file on disk as last saved
 * is the restore point. One copy is taken per saved state: the first apply
 * of a session backs up, and so does the first apply after every save (the
 * file's mtime moved). Unsaved edits are not in the file and so not in the
 * copy — the copy is the state before this run of agent edits began.
 *
 * Configured in mcp.properties next to plugin.xml:
 *   backup=true|false        (default true)
 *   backup_dir=<absolute>    (default <plugin dir>/backups)
 *   backup_keep=<n>          (copies kept per project, default 20)
 */
public final class BackupManager {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final boolean enabled;
    private final File root;
    private final int keep;
    private final Clock clock;
    private final Map<String, Long> backedUpMtime = new HashMap<>();
    private final Map<String, File> lastCopy = new HashMap<>();

    public BackupManager(boolean enabled, File root, int keep, Clock clock) {
        this.enabled = enabled;
        this.root = root;
        this.keep = Math.max(1, keep);
        this.clock = clock;
    }

    public static BackupManager disabled() {
        return new BackupManager(false, null, 1, Clock.systemDefaultZone());
    }

    public static BackupManager load(File pluginDir) {
        Properties properties = new Properties();
        File file = pluginDir == null ? null : new File(pluginDir, "mcp.properties");
        if (file != null && file.isFile()) {
            try (InputStream stream = new FileInputStream(file)) {
                properties.load(stream);
            } catch (IOException unreadable) {
                VpLog.error("Could not read " + file.getAbsolutePath() + "; backups use defaults.", unreadable);
            }
        }
        boolean enabled = !"false".equalsIgnoreCase(properties.getProperty("backup", "true").trim());
        String dir = properties.getProperty("backup_dir", "").trim();
        File root = !dir.isEmpty() ? new File(dir)
                : pluginDir == null ? null : new File(pluginDir, "backups");
        int keep = 20;
        try {
            keep = Integer.parseInt(properties.getProperty("backup_keep", "20").trim());
        } catch (NumberFormatException notANumber) {
            VpLog.info("backup_keep is not a number; keeping 20 copies.");
        }
        return new BackupManager(enabled && root != null, root, keep, Clock.systemDefaultZone());
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ensures a copy of the project's current saved state exists. Returns a
     * report for the apply result; throws when a copy was needed and failed,
     * so the caller can refuse to write without a restore point.
     */
    public synchronized JsonObject ensureBackup(File projectFile) throws IOException {
        JsonObject report = new JsonObject();
        report.addProperty("enabled", enabled);
        if (!enabled) {
            return report;
        }
        if (projectFile == null || !projectFile.isFile()) {
            report.addProperty("created", false);
            report.addProperty("note", "The project has never been saved, so there is no file to back up.");
            return report;
        }
        String key = projectFile.getAbsolutePath();
        long mtime = projectFile.lastModified();
        Long previous = backedUpMtime.get(key);
        if (previous != null && previous == mtime && lastCopy.get(key) != null
                && lastCopy.get(key).isFile()) {
            report.addProperty("created", false);
            report.addProperty("file", lastCopy.get(key).getAbsolutePath());
            report.addProperty("note", "Already backed up since the project was last saved.");
            return report;
        }
        File dir = new File(root, baseName(projectFile));
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create backup folder " + dir.getAbsolutePath());
        }
        String stamp = LocalDateTime.now(clock).format(STAMP);
        File target = new File(dir, baseName(projectFile) + "-" + stamp + ".vpp");
        for (int n = 1; target.exists(); n++) {
            target = new File(dir, baseName(projectFile) + "-" + stamp + "-" + n + ".vpp");
        }
        Files.copy(projectFile.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        backedUpMtime.put(key, mtime);
        lastCopy.put(key, target);
        int pruned = prune(dir);
        VpLog.info("BACKUP " + key + " -> " + target.getAbsolutePath());
        report.addProperty("created", true);
        report.addProperty("file", target.getAbsolutePath());
        report.addProperty("source", key);
        if (pruned > 0) {
            report.addProperty("pruned", pruned);
        }
        return report;
    }

    /** Deletes the oldest copies beyond the keep limit; returns how many went. */
    private int prune(File dir) {
        File[] copies = dir.listFiles((d, name) -> name.endsWith(".vpp"));
        if (copies == null || copies.length <= keep) {
            return 0;
        }
        Arrays.sort(copies, Comparator.comparing(File::getName).reversed());
        int removed = 0;
        for (int i = keep; i < copies.length; i++) {
            if (copies[i].delete()) {
                removed++;
            }
        }
        return removed;
    }

    static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.replaceAll("[^A-Za-z0-9._ -]", "_");
    }
}
