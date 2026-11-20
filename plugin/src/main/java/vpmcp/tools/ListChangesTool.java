package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IProject;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.journal.ChangeJournal;
import vpmcp.vp.DiagramLocator;

/** Lists journaled batches for the open project, newest first (ADR-0033). Read-only. */
public final class ListChangesTool implements McpTool {

    private static final String DESCRIPTION =
            "List the write batches applied to the open project through vp_apply_batch, and any "
            + "reverts, newest first. Each entry has batch_id, time, status, op counts, and the "
            + "changes (op id, kind, name, vp_id, view_id). \"revertable\" is true when "
            + "vp_revert_batch can undo it now: same VP session, project not reopened, not yet "
            + "reverted, and no later standing batch touching the same elements. History persists "
            + "across VP restarts; undo steps do not. Edits made by hand in VP are not journaled.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"Max entries (default 20).\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_list_changes", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final ChangeJournal journal;

    public ListChangesTool(ChangeJournal journal) {
        this.journal = journal;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        int limit = 20;
        JsonElement requested = params.get("limit");
        if (requested != null && !requested.isJsonNull()) {
            if (!requested.isJsonPrimitive() || !requested.getAsJsonPrimitive().isNumber()
                    || requested.getAsInt() < 1) {
                throw new McpToolException("\"limit\" must be a positive integer.");
            }
            limit = requested.getAsInt();
        }
        IProject project = DiagramLocator.requireOpenProject();
        String key = ChangeJournal.projectKey(project);
        JsonObject result = new JsonObject();
        result.addProperty("project", key);
        result.add("changes", journal.list(key, limit));
        return result;
    }
}
