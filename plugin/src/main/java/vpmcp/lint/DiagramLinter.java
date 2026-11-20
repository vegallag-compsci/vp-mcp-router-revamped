package vpmcp.lint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural and layout checks over an extracted diagram graph (ADR-0030).
 * Input is exactly what DiagramExtractor produces ({diagram, nodes, edges}),
 * so the linter never touches VP and every rule is unit-testable on plain
 * JSON. Findings are advice, not errors: the agent turns them into a
 * previewed batch, and the user decides.
 */
public final class DiagramLinter {

    /** Shapes closer than this (px) without overlapping count as crowded. */
    static final int MIN_GAP = 6;

    private static final Set<String> DOCUMENTED_TYPES = Set.of("Actor", "UseCase", "Class");
    private static final Set<String> CONTAINER_TYPES = Set.of("System", "Package");
    private static final Pattern NUMBERED = Pattern.compile("^([A-Za-z]{1,6})[-_ ]?(\\d{1,4})\\b");

    private final JsonObject graph;
    private final Map<String, JsonObject> nodes = new LinkedHashMap<>();
    private final Map<String, Integer> degree = new HashMap<>();
    private final JsonArray findings = new JsonArray();

    private DiagramLinter(JsonObject graph) {
        this.graph = graph;
        for (JsonElement item : array(graph, "nodes")) {
            JsonObject node = item.getAsJsonObject();
            String id = text(node, "id");
            if (id != null) {
                nodes.put(id, node);
                degree.put(id, 0);
            }
        }
        for (JsonElement item : array(graph, "edges")) {
            JsonObject edge = item.getAsJsonObject();
            bump(text(edge, "from"));
            bump(text(edge, "to"));
        }
    }

    /** Runs every rule. includeInfo=false drops info-level findings. */
    public static JsonObject lint(JsonObject graph, boolean includeInfo) {
        DiagramLinter linter = new DiagramLinter(graph);
        linter.unconnected();
        linter.containment();
        linter.spacing();
        linter.duplicateNames();
        linter.numbering();
        linter.relationshipEnds();
        linter.documentation();
        return linter.report(includeInfo);
    }

    private void bump(String id) {
        if (id != null && degree.containsKey(id)) {
            degree.put(id, degree.get(id) + 1);
        }
    }

    // --- rules -------------------------------------------------------------

    private void unconnected() {
        for (JsonObject node : nodes.values()) {
            String type = text(node, "model_type");
            if (degree.get(text(node, "id")) > 0) {
                continue;
            }
            if ("Actor".equals(type)) {
                add("unconnected_actor", "warning", "Actor '" + name(node) + "' has no relationships.",
                        "Associate it with the use cases it takes part in, or remove it.", node);
            } else if ("UseCase".equals(type)) {
                add("unconnected_use_case", "warning",
                        "Use case '" + name(node) + "' has no relationships.",
                        "Associate it with an actor, or link it by include/extend.", node);
            }
        }
    }

    private void containment() {
        for (JsonObject node : nodes.values()) {
            String parentId = text(node, "parent_id");
            JsonObject parent = parentId == null ? null : nodes.get(parentId);
            String type = text(node, "model_type");
            if (CONTAINER_TYPES.contains(type) && array(node, "child_ids").size() == 0) {
                add("empty_container", "info", type + " '" + name(node) + "' contains nothing.",
                        "Move the elements it should hold inside it, or remove it.", node);
            }
            if (parent == null) {
                continue;
            }
            if ("Actor".equals(type) && "System".equals(text(parent, "model_type"))) {
                add("actor_inside_boundary", "warning",
                        "Actor '" + name(node) + "' sits inside system boundary '" + name(parent) + "'.",
                        "Actors are external to the system; move it outside the boundary.", node, parent);
            }
            int[] child = bounds(node);
            int[] box = bounds(parent);
            if (child != null && box != null && !contains(box, child)) {
                add("outside_container", "warning",
                        "'" + name(node) + "' extends past the edge of its container '" + name(parent) + "'.",
                        "Move or shrink it so it lies fully inside, or enlarge the container.", node, parent);
            }
        }
    }

