package vpmcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.model.IProject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.layout.StyleProfile;
import vpmcp.layout.TextMeasure;
import vpmcp.layout.UseCaseStrategy;
import vpmcp.lint.DiagramLinter;
import vpmcp.lint.LayoutLinter;
import vpmcp.vp.DiagramExtractor;
import vpmcp.vp.DiagramLocator;
import vpmcp.vp.SelectionReader;
import vpmcp.vp.VppUrl;

/**
 * Plans a tidy of an existing diagram (ADR-0039). Read-only: returns ordinary
 * batch ops plus the score now and the score predicted after applying them,
 * so the agent can preview, explain and apply through the normal path.
 */
public final class PlanLayoutTool implements McpTool {

    private static final String DESCRIPTION =
            "Plan a tidy of a use case diagram (vpp_url, or the active diagram) without changing it. "
            + "Lays it out by meaning: actors on the course sides (people left, external systems right; "
            + "\"sides\" overrides per actor name or view id), use cases in number order grouped by actor "
            + "with include/extend partners beside their base, two columns past 8 use cases, widths fitted "
            + "to measured names, boundary fitted to its contents, actors level with their use cases, "
            + "straight edge-to-edge connectors. Returns ops (move_element and route_connector) for "
            + "vp_preview_batch / vp_apply_batch, actor_sides with the reason for each, score_before and "
            + "score_predicted (vp_lint_diagram layout score), the layout findings before and predicted, "
            + "and recommended=true only when the predicted score is higher and no new layout warning "
            + "appears. Explain the plan, apply only on approval, then lint and look at a PNG.";

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
            + "\"vpp_url\":{\"type\":\"string\",\"description\":\"Diagram address or bare id; "
            + "defaults to the active diagram.\"},"
            + "\"sides\":{\"type\":\"object\",\"additionalProperties\":{\"type\":\"string\","
            + "\"enum\":[\"left\",\"right\"]},\"description\":\"Actor name or view id to side, overriding "
            + "the automatic classification.\"}},"
            + "\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_plan_layout", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());
    private final StyleProfile profile;

    public PlanLayoutTool(StyleProfile profile) {
        this.profile = profile;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        IProject project = DiagramLocator.requireOpenProject();
        JsonElement url = params.get("vpp_url");
        IDiagramUIModel diagram = url != null && url.isJsonPrimitive() && !url.getAsString().trim().isEmpty()
                ? DiagramLocator.locate(VppUrl.parse(url.getAsString()), project, new ArrayList<>())
                : SelectionReader.requireActiveDiagram();
        if (!"UseCaseDiagram".equals(diagram.getType())) {
            throw new McpToolException("vp_plan_layout handles use case diagrams so far; '" + diagram.getName()
                    + "' is a " + diagram.getType() + ".");
        }
        Map<String, String> sides = new HashMap<>();
        JsonElement given = params.get("sides");
        if (given != null && given.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : given.getAsJsonObject().entrySet()) {
                String side = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : "";
                if (!"left".equals(side) && !"right".equals(side)) {
                    throw new McpToolException("\"sides\" values must be \"left\" or \"right\".");
                }
                sides.put(entry.getKey(), side);
            }
        }
        JsonObject graph = new DiagramExtractor(false, new ArrayList<>()).extract(diagram, project);
        return plan(graph, profile, TextMeasure.awt(profile.fontName(), profile.fontSize()), sides);
    }

    /** The whole planning step on a graph; public static for unit tests without VP. */
    public static JsonObject plan(JsonObject graph, StyleProfile profile, TextMeasure measure,
            Map<String, String> sides) {
        UseCaseStrategy.Result layout = UseCaseStrategy.plan(graph, profile, measure, sides);
        JsonArray ops = new JsonArray();
        int n = 0;
        for (UseCaseStrategy.Placement placement : layout.placements) {
            if (!placement.changes()) {
                continue;
            }
            JsonObject op = new JsonObject();
            op.addProperty("op", "move_element");
            op.addProperty("id", "m" + (++n));
            op.addProperty("element", placement.viewId);
            op.addProperty("x", placement.after[0]);
            op.addProperty("y", placement.after[1]);
            op.addProperty("width", placement.after[2]);
            op.addProperty("height", placement.after[3]);
            ops.add(op);
        }
        int r = 0;
        for (UseCaseStrategy.Route route : layout.routes) {
            JsonObject op = new JsonObject();
            op.addProperty("op", "route_connector");
            op.addProperty("id", "r" + (++r));
            op.addProperty("element", route.edgeId);
            JsonArray points = new JsonArray();
            for (int[] p : route.points) {
                JsonObject point = new JsonObject();
                point.addProperty("x", p[0]);
                point.addProperty("y", p[1]);
                points.add(point);
            }
            op.add("points", points);
            if (route.label != null) {
                op.add("label", box(route.label));
            }
            ops.add(op);
        }

        JsonObject predictedGraph = predict(graph, layout);
        JsonObject before = DiagramLinter.lint(graph, true, profile, measure);
        JsonObject after = DiagramLinter.lint(predictedGraph, true, profile, measure);
        int scoreBefore = before.getAsJsonObject("layout").get("score").getAsInt();
        int scoreAfter = after.getAsJsonObject("layout").get("score").getAsInt();
        int warningsBefore = layoutWarnings(before);
        int warningsAfter = layoutWarnings(after);

        JsonObject result = new JsonObject();
        result.add("diagram", before.get("diagram"));
        result.add("actor_sides", layout.actorSides);
        result.addProperty("score_before", scoreBefore);
        result.addProperty("score_predicted", scoreAfter);
        result.addProperty("layout_warnings_before", warningsBefore);
        result.addProperty("layout_warnings_predicted", warningsAfter);
        result.add("findings_before", byRule(before));
        result.add("findings_predicted", byRule(after));
        result.addProperty("recommended", scoreAfter > scoreBefore && warningsAfter <= warningsBefore);
        result.addProperty("moves", n);
        result.addProperty("routes", r);
        result.add("ops", ops);
        result.add("notes", layout.notes);
        result.addProperty("next", "Preview with vp_preview_batch, explain the plan to the user, apply with "
                + "vp_apply_batch {confirm: true} only after approval, then vp_lint_diagram and a PNG.");
        return result;
    }

    /** The graph as it will look after the ops: bounds, actor captions, waypoints. */
    static JsonObject predict(JsonObject graph, UseCaseStrategy.Result layout) {
        JsonObject copy = graph.deepCopy();
        Map<String, JsonObject> nodes = new HashMap<>();
        for (JsonElement item : copy.getAsJsonArray("nodes")) {
            nodes.put(item.getAsJsonObject().get("id").getAsString(), item.getAsJsonObject());
        }
        for (UseCaseStrategy.Placement placement : layout.placements) {
            JsonObject node = nodes.get(placement.viewId);
            JsonObject bounds = node.getAsJsonObject("bounds");
            bounds.addProperty("x", placement.after[0]);
            bounds.addProperty("y", placement.after[1]);
            bounds.addProperty("width", placement.after[2]);
            bounds.addProperty("height", placement.after[3]);
            JsonObject caption = caption(node);
            if ("Actor".equals(placement.modelType) && caption != null && caption.has("bounds")) {
                JsonObject cb = caption.getAsJsonObject("bounds");
                int cw = cb.get("width").getAsInt();
                if (cw > 0) {
                    cb.addProperty("x", placement.after[0] + (placement.after[2] - cw) / 2);
                    cb.addProperty("y", placement.after[1] + placement.after[3]);
                }
            }
        }
        Map<String, UseCaseStrategy.Route> routes = new HashMap<>();
        for (UseCaseStrategy.Route route : layout.routes) {
            routes.put(route.edgeId, route);
        }
        for (JsonElement item : copy.getAsJsonArray("edges")) {
            JsonObject edge = item.getAsJsonObject();
            UseCaseStrategy.Route route = routes.get(edge.get("id").getAsString());
            if (route == null) {
                continue;
            }
            JsonArray points = new JsonArray();
            for (int[] p : route.points) {
                JsonObject point = new JsonObject();
                point.addProperty("x", p[0]);
                point.addProperty("y", p[1]);
                points.add(point);
            }
            edge.add("waypoints", points);
            JsonObject caption = caption(edge);
            if (route.label != null && caption != null) {
                caption.add("bounds", box(route.label));
            }
        }
        return copy;
    }

    private static JsonObject box(int[] r) {
        JsonObject box = new JsonObject();
        box.addProperty("x", r[0]);
        box.addProperty("y", r[1]);
        box.addProperty("width", r[2]);
        box.addProperty("height", r[3]);
        return box;
    }

    private static JsonObject caption(JsonObject node) {
        JsonElement visual = node.get("visual_properties");
        if (visual == null || !visual.isJsonObject()) {
            return null;
        }
        JsonElement caption = visual.getAsJsonObject().get("caption");
        return caption != null && caption.isJsonObject() ? caption.getAsJsonObject() : null;
    }

    private static int layoutWarnings(JsonObject report) {
        int count = 0;
        for (JsonElement item : report.getAsJsonArray("findings")) {
            JsonObject finding = item.getAsJsonObject();
            if (LayoutLinter.LAYOUT_RULES.contains(finding.get("rule").getAsString())
                    && !"info".equals(finding.get("severity").getAsString())) {
                count++;
            }
        }
        return count;
    }

    private static JsonObject byRule(JsonObject report) {
        JsonObject counts = new JsonObject();
        for (JsonElement item : report.getAsJsonArray("findings")) {
            String rule = item.getAsJsonObject().get("rule").getAsString();
            if (LayoutLinter.LAYOUT_RULES.contains(rule)) {
                counts.addProperty(rule, counts.has(rule) ? counts.get(rule).getAsInt() + 1 : 1);
            }
        }
        return counts;
    }
}
