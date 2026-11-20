package vpmcp.journal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vpmcp.write.BatchApplier.Compensation;

/**
 * Change journal (ADR-0033). Every batch that mutated the open project is
 * recorded: in memory with its undo steps (so it can be reverted while VP
 * keeps the same project open), and as one JSON line per event in
 * {@code <dir>/<project>.jsonl} (so "what changed" survives restarts).
 *
 * Reverting a batch runs its undo steps in reverse. It is refused when a
 * later, still-standing batch touched any of the same ids (revert that one
 * first), when the project was reopened (the undo steps point at objects
 * that no longer exist), or when the batch was already reverted.
 * Deletions have no undo step: they are listed as irreversible.
 *
 * Pure logic: no VP calls beyond running the undo closures, so it is
 * unit-tested with fake steps on a temp dir.
 */
public final class ChangeJournal {

    /** Op fields that reference existing diagrams, views, models or members. */
    private static final String[] REFERENCE_FIELDS = {
        "diagram", "element", "from", "to", "model", "parent", "container",
        "member", "from_member", "to_member"
    };

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final File dir;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /** Every record of this session, batches and reverts, in event order. */
    private final List<JsonObject> log = new ArrayList<>();
    private int sequence;

    /** @param dir where JSONL files go; null keeps the journal in memory only. */
    public ChangeJournal(File dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    public static ChangeJournal inMemory() {
        return new ChangeJournal(null, Clock.systemUTC());
    }

    /**
     * mcp.properties next to plugin.xml: {@code journal=true|false} (default
     * true; false keeps it in memory only, so revert still works but nothing
     * is written) and {@code journal_dir} (default {@code <plugin dir>/journal}).
     */
    public static ChangeJournal load(File pluginDir) {
        java.util.Properties properties = new java.util.Properties();
        File file = pluginDir == null ? null : new File(pluginDir, "mcp.properties");
        if (file != null && file.isFile()) {
            try (java.io.InputStream stream = new java.io.FileInputStream(file)) {
                properties.load(stream);
            } catch (IOException unreadable) {
                vpmcp.vp.VpLog.error("Could not read " + file.getAbsolutePath()
                        + "; the change journal uses defaults.", unreadable);
            }
        }
        boolean persist = !"false".equalsIgnoreCase(properties.getProperty("journal", "true").trim());
        String configured = properties.getProperty("journal_dir", "").trim();
        File dir = !persist ? null
                : !configured.isEmpty() ? new File(configured)
                : pluginDir == null ? null : new File(pluginDir, "journal");
        return new ChangeJournal(dir, Clock.systemUTC());
    }

    /** Journal key for a project: its .vpp path, or its name while never saved. */
    public static String projectKey(com.vp.plugin.model.IProject project) {
        try {
            File file = project.getProjectFile();
            if (file != null) {
                return file.getAbsolutePath();
            }
        } catch (RuntimeException unreadable) {
            // fall through to the name
        }
        return "unsaved-" + project.getName();
    }

    /**
     * Records a batch that mutated the project. {@code projectToken} is the
     * live project object: revert only runs against the same instance.
     * Returns the journal record (with its {@code batch_id}).
     */
    public synchronized JsonObject recordBatch(String projectKey, Object projectToken, JsonArray ops,
            JsonObject result, List<Compensation> undo) {
        String batchId = nextId();
        JsonObject record = new JsonObject();
        record.addProperty("event", "batch");
        record.addProperty("batch_id", batchId);
        record.addProperty("time", Instant.now(clock).toString());
        record.addProperty("project", projectKey);
        boolean ok = result.getAsJsonArray("errors") == null || result.getAsJsonArray("errors").size() == 0;
        record.addProperty("status", ok ? "applied" : "failed_compensated");
        record.add("ops", ops.deepCopy());
        record.add("applied", copyArray(result, "applied"));
        if (!ok) {
            record.add("errors", copyArray(result, "errors"));
            record.add("compensated", copyArray(result, "compensated"));
        }
        Entry entry = new Entry(record, projectToken, ok ? new ArrayList<>(undo) : Collections.emptyList(),
                touchedIds(ops, record.getAsJsonArray("applied")));
        entries.put(batchId, entry);
        log.add(record);
        append(projectKey, record);
        return record;
    }

    /**
     * Newest first. Persisted history for the project, with in-session
     * entries annotated {@code revertable} and current revert state.
     */
    public synchronized JsonArray list(String projectKey, int limit) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        Map<String, String> revertedBy = new LinkedHashMap<>();
        for (JsonObject record : readPersisted(projectKey)) {
            collect(record, byId, revertedBy);
        }
        for (JsonObject record : log) {
            if (projectKey.equals(record.get("project").getAsString())) {
                collect(record, byId, revertedBy);
            }
        }
        List<JsonObject> ordered = new ArrayList<>(byId.values());
        Collections.reverse(ordered);
        JsonArray out = new JsonArray();
        for (JsonObject record : ordered) {
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            JsonObject summary = summarize(record);
            String id = record.get("batch_id").getAsString();
            if (revertedBy.containsKey(id)) {
                summary.addProperty("reverted_by", revertedBy.get(id));
            }
            Entry live = entries.get(id);
            summary.addProperty("revertable", live != null && "batch".equals(eventOf(record))
                    && live.revertBlocker(null, entries) == null && !revertedBy.containsKey(id));
            out.add(summary);
        }
        return out;
    }

