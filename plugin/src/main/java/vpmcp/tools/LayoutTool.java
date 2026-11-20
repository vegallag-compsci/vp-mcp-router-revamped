package vpmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.journal.ChangeJournal;
import vpmcp.vp.BackupManager;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;
import vpmcp.vp.VpLog;
import vpmcp.vp.VppUrl;
import vpmcp.write.BatchApplier.Compensation;
import vpmcp.write.LayoutSnapshot;

/**
 * Runs one of VP's built-in auto-layouts on a whole diagram (ADR-0035).
 * Dry run without confirm. With confirm: backup, snapshot every view's
 * geometry, lay out, journal the moved views with the snapshot as the undo
 * step, so vp_revert_batch puts everything back.
 */
public final class LayoutTool implements McpTool {

    private static final Map<String, Integer> ALGORITHMS = new LinkedHashMap<>();

    static {
        ALGORITHMS.put("hierarchical", DiagramManager.LAYOUT_HIERARCHIC);
        ALGORITHMS.put("orthogonal", DiagramManager.LAYOUT_ORTHOGONAL);
        ALGORITHMS.put("organic", DiagramManager.LAYOUT_ORGANIC);
        ALGORITHMS.put("smart_organic", DiagramManager.LAYOUT_SMART_ORGANIC_LAYOUT);
        ALGORITHMS.put("tree", DiagramManager.LAYOUT_TREE_STYLE);
        ALGORITHMS.put("directed_tree", DiagramManager.LAYOUT_DIRECTED_TREE);
        ALGORITHMS.put("circular", DiagramManager.LAYOUT_CIRCULAR);
        ALGORITHMS.put("compact", DiagramManager.LAYOUT_COMPACT);
        ALGORITHMS.put("route_orthogonal", DiagramManager.LAYOUT_ROUTE_CONNECTORS_ORTHOGONAL);
        ALGORITHMS.put("route_organic", DiagramManager.LAYOUT_ROUTE_CONNECTORS_ORGANIC);
        ALGORITHMS.put("auto", DiagramManager.LAYOUT_AUTO);
    }

    private static final String DESCRIPTION =
            "Run a Visual Paradigm auto-layout on a whole diagram (vpp_url, or the active diagram). "
            + "algorithm: hierarchical (default), orthogonal, organic, smart_organic, tree, "
            + "directed_tree, circular, compact, auto, or route_orthogonal / route_organic (re-route "
            + "connectors only, shapes stay). VP lays out the whole diagram; there is no "
            + "selection-only layout. Without {\"confirm\": true} it is a dry run (counts, allowed). "
            + "With confirm it backs up like vp_apply_batch, snapshots every view's geometry, runs "
            + "the layout, and returns batch_id plus the moved views; vp_revert_batch with that "
            + "batch_id restores the previous geometry. Layouts ignore containment: on a use case "
            + "diagram they move use cases out of the System boundary, so prefer route_orthogonal "
            + "there or try on a duplicate_diagram copy. Always export an image and check. "
            + "The project is NOT saved.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"vpp_url\":{\"type\":\"string\",\"description\":\"Diagram address or bare id; "
            + "defaults to the active diagram.\"},"
            + "\"algorithm\":{\"type\":\"string\",\"enum\":" + enumJson() + ",\"default\":\"hierarchical\"},"
            + "\"confirm\":{\"type\":\"boolean\",\"description\":\"true to lay out; omit for a dry run.\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_layout", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final BackupManager backups;
    private final ChangeJournal journal;

