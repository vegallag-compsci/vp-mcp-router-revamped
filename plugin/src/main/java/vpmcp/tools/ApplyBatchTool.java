package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IProject;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.journal.ChangeJournal;
import vpmcp.vp.BackupManager;
import vpmcp.vp.DiagramLocator;
import vpmcp.write.BatchApplier;
import vpmcp.write.PlanValidator;
import vpmcp.write.TypeTiers;

/**
 * Applies a write batch to the open project. The confirm gate runs before
 * anything touches VP, so declining never reaches the model. Confirmed
 * batches re-validate immediately before the first mutation.
 */
public final class ApplyBatchTool implements McpTool {

    private static final String DESCRIPTION =
            "Apply a batch of diagram writes to the open project. Same op "
            + "shapes as vp_preview_batch; dry-run there first. Requires "
            + "{\"confirm\": true} alongside \"ops\". The project is NOT "
            + "saved; persist it with vp_save_project {\"confirm\": true}. Before the first "
            + "write after each save, the .vpp as last saved is copied to a backup folder; "
            + "the result's \"backup\" reports the copy (restore by opening it in VP). Every "
            + "batch that changed the project is journaled; the result's \"batch_id\" names it "
            + "for vp_list_changes and vp_revert_batch. The result is short by default: counts per "
            + "kind, one line per applied op (id, kind, vp_id, view_id and any warning or note), errors, "
            + "compensated, backup and batch_id. Pass \"detail\": \"full\" for the whole plan and every "
            + "applied field.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\","
            + "\"properties\":{"
            + "\"ops\":{\"type\":\"array\",\"description\":\"Write ops in dependency order.\"},"
            + "\"confirm\":{\"type\":\"boolean\",\"description\":\"Must be true.\"},"
            + "\"detail\":{\"type\":\"string\",\"enum\":[\"summary\",\"full\"],\"default\":\"summary\","
            + "\"description\":\"summary (default) or full.\"}},"
            + "\"required\":[\"ops\",\"confirm\"],\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_apply_batch", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final TypeTiers tiers;
    private final BackupManager backups;
    private final ChangeJournal journal;

    /** Verified-only tiers, no backups: the pre-pack behavior, for dev servers and tests. */
    public ApplyBatchTool() {
        this(TypeTiers.verifiedOnly());
    }

    public ApplyBatchTool(TypeTiers tiers) {
        this(tiers, BackupManager.disabled());
    }

    public ApplyBatchTool(TypeTiers tiers, BackupManager backups) {
        this(tiers, backups, ChangeJournal.inMemory());
    }

    public ApplyBatchTool(TypeTiers tiers, BackupManager backups, ChangeJournal journal) {
        this.tiers = tiers;
        this.backups = backups;
        this.journal = journal;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        JsonElement confirm = params.get("confirm");
        if (confirm == null || !confirm.isJsonPrimitive() || !confirm.getAsBoolean()) {
            throw new McpToolException("Refusing to apply: pass {\"confirm\": true} alongside \"ops\".");
        }
        JsonElement ops = params.get("ops");
        if (ops == null || !ops.isJsonArray()) {
            throw new McpToolException("\"ops\" is required and must be an array.");
        }
        IProject project = DiagramLocator.requireOpenProject();
        JsonObject backup = null;
        if (backups.isEnabled()
                && PlanValidator.validate(project, ops.getAsJsonArray(), tiers).get("valid").getAsBoolean()) {
            // Restore point first (ADR-0031): no copy, no write.
            try {
                backup = backups.ensureBackup(projectFile(project));
            } catch (IOException | RuntimeException failed) {
                throw new McpToolException("Refusing to apply: the pre-write backup failed (" + failed
                        + "). Nothing was changed. Fix the backup folder, or set backup=false in "
                        + "mcp.properties to write without a restore point.");
            }
        }
        List<BatchApplier.Compensation> undo = new ArrayList<>();
        JsonObject result = BatchApplier.apply(project, ops.getAsJsonArray(), tiers, undo);
        if (backup != null) {
            result.add("backup", backup);
        }
        if (result.get("mutated").getAsBoolean()) {
            JsonObject record = journal.recordBatch(ChangeJournal.projectKey(project), project,
                    ops.getAsJsonArray(), result, undo);
            result.add("batch_id", record.get("batch_id"));
            if (record.has("journal_warning")) {
                result.add("journal_warning", record.get("journal_warning"));
            }
        }
        JsonElement detail = params.get("detail");
        boolean full = detail != null && detail.isJsonPrimitive() && "full".equals(detail.getAsString());
        return full ? result : summarize(result);
    }

    /**
     * The short result (live: a 72-op batch came back as ~25 KB, half of it
     * the plan repeated). Keeps everything needed to act on the result:
     * ids, warnings, notes, errors, compensations, backup, batch id.
     */
    public static JsonObject summarize(JsonObject full) {
        JsonObject out = new JsonObject();
        for (String key : new String[] {"mutated"}) {
            if (full.has(key)) {
                out.add(key, full.get(key));
            }
        }
        JsonObject validation = full.has("validation") && full.get("validation").isJsonObject()
                ? full.getAsJsonObject("validation") : null;
        if (validation != null) {
            out.add("valid", validation.get("valid"));
            if (validation.has("hint")) {
                out.add("hint", validation.get("hint"));
            }
        }
        com.google.gson.JsonArray applied = full.has("applied") && full.get("applied").isJsonArray()
                ? full.getAsJsonArray("applied") : new com.google.gson.JsonArray();
        JsonObject counts = new JsonObject();
        com.google.gson.JsonArray lines = new com.google.gson.JsonArray();
        for (JsonElement item : applied) {
            JsonObject entry = item.getAsJsonObject();
            String kind = entry.has("kind") ? entry.get("kind").getAsString() : "other";
            counts.addProperty(kind, counts.has(kind) ? counts.get(kind).getAsInt() + 1 : 1);
            JsonObject line = new JsonObject();
            for (java.util.Map.Entry<String, JsonElement> field : entry.entrySet()) {
                String key = field.getKey();
                if (key.equals("id") || key.equals("kind") || key.equals("vp_id") || key.equals("view_id")
                        || key.equals("user_id")
                        || key.contains("warning") || key.contains("note") || key.contains("error")) {
                    line.add(key, field.getValue());
                }
            }
            lines.add(line);
        }
        out.add("counts", counts);
        out.add("applied", lines);
        for (String key : new String[] {"errors", "compensated", "backup", "batch_id", "journal_warning"}) {
            if (full.has(key)) {
                out.add(key, full.get(key));
            }
        }
        if (validation != null && !validation.get("valid").getAsBoolean() && !full.has("errors")) {
            out.add("errors", validation.get("errors"));
        }
        out.addProperty("detail", "summary; pass \"detail\": \"full\" for the plan and every applied field");
        return out;
    }

    static File projectFile(IProject project) {
        try {
            return project.getProjectFile();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