    /** The most recent batch in this session that could still be reverted, or null. */
    public synchronized String latestBatchId(String projectKey) {
        String latest = null;
        for (Entry entry : entries.values()) {
            if (projectKey.equals(entry.record.get("project").getAsString())
                    && "batch".equals(eventOf(entry.record)) && entry.revertedBy == null
                    && "applied".equals(entry.record.get("status").getAsString())) {
                latest = entry.record.get("batch_id").getAsString();
            }
        }
        return latest;
    }

    /** Dry run: what a revert would undo, what it cannot, and whether it is allowed. */
    public synchronized JsonObject planRevert(String projectKey, Object projectToken, String batchId) {
        JsonObject plan = new JsonObject();
        plan.addProperty("batch_id", batchId);
        Entry entry = entries.get(batchId);
        String blocker = entry == null ? unknownReason(projectKey, batchId)
                : !projectKey.equals(entry.record.get("project").getAsString())
                        ? "Batch " + batchId + " belongs to another project."
                        : entry.revertBlocker(projectToken, entries);
        plan.addProperty("allowed", blocker == null);
        if (blocker != null) {
            plan.addProperty("reason", blocker);
        }
        if (entry != null) {
            plan.add("conflicts", entry.conflicts(entries));
            JsonArray steps = new JsonArray();
            for (int index = entry.undo.size() - 1; index >= 0; index--) {
                steps.add(entry.undo.get(index).id());
            }
            plan.add("undo_steps", steps);
            plan.add("irreversible", irreversible(entry.record));
        }
        return plan;
    }

    /** Model ids (vp_id) of the elements this batch created. */
    public synchronized List<String> createdModelIds(String batchId) {
        List<String> out = new ArrayList<>();
        Entry entry = entries.get(batchId);
        if (entry == null || !entry.record.has("applied")) {
            return out;
        }
        for (JsonElement item : entry.record.getAsJsonArray("applied")) {
            JsonObject applied = item.getAsJsonObject();
            if ("element".equals(applied.has("kind") ? applied.get("kind").getAsString() : null)
                    && applied.has("vp_id") && applied.get("vp_id").isJsonPrimitive()) {
                out.add(applied.get("vp_id").getAsString());
            }
        }
        return out;
    }

    /**
     * Diagrams this batch created, each with every view and model id the batch
     * itself made, so a caller can spot content added by hand since: reverting
     * deletes the whole diagram, hand-added views included.
     */
    public synchronized Map<String, java.util.Set<String>> createdDiagrams(String batchId) {
        Map<String, java.util.Set<String>> out = new java.util.LinkedHashMap<>();
        Entry entry = entries.get(batchId);
        if (entry == null || !entry.record.has("applied")) {
            return out;
        }
        java.util.Set<String> made = new java.util.HashSet<>();
        for (JsonElement item : entry.record.getAsJsonArray("applied")) {
            JsonObject applied = item.getAsJsonObject();
            for (String field : new String[] {"vp_id", "view_id"}) {
                if (applied.has(field) && applied.get(field).isJsonPrimitive()) {
                    made.add(applied.get(field).getAsString());
                }
            }
            if ("diagram".equals(applied.has("kind") ? applied.get("kind").getAsString() : null)) {
                out.put(applied.get("vp_id").getAsString(), made);
            }
        }
        return out;
    }