    private void spacing() {
        List<JsonObject> shapes = new ArrayList<>();
        for (JsonObject node : nodes.values()) {
            if (bounds(node) != null && !CONTAINER_TYPES.contains(text(node, "model_type"))) {
                shapes.add(node);
            }
        }
        for (int i = 0; i < shapes.size(); i++) {
            for (int j = i + 1; j < shapes.size(); j++) {
                JsonObject a = shapes.get(i);
                JsonObject b = shapes.get(j);
                if (related(a, b)) {
                    continue;
                }
                int[] ra = bounds(a);
                int[] rb = bounds(b);
                int gap = gap(ra, rb);
                if (gap < 0) {
                    add("overlap", "warning", "'" + name(a) + "' and '" + name(b) + "' overlap.",
                            "Move one of them so they no longer cover each other.", a, b);
                } else if (gap < MIN_GAP) {
                    add("crowded", "info", "'" + name(a) + "' and '" + name(b) + "' are "
                            + (gap == 0 ? "touching." : "only " + gap + "px apart."),
                            "Leave at least " + MIN_GAP + "px between shapes for readability.", a, b);
                }
            }
        }
    }

    private void duplicateNames() {
        Map<String, List<JsonObject>> byKey = new TreeMap<>();
        for (JsonObject node : nodes.values()) {
            String type = text(node, "model_type");
            String nodeName = text(node, "name");
            if (type == null || nodeName == null || nodeName.trim().isEmpty()) {
                continue;
            }
            byKey.computeIfAbsent(type + "\u0000" + nodeName.trim().toLowerCase(), k -> new ArrayList<>())
                    .add(node);
        }
        for (List<JsonObject> group : byKey.values()) {
            Set<String> models = new HashSet<>();
            for (JsonObject node : group) {
                models.add(String.valueOf(text(node, "model_id")));
            }
            if (models.size() > 1) {
                JsonObject first = group.get(0);
                add("duplicate_name", "warning", group.size() + " different " + text(first, "model_type")
                        + " elements are named '" + name(first) + "'.",
                        "Rename them apart, or show one shared model twice instead.",
                        group.toArray(new JsonObject[0]));
            }
        }
    }

    private void numbering() {
        Map<String, TreeMap<Integer, List<JsonObject>>> series = new TreeMap<>();
        for (JsonObject node : nodes.values()) {
            String nodeName = text(node, "name");
            if (nodeName == null) {
                continue;
            }
            Matcher match = NUMBERED.matcher(nodeName.trim());
            if (match.find()) {
                series.computeIfAbsent(match.group(1).toUpperCase(), k -> new TreeMap<>())
                        .computeIfAbsent(Integer.parseInt(match.group(2)), k -> new ArrayList<>())
                        .add(node);
            }
        }
        for (Map.Entry<String, TreeMap<Integer, List<JsonObject>>> entry : series.entrySet()) {
            TreeMap<Integer, List<JsonObject>> numbers = entry.getValue();
            if (numbers.size() < 3) {
                continue; // too few to call it a numbering scheme
            }
            for (Map.Entry<Integer, List<JsonObject>> number : numbers.entrySet()) {
                if (number.getValue().size() > 1) {
                    add("duplicate_number", "warning", "Number " + entry.getKey() + "-" + number.getKey()
                            + " is used " + number.getValue().size() + " times.",
                            "Give each element its own number.",
                            number.getValue().toArray(new JsonObject[0]));
                }
            }
            List<Integer> missing = new ArrayList<>();
            for (int n = numbers.firstKey(); n < numbers.lastKey(); n++) {
                if (!numbers.containsKey(n)) {
                    missing.add(n);
                }
            }
            if (!missing.isEmpty()) {
                add("numbering_gap", "info", "The " + entry.getKey() + " numbering skips " + missing + ".",
                        "Renumber, or confirm the missing numbers live on another diagram.");
            }
        }
    }

    private void relationshipEnds() {
        for (JsonElement item : array(graph, "edges")) {
            JsonObject edge = item.getAsJsonObject();
            String type = text(edge, "model_type");
            JsonObject from = nodes.get(text(edge, "from"));
            JsonObject to = nodes.get(text(edge, "to"));
            if (from == null || to == null) {
                continue;
            }
            if (text(from, "id").equals(text(to, "id"))) {
                add("self_relationship", "warning", type + " connects '" + name(from) + "' to itself.",
                        "Remove it unless the self-reference is intended.", from);
                continue;
            }
            String fromType = text(from, "model_type");
            String toType = text(to, "model_type");
            if (("Include".equals(type) || "Extend".equals(type))
                    && !("UseCase".equals(fromType) && "UseCase".equals(toType))) {
                add("include_extend_ends", "error", "«" + type.toLowerCase() + "» between '" + name(from)
                        + "' (" + fromType + ") and '" + name(to) + "' (" + toType + ").",
                        "Include and extend only relate two use cases; use an association for actors.",
                        from, to);
            }
            if ("Association".equals(type) && "Actor".equals(fromType) && "Actor".equals(toType)) {
                add("actor_to_actor_association", "warning", "Association between actors '" + name(from)
                        + "' and '" + name(to) + "'.",
                        "Actors usually relate by generalization, not association.", from, to);
            }
        }
    }