    public LayoutTool(BackupManager backups, ChangeJournal journal) {
        this.backups = backups;
        this.journal = journal;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        String algorithm = string(params, "algorithm", "hierarchical");
        Integer code = ALGORITHMS.get(algorithm);
        if (code == null) {
            throw new McpToolException("Unknown algorithm \"" + algorithm + "\"; use one of "
                    + ALGORITHMS.keySet() + ".");
        }
        JsonElement confirmValue = params.get("confirm");
        if (confirmValue != null && !(confirmValue.isJsonPrimitive()
                && confirmValue.getAsJsonPrimitive().isBoolean())) {
            throw new McpToolException("\"confirm\" must be a boolean.");
        }
        boolean confirm = confirmValue != null && confirmValue.getAsBoolean();

        IProject project = DiagramLocator.requireOpenProject();
        IDiagramUIModel diagram = params.has("vpp_url")
                ? DiagramLocator.locate(VppUrl.parse(string(params, "vpp_url", "")), project, new ArrayList<>())
                : SelectionReader.requireActiveDiagram();
        LayoutSnapshot before = LayoutSnapshot.capture(diagram);
        boolean allowed = allowLayout(diagram);

        JsonObject result = new JsonObject();
        result.addProperty("diagram", diagram.getId());
        result.addProperty("diagram_name", diagram.getName());
        result.addProperty("algorithm", algorithm);
        result.addProperty("shapes", before.shapeCount());
        result.addProperty("connectors", before.connectorCount());
        result.addProperty("allowed", allowed);
        if (!allowed) {
            result.addProperty("reason", "VP does not allow auto-layout on this diagram type.");
        }
        if (!confirm || !allowed) {
            result.addProperty("dry_run", true);
            return result;
        }

        JsonObject backup = null;
        if (backups.isEnabled()) {
            try {
                backup = backups.ensureBackup(ApplyBatchTool.projectFile(project));
            } catch (IOException | RuntimeException failed) {
                throw new McpToolException("Refusing to lay out: the pre-write backup failed (" + failed
                        + "). Nothing was changed.");
            }
        }

        DiagramManager manager = ApplicationManager.instance().getDiagramManager();
        try {
            manager.layout(diagram, code);
        } catch (RuntimeException failure) {
            VpLog.error("LAYOUT failed on " + diagram.getId(), failure);
            try {
                before.restore();
            } catch (RuntimeException restoreFailure) {
                VpLog.error("LAYOUT restore failed on " + diagram.getId(), restoreFailure);
            }
            throw new McpToolException("Layout failed (" + failure + "); the previous geometry was "
                    + "restored.");
        }

        List<IDiagramElement> moved = before.changed();
        JsonArray applied = new JsonArray();
        int movedShapes = 0;
        for (IDiagramElement view : moved) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "layout");
            boolean connector = view instanceof IConnectorUIModel;
            entry.addProperty("kind", connector ? "reroute" : "move");
            entry.addProperty("vp_id", view.getId());
            IModelElement model = view.getModelElement();
            entry.addProperty("name", model == null ? null : model.getName());
            if (!connector) {
                movedShapes++;
                JsonObject bounds = new JsonObject();
                bounds.addProperty("x", view.getX());
                bounds.addProperty("y", view.getY());
                bounds.addProperty("width", view.getWidth());
                bounds.addProperty("height", view.getHeight());
                entry.add("bounds", bounds);
            }
            applied.add(entry);
        }
        result.addProperty("moved_shapes", movedShapes);
        result.addProperty("rerouted_connectors", moved.size() - movedShapes);
        result.add("applied", applied);
        if (backup != null) {
            result.add("backup", backup);
        }
        if (moved.isEmpty()) {
            result.addProperty("note", "The layout left every view where it was; nothing to journal.");
            return result;
        }

        JsonObject op = new JsonObject();
        op.addProperty("op", "layout");
        op.addProperty("id", "layout");
        op.addProperty("diagram", diagram.getId());
        op.addProperty("algorithm", algorithm);
        JsonArray ops = new JsonArray();
        ops.add(op);
        JsonObject journalResult = new JsonObject();
        journalResult.add("applied", applied);
        journalResult.add("errors", new JsonArray());
        List<Compensation> undo = new ArrayList<>();
        undo.add(new Compensation("layout", before::restore));
        JsonObject record = journal.recordBatch(ChangeJournal.projectKey(project), project, ops,
                journalResult, undo);
        result.add("batch_id", record.get("batch_id"));
        if (record.has("journal_warning")) {
            result.add("journal_warning", record.get("journal_warning"));
        }
        VpLog.info("LAYOUT " + algorithm + " on " + diagram.getId() + ": " + moved.size() + " views changed");
        return result;
    }

    private static boolean allowLayout(IDiagramUIModel diagram) {
        try {
            return diagram.isAllowLayout();
        } catch (RuntimeException | LinkageError unknown) {
            return true;
        }
    }

    private static String enumJson() {
        StringBuilder out = new StringBuilder("[");
        for (String name : ALGORITHMS.keySet()) {
            out.append(out.length() > 1 ? "," : "").append('"').append(name).append('"');
        }
        return out.append(']').toString();
    }

    private static String string(JsonObject params, String name, String fallback) {
        JsonElement value = params.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