    /**
     * Runs the batch's undo steps in reverse. Throws IllegalStateException
     * (nothing touched) when {@link #planRevert} would not allow it.
     */
    public synchronized JsonObject revert(String projectKey, Object projectToken, String batchId) {
        JsonObject plan = planRevert(projectKey, projectToken, batchId);
        if (!plan.get("allowed").getAsBoolean()) {
            throw new IllegalStateException(plan.get("reason").getAsString());
        }
        Entry entry = entries.get(batchId);
        JsonArray steps = new JsonArray();
        int failed = 0;
        for (int index = entry.undo.size() - 1; index >= 0; index--) {
            Compensation step = entry.undo.get(index);
            JsonObject outcome = new JsonObject();
            outcome.addProperty("id", step.id());
            try {
                step.run();
                outcome.addProperty("undone", true);
            } catch (RuntimeException failure) {
                outcome.addProperty("undone", false);
                outcome.addProperty("message", failure.toString());
                failed++;
            }
            steps.add(outcome);
        }
        String revertId = nextId();
        JsonObject record = new JsonObject();
        record.addProperty("event", "revert");
        record.addProperty("batch_id", revertId);
        record.addProperty("time", Instant.now(clock).toString());
        record.addProperty("project", projectKey);
        record.addProperty("reverts", batchId);
        record.addProperty("status", failed == 0 ? "reverted" : "partially_reverted");
        record.add("steps", steps);
        record.add("irreversible", irreversible(entry.record));
        entry.revertedBy = revertId;
        log.add(record);
        append(projectKey, record);
        return record;
    }

    // ---- internals ---------------------------------------------------------

    private String nextId() {
        sequence++;
        return "b" + STAMP.format(Instant.now(clock)) + "-" + sequence;
    }

    private String unknownReason(String projectKey, String batchId) {
        for (JsonObject record : readPersisted(projectKey)) {
            if (batchId.equals(record.get("batch_id").getAsString())) {
                return "Batch " + batchId + " was applied in an earlier VP session; its undo steps "
                        + "are gone. Restore from the backup taken before it, or reverse it with a "
                        + "new previewed batch.";
            }
        }
        return "No batch " + batchId + " in the journal for this project.";
    }

    private static void collect(JsonObject record, Map<String, JsonObject> byId,
            Map<String, String> revertedBy) {
        byId.put(record.get("batch_id").getAsString(), record);
        if ("revert".equals(eventOf(record)) && record.has("reverts")) {
            revertedBy.put(record.get("reverts").getAsString(), record.get("batch_id").getAsString());
        }
    }

    private static String eventOf(JsonObject record) {
        return record.has("event") ? record.get("event").getAsString() : "batch";
    }

    /** Compact view for listings: counts per op, names touched, no full op bodies. */
    private static JsonObject summarize(JsonObject record) {
        JsonObject summary = new JsonObject();
        for (String key : new String[] {"batch_id", "event", "time", "status", "reverts"}) {
            if (record.has(key)) {
                summary.add(key, record.get(key));
            }
        }
        if (record.has("ops")) {
            JsonObject counts = new JsonObject();
            for (JsonElement op : record.getAsJsonArray("ops")) {
                String name = op.getAsJsonObject().has("op")
                        ? op.getAsJsonObject().get("op").getAsString() : "?";
                counts.addProperty(name, counts.has(name) ? counts.get(name).getAsInt() + 1 : 1);
            }
            summary.add("ops", counts);
        }
        if (record.has("applied")) {
            JsonArray changes = new JsonArray();
            for (JsonElement item : record.getAsJsonArray("applied")) {
                JsonObject applied = item.getAsJsonObject();
                JsonObject change = new JsonObject();
                for (String key : new String[] {"id", "kind", "name", "vp_id", "view_id"}) {
                    if (applied.has(key)) {
                        change.add(key, applied.get(key));
                    }
                }
                changes.add(change);
            }
            summary.add("changes", changes);
        }
        if (record.has("steps")) {
            summary.add("steps", record.get("steps"));
        }
        return summary;
    }

    private static JsonArray irreversible(JsonObject record) {
        JsonArray out = new JsonArray();
        if (record.has("applied")) {
            for (JsonElement item : record.getAsJsonArray("applied")) {
                JsonObject applied = item.getAsJsonObject();
                if (applied.has("kind") && "deletion".equals(applied.get("kind").getAsString())) {
                    out.add(applied.deepCopy());
                }
            }
        }
        return out;
    }

