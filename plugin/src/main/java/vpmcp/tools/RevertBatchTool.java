package vpmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IProject;
import java.io.IOException;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.journal.ChangeJournal;
import vpmcp.vp.BackupManager;
import vpmcp.vp.DiagramLocator;

/**
 * Reverts one journaled batch (ADR-0033). Without confirm it is a dry run
 * returning the revert plan; with {"confirm": true} it backs up (like apply)
 * and runs the batch's undo steps in reverse.
 */
public final class RevertBatchTool implements McpTool {

    private static final String DESCRIPTION =
            "Undo a batch applied by vp_apply_batch in this VP session, even after later batches, "
            + "as long as no later standing batch touched the same elements (revert those first, "
            + "newest first). \"batch_id\" defaults to the latest batch not yet reverted. Without "
            + "{\"confirm\": true} this is a dry run: it returns allowed, reason, conflicts, "
            + "undo_steps, irreversible (deletions are final and are never restored) and also_removed "
            + "(views added after the batch to a diagram it created, which go with that diagram). With "
            + "confirm it runs the undo steps in reverse and reports each step. Hand edits made in "
            + "VP after the batch may be overwritten by restored values. The project is NOT saved.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"batch_id\":{\"type\":\"string\",\"description\":\"From vp_apply_batch or vp_list_changes.\"},"
            + "\"confirm\":{\"type\":\"boolean\",\"description\":\"true to revert; omit for a dry run.\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_revert_batch", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final ChangeJournal journal;
    private final BackupManager backups;

    public RevertBatchTool(ChangeJournal journal, BackupManager backups) {
        this.journal = journal;
        this.backups = backups;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    /**
     * VP's auto-numbers of the batch's created elements, read live: VP assigns
     * them after the create returns, so the applied entry cannot carry them.
     */
    public static JsonArray userIds(IProject project, java.util.List<String> modelIds) {
        JsonArray out = new JsonArray();
        for (String id : modelIds) {
            com.vp.plugin.model.IModelElement model;
            try {
                model = vpmcp.vp.ModelLookup.byId(project, id);
            } catch (RuntimeException gone) {
                model = null;
            }
            if (model instanceof com.vp.plugin.model.IHasChildrenBaseModelElement) {
                String number = ((com.vp.plugin.model.IHasChildrenBaseModelElement) model).getUserID();
                if (number != null && !number.isEmpty()) {
                    out.add(number);
                }
            }
        }
        return out;
    }

    /** Views on batch-created diagrams that the batch did not make. */
    public static JsonArray handAddedContent(IProject project,
            java.util.Map<String, java.util.Set<String>> created) {
        JsonArray out = new JsonArray();
        for (java.util.Map.Entry<String, java.util.Set<String>> diagram : created.entrySet()) {
            com.vp.plugin.diagram.IDiagramUIModel live = project.getDiagramById(diagram.getKey());
            com.vp.plugin.diagram.IDiagramElement[] views = live == null ? null : live.toDiagramElementArray();
            if (views == null) {
                continue;
            }
            for (com.vp.plugin.diagram.IDiagramElement view : views) {
                if (view == null || diagram.getValue().contains(view.getId())) {
                    continue;
                }
                JsonObject item = new JsonObject();
                item.addProperty("view_id", view.getId());
                com.vp.plugin.model.IModelElement model = view.getModelElement();
                item.addProperty("name", model == null ? null : model.getName());
                item.addProperty("model_type", model == null ? null : model.getModelType());
                item.addProperty("diagram", live.getName());
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        JsonElement confirmValue = params.get("confirm");
        if (confirmValue != null && !(confirmValue.isJsonPrimitive()
                && confirmValue.getAsJsonPrimitive().isBoolean())) {
            throw new McpToolException("\"confirm\" must be a boolean.");
        }
        boolean confirm = confirmValue != null && confirmValue.getAsBoolean();
        JsonElement requested = params.get("batch_id");
        if (requested != null && !(requested.isJsonPrimitive()
                && requested.getAsJsonPrimitive().isString())) {
            throw new McpToolException("\"batch_id\" must be a string.");
        }
        IProject project = DiagramLocator.requireOpenProject();
        String key = ChangeJournal.projectKey(project);
        String batchId = requested != null ? requested.getAsString() : journal.latestBatchId(key);
        if (batchId == null) {
            throw new McpToolException("No batch applied in this VP session to revert. "
                    + "vp_list_changes shows the history.");
        }
        JsonObject plan = journal.planRevert(key, project, batchId);
        JsonArray handAdded = handAddedContent(project, journal.createdDiagrams(batchId));
        if (handAdded.size() > 0) {
            // Live 2026-11-20: reverting a test batch deleted its diagram and, with it,
            // two use cases the operator had drawn there by hand, without a word.
            plan.add("also_removed", handAdded);
            plan.addProperty("warning", handAdded.size() + " element(s) on diagrams this batch created "
                    + "were added afterwards (by hand or by later batches); reverting deletes those "
                    + "diagrams with them. Their models stay in the project.");
        }
        JsonArray numbers = userIds(project, journal.createdModelIds(batchId));
        if (numbers.size() > 0) {
            // Live 2026-11-20: reverting REQ003 did not free it; the next requirement was REQ004.
            plan.add("numbers_not_reused", numbers);
            plan.addProperty("numbering_note", "VP does not reissue the auto-numbers " + numbers
                    + " after this revert; later elements continue after them. Set ids explicitly "
                    + "(update_element \"requirement\": {\"id\": ...}) if the sequence matters.");
        }
        if (!confirm || !plan.get("allowed").getAsBoolean()) {
            plan.addProperty("dry_run", true);
            if (!confirm && plan.get("allowed").getAsBoolean()) {
                plan.addProperty("next", "Call again with {\"batch_id\": \"" + batchId
                        + "\", \"confirm\": true} to revert.");
            }
            return plan;
        }
        JsonObject backup = null;
        if (backups.isEnabled()) {
            try {
                backup = backups.ensureBackup(ApplyBatchTool.projectFile(project));
            } catch (IOException | RuntimeException failed) {
                throw new McpToolException("Refusing to revert: the pre-write backup failed (" + failed
                        + "). Nothing was changed.");
            }
        }
        JsonObject result;
        try {
            result = journal.revert(key, project, batchId);
        } catch (IllegalStateException refused) {
            throw new McpToolException("Refusing to revert: " + refused.getMessage());
        }
        if (backup != null) {
            result.add("backup", backup);
        }
        return result;
    }
}
