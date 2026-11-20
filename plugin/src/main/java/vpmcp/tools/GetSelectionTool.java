package vpmcp.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;

/** The diagram in front in the editor and what the operator has selected on it. */
public final class GetSelectionTool implements McpTool {

    private static final String DESCRIPTION =
            "Report what the operator has selected in Visual Paradigm: the active diagram "
            + "{id, name, type} and each selected element as {view_id, model_id, name, "
            + "model_type, shape_type, kind, bounds | from/to, parent_id}. Use it when the "
            + "user says \"this\", \"these\" or \"the selected ...\"; view_id and model_id "
            + "feed straight into vp_preview_batch ops. selected_count 0 means nothing is "
            + "selected — ask the user to select, don't guess.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_get_selection", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        DiagramLocator.requireOpenProject();
        IDiagramUIModel active = SelectionReader.requireActiveDiagram();
        return SelectionReader.describe(active, SelectionReader.selectedElements(active));
    }
}