    /** VP ids a batch read or wrote: op references plus every id it produced. */
    static Set<String> touchedIds(JsonArray ops, JsonArray applied) {
        Set<String> ids = new HashSet<>();
        for (JsonElement item : ops) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject op = item.getAsJsonObject();
            for (String field : REFERENCE_FIELDS) {
                JsonElement value = op.get(field);
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    ids.add(value.getAsString());
                }
            }
        }
        if (applied != null) {
            for (JsonElement item : applied) {
                JsonObject entry = item.getAsJsonObject();
                for (String field : new String[] {"vp_id", "view_id"}) {
                    if (entry.has(field) && !entry.get(field).isJsonNull()) {
                        ids.add(entry.get(field).getAsString());
                    }
                }
            }
        }
        return ids;
    }

    private static JsonArray copyArray(JsonObject result, String name) {
        JsonArray source = result.getAsJsonArray(name);
        return source == null ? new JsonArray() : source.deepCopy();
    }

    private File fileFor(String projectKey) {
        String base = new File(projectKey).getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^A-Za-z0-9._ -]", "_");
        return new File(dir, (base.isEmpty() ? "project" : base) + ".jsonl");
    }

    private void append(String projectKey, JsonObject record) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir.toPath());
            try (Writer writer = Files.newBufferedWriter(fileFor(projectKey).toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(record.toString());
                writer.write('\n');
            }
        } catch (IOException | RuntimeException unwritable) {
            // The journal is a record, not a gate: a write failure must not undo a landed batch.
            record.addProperty("journal_warning", "Not persisted: " + unwritable);
        }
    }

    private List<JsonObject> readPersisted(String projectKey) {
        List<JsonObject> records = new ArrayList<>();
        if (dir == null) {
            return records;
        }
        File file = fileFor(projectKey);
        if (!file.isFile()) {
            return records;
        }
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                    if (record.has("batch_id") && projectKey.equals(
                            record.has("project") ? record.get("project").getAsString() : null)) {
                        records.add(record);
                    }
                } catch (RuntimeException corrupt) {
                    // A torn line (crash mid-write) skips; the rest of the history stays readable.
                }
            }
        } catch (IOException unreadable) {
            return records;
        }
        return records;
    }

    private static final class Entry {
        final JsonObject record;
        final Object projectToken;
        final List<Compensation> undo;
        final Set<String> touched;
        String revertedBy;

        Entry(JsonObject record, Object projectToken, List<Compensation> undo, Set<String> touched) {
            this.record = record;
            this.projectToken = projectToken;
            this.undo = undo;
            this.touched = touched;
        }

        /** Later standing batches sharing an id with this one. */
        JsonArray conflicts(Map<String, Entry> all) {
            JsonArray out = new JsonArray();
            boolean later = false;
            for (Map.Entry<String, Entry> other : all.entrySet()) {
                if (other.getValue() == this) {
                    later = true;
                    continue;
                }
                Entry candidate = other.getValue();
                if (!later || candidate.revertedBy != null
                        || !"batch".equals(eventOf(candidate.record))
                        || !record.get("project").equals(candidate.record.get("project"))
                        || !"applied".equals(candidate.record.get("status").getAsString())) {
                    continue;
                }
                Set<String> shared = new HashSet<>(touched);
                shared.retainAll(candidate.touched);
                if (!shared.isEmpty()) {
                    out.add(other.getKey());
                }
            }
            return out;
        }

        /** Why this entry cannot be reverted now, or null. A null token skips the identity check. */
        String revertBlocker(Object currentToken, Map<String, Entry> all) {
            String id = record.get("batch_id").getAsString();
            if (!"applied".equals(record.get("status").getAsString())) {
                return "Batch " + id + " failed and was already compensated; nothing to revert.";
            }
            if (revertedBy != null) {
                return "Batch " + id + " was already reverted by " + revertedBy + ".";
            }
            if (currentToken != null && currentToken != projectToken) {
                return "The project was reopened since batch " + id + "; its undo steps point at "
                        + "objects that no longer exist. Restore from the backup taken before it.";
            }
            if (undo.isEmpty()) {
                return "Batch " + id + " has no undo steps (only deletions, which are final).";
            }
            JsonArray conflicts = conflicts(all);
            if (conflicts.size() > 0) {
                return "Later batches touch the same elements: " + conflicts
                        + ". Revert them first (newest first).";
            }
            return null;
        }
    }
}
