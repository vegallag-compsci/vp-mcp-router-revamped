package vpmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IProject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.export.TextExporter;
import vpmcp.vp.DiagramExtractor;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;
import vpmcp.vp.VppUrl;

/** Exports diagrams as git-diffable text (ADR-0034). Read-only on the model. */
public final class ExportTextTool implements McpTool {

    private static final String DESCRIPTION =
            "Export diagrams of the open project as text that diffs well in git: \"plantuml\" "
            + "(default), \"mermaid\" (classDiagram for class diagrams, flowchart otherwise) or "
            + "\"json\" (normalized model facts plus bounds; the only format where moves show). "
            + "Output is deterministic: sorted, no colours or z-order, so re-exporting an "
            + "unchanged diagram gives identical text. One diagram: vpp_url, or the active "
            + "diagram when omitted; returns {diagram, format, text} or, with out_dir, writes "
            + "<diagram name>.<puml|mmd|json> and returns {files[]}. \"all\": true exports every "
            + "diagram and requires out_dir. Never modifies the project.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"vpp_url\":{\"type\":\"string\",\"description\":\"Diagram address or bare id; "
            + "defaults to the active diagram.\"},"
            + "\"format\":{\"type\":\"string\",\"enum\":[\"plantuml\",\"mermaid\",\"json\"],"
            + "\"default\":\"plantuml\"},"
            + "\"all\":{\"type\":\"boolean\",\"description\":\"Export every diagram (needs out_dir).\"},"
            + "\"out_dir\":{\"type\":\"string\",\"description\":\"Absolute folder to write files into, "
            + "e.g. a docs/diagrams folder in a git repo. Created if missing; files are overwritten.\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_export_text", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        TextExporter.Format format;
        try {
            format = TextExporter.Format.parse(optionalString(params, "format", "plantuml"));
        } catch (IllegalArgumentException unknown) {
            throw new McpToolException(unknown.getMessage());
        }
        boolean all = params.has("all") && params.get("all").isJsonPrimitive()
                && params.get("all").getAsBoolean();
        String outDir = optionalString(params, "out_dir", null);
        if (all && outDir == null) {
            throw new McpToolException("\"all\": true needs \"out_dir\" (one file per diagram).");
        }
        if (all && params.has("vpp_url")) {
            throw new McpToolException("Pass either \"vpp_url\" or \"all\", not both.");
        }

        IProject project = DiagramLocator.requireOpenProject();
        List<IDiagramUIModel> diagrams = new ArrayList<>();
        if (all) {
            IDiagramUIModel[] every = project.toDiagramArray();
            if (every != null) {
                for (IDiagramUIModel diagram : every) {
                    if (diagram != null) {
                        diagrams.add(diagram);
                    }
                }
            }
        } else if (params.has("vpp_url")) {
            diagrams.add(DiagramLocator.locate(VppUrl.parse(optionalString(params, "vpp_url", "")),
                    project, new ArrayList<>()));
        } else {
            diagrams.add(SelectionReader.requireActiveDiagram());
        }

        if (outDir == null) {
            IDiagramUIModel diagram = diagrams.get(0);
            JsonObject result = new JsonObject();
            result.add("diagram", describe(diagram));
            result.addProperty("format", format.name().toLowerCase());
            result.addProperty("text", render(diagram, project, format));
            return result;
        }

        File dir = new File(outDir);
        if (!dir.isAbsolute()) {
            throw new McpToolException("\"out_dir\" must be an absolute path.");
        }
        try {
            Files.createDirectories(dir.toPath());
        } catch (IOException | RuntimeException failed) {
            throw new McpToolException("Cannot create " + dir.getAbsolutePath() + ": " + failed);
        }
        Set<String> taken = new HashSet<>();
        Set<String> clashing = new HashSet<>();
        for (IDiagramUIModel diagram : diagrams) {
            if (!taken.add(fileBase(diagram.getName()).toLowerCase())) {
                clashing.add(fileBase(diagram.getName()).toLowerCase());
            }
        }
        JsonArray files = new JsonArray();
        for (IDiagramUIModel diagram : diagrams) {
            String base = fileBase(diagram.getName());
            if (clashing.contains(base.toLowerCase())) {
                base = base + "-" + fileBase(diagram.getId());
            }
            File target = new File(dir, base + "." + format.extension);
            JsonObject entry = describe(diagram);
            try {
                String text = render(diagram, project, format);
                Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
                entry.addProperty("file", target.getAbsolutePath());
            } catch (IOException | RuntimeException failed) {
                entry.addProperty("error", failed.toString());
            }
            files.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("format", format.name().toLowerCase());
        result.addProperty("out_dir", dir.getAbsolutePath());
        result.add("files", files);
        return result;
    }

    private static String render(IDiagramUIModel diagram, IProject project, TextExporter.Format format) {
        JsonObject graph = new DiagramExtractor(false, new ArrayList<>()).extract(diagram, project);
        return TextExporter.export(graph, format);
    }

    private static JsonObject describe(IDiagramUIModel diagram) {
        JsonObject json = new JsonObject();
        json.addProperty("id", diagram.getId());
        json.addProperty("name", diagram.getName());
        json.addProperty("type", diagram.getType());
        return json;
    }

    /**
     * Shell- and file-system-safe base name: letters, digits, dots and
     * underscores kept, every other run becomes one dash ("UC - WashWatch
     * (All)" gives "UC-WashWatch-All").
     */
    public static String fileBase(String name) {
        String base = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9._]+", "-")
                .replaceAll("^[-.]+|-+$", "");
        return base.isEmpty() ? "diagram" : base;
    }

    private static String optionalString(JsonObject params, String name, String fallback) {
        JsonElement value = params.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }
}
