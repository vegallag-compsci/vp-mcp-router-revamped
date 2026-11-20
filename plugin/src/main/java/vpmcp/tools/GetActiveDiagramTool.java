package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IProject;
import java.util.ArrayList;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.vp.DiagramExtractor;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;

/** The diagram in front in the editor, read in full, plus the ids selected on it. */
public final class GetActiveDiagramTool implements McpTool {

    private static final String DESCRIPTION =
            "Read the diagram that is active (in front) in the Visual Paradigm editor, with "
            + "the same graph shape as get_diagram_by_url, plus \"selected_ids\": the view "
            + "ids the operator has selected on it (empty when nothing is). Use it instead of "
            + "asking the user for a diagram link.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"detail\":{\"type\":\"string\",\"enum\":[\"standard\",\"full\"],\"default\":\"standard\","
            + "\"description\":\"\\\"full\\\" additionally dumps every raw model and view property.\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_get_active_diagram", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        JsonElement detail = params.get("detail");
        boolean full = detail != null && detail.isJsonPrimitive()
                && "full".equalsIgnoreCase(detail.getAsString());
        IProject project = DiagramLocator.requireOpenProject();
        IDiagramUIModel active = SelectionReader.requireActiveDiagram();
        JsonObject graph = new DiagramExtractor(full, new ArrayList<>()).extract(active, project);
        graph.add("selected_ids", SelectionReader.ids(SelectionReader.selectedElements(active)));
        return graph;
    }
}