    private void documentation() {
        List<JsonObject> missing = new ArrayList<>();
        for (JsonObject node : nodes.values()) {
            if (DOCUMENTED_TYPES.contains(text(node, "model_type"))) {
                String doc = text(node, "documentation");
                if (doc == null || doc.trim().isEmpty()) {
                    missing.add(node);
                }
            }
        }
        if (!missing.isEmpty()) {
            add("missing_documentation", "info", missing.size() + " element(s) have no documentation.",
                    "Add a one-line description to each.", missing.toArray(new JsonObject[0]));
        }
    }

    // --- report ------------------------------------------------------------

    private JsonObject report(boolean includeInfo) {
        JsonArray kept = new JsonArray();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("error", 0);
        counts.put("warning", 0);
        counts.put("info", 0);
        for (JsonElement item : findings) {
            String severity = text(item.getAsJsonObject(), "severity");
            counts.merge(severity, 1, Integer::sum);
            if (includeInfo || !"info".equals(severity)) {
                kept.add(item);
            }
        }
        JsonObject summary = new JsonObject();
        counts.forEach(summary::addProperty);
        JsonObject result = new JsonObject();
        JsonObject diagram = graph.has("diagram") && graph.get("diagram").isJsonObject()
                ? graph.getAsJsonObject("diagram") : new JsonObject();
        JsonObject header = new JsonObject();
        header.add("id", diagram.get("id"));
        header.add("name", diagram.get("name"));
        header.add("type", diagram.get("type"));
        result.add("diagram", header);
        result.add("summary", summary);
        result.add("findings", kept);
        return result;
    }

    private void add(String rule, String severity, String message, String suggestion, JsonObject... involved) {
        JsonObject finding = new JsonObject();
        finding.addProperty("rule", rule);
        finding.addProperty("severity", severity);
        finding.addProperty("message", message);
        finding.addProperty("suggestion", suggestion);
        JsonArray elements = new JsonArray();
        for (JsonObject node : involved) {
            JsonObject ref = new JsonObject();
            ref.addProperty("view_id", text(node, "id"));
            ref.addProperty("model_id", text(node, "model_id"));
            ref.addProperty("name", text(node, "name"));
            elements.add(ref);
        }
        finding.add("elements", elements);
        findings.add(finding);
    }

    // --- geometry and helpers ------------------------------------------------

    /** True when one shape is an ancestor of the other (containers and their contents). */
    private boolean related(JsonObject a, JsonObject b) {
        return isAncestor(text(a, "id"), b) || isAncestor(text(b, "id"), a);
    }

    private boolean isAncestor(String ancestorId, JsonObject node) {
        Set<String> seen = new HashSet<>();
        String parent = text(node, "parent_id");
        while (parent != null && seen.add(parent)) {
            if (parent.equals(ancestorId)) {
                return true;
            }
            JsonObject next = nodes.get(parent);
            parent = next == null ? null : text(next, "parent_id");
        }
        return false;
    }

    /** {x, y, width, height}, or null when the node has no usable bounds. */
    static int[] bounds(JsonObject node) {
        JsonElement raw = node.get("bounds");
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject b = raw.getAsJsonObject();
        if (!b.has("x") || !b.has("y") || !b.has("width") || !b.has("height")) {
            return null;
        }
        int[] r = {b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("width").getAsInt(),
                b.get("height").getAsInt()};
        return r[2] > 0 && r[3] > 0 ? r : null;
    }

    static boolean contains(int[] outer, int[] inner) {
        return inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[0] + inner[2] <= outer[0] + outer[2]
                && inner[1] + inner[3] <= outer[1] + outer[3];
    }

    /** Distance between two rectangles; negative when they overlap, 0 when touching. */
    static int gap(int[] a, int[] b) {
        int dx = Math.max(b[0] - (a[0] + a[2]), a[0] - (b[0] + b[2]));
        int dy = Math.max(b[1] - (a[1] + a[3]), a[1] - (b[1] + b[3]));
        if (dx < 0 && dy < 0) {
            return Math.max(dx, dy); // overlapping: how deep, as a negative number
        }
        return Math.max(dx, dy);
    }

    private static String name(JsonObject node) {
        String value = text(node, "name");
        return value == null || value.trim().isEmpty() ? text(node, "id") : value;
    }

    private static String text(JsonObject holder, String field) {
        JsonElement value = holder == null ? null : holder.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static JsonArray array(JsonObject holder, String field) {
        JsonElement value = holder.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
