package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IProject;
import java.util.ArrayList;
import java.util.List;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.lint.DiagramLinter;
import vpmcp.vp.DiagramExtractor;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;
import vpmcp.vp.VppUrl;

/** Checks a diagram for structural and layout problems. Read-only. */
public final class LintDiagramTool implements McpTool {

    private static final String DESCRIPTION =
            "Check a diagram for problems and return findings, without changing anything. "
            + "Rules: unconnected_actor, unconnected_use_case, actor_inside_boundary, "
            + "outside_container (shape past its container's edge), empty_container, overlap, "
            + "crowded (touching / under 6px apart), duplicate_name, duplicate_number and "
            + "numbering_gap (UC-01.. style names), self_relationship, include_extend_ends "
            + "(include/extend must join two use cases), actor_to_actor_association, "
            + "missing_documentation. Each finding: {rule, severity error|warning|info, message, "
            + "suggestion, elements[{view_id, model_id, name}]}; ids feed vp_preview_batch. "
            + "Omit vpp_url to check the diagram active in the editor.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"vpp_url\":{\"type\":\"string\",\"description\":\"Diagram address or bare id; "
            + "omit for the active diagram.\"},"
            + "\"include_info\":{\"type\":\"boolean\",\"default\":true,"
            + "\"description\":\"false drops info-level findings (counts still report them).\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_lint_diagram", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        IProject project = DiagramLocator.requireOpenProject();
        List<String> warnings = new ArrayList<>();
        JsonElement url = params.get("vpp_url");
        IDiagramUIModel diagram = url != null && url.isJsonPrimitive() && !url.getAsString().trim().isEmpty()
                ? DiagramLocator.locate(VppUrl.parse(url.getAsString()), project, warnings)
                : SelectionReader.requireActiveDiagram();
        JsonElement info = params.get("include_info");
        boolean includeInfo = info == null || !info.isJsonPrimitive() || info.getAsBoolean();
        JsonObject graph = new DiagramExtractor(false, warnings).extract(diagram, project);
        JsonObject report = DiagramLinter.lint(graph, includeInfo);
        report.add("warnings", graph.get("warnings"));
        return report;
    }
}
