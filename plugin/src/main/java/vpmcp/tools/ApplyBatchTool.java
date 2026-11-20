package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IProject;
import java.io.File;
import java.io.IOException;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
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
            + "the result's \"backup\" reports the copy (restore by opening it in VP).";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\","
            + "\"properties\":{"
            + "\"ops\":{\"type\":\"array\",\"description\":\"Write ops in dependency order.\"},"
            + "\"confirm\":{\"type\":\"boolean\",\"description\":\"Must be true.\"}},"
            + "\"required\":[\"ops\",\"confirm\"],\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_apply_batch", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final TypeTiers tiers;
    private final BackupManager backups;

    /** Verified-only tiers, no backups: the pre-pack behavior, for dev servers and tests. */
    public ApplyBatchTool() {
        this(TypeTiers.verifiedOnly());
    }

    public ApplyBatchTool(TypeTiers tiers) {
        this(tiers, BackupManager.disabled());
    }

    public ApplyBatchTool(TypeTiers tiers, BackupManager backups) {
        this.tiers = tiers;
        this.backups = backups;
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
        JsonObject result = BatchApplier.apply(project, ops.getAsJsonArray(), tiers);
        if (backup != null) {
            result.add("backup", backup);
        }
        return result;
    }

    private static File projectFile(IProject project) {
        try {
            return project.getProjectFile();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
