package vpmcp.lint;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import vpmcp.layout.Geometry;
import vpmcp.layout.StyleProfile;
import vpmcp.layout.TextMeasure;

/**
 * Layout rules (ADR-0038): what makes a diagram look untidy, measured against
 * a style profile. Works on extractor graphs, like DiagramLinter, and emits
 * findings in the same shape. Pure: text widths come from an injected
 * measure. Also computes a 0-100 layout score from all layout findings.
 */
public final class LayoutLinter {

    /** Rules whose findings count towards the layout score. */
    public static final Set<String> LAYOUT_RULES = Set.of(
            "overlap", "crowded", "outside_container", "off_grid", "inconsistent_size",
            "name_truncated", "name_wraps", "line_crossing", "line_through_shape", "label_overlap",
            "label_misplaced", "misaligned", "uneven_spacing", "actor_wrong_side", "boundary_padding", "container_slack");

    private static final Set<String> CONTAINER_TYPES = Set.of("System", "Package", "ActivitySwimlane2");
    private static final Set<String> PART_SHAPES = Set.of("ActivityPartitionHeader", "ActivitySwimlane2Compartment");
    private static final Set<String> NAMED_BOXES = Set.of("UseCase", "Activity");
    /**
     * VP's inner margin before a caption wraps. Calibrated live on WashWatch:
     * every one-line use case name had at least 12px spare, the one that
     * wrapped had -22px; 10 keeps a safety margin against false alarms.
     */
    private static final Map<String, Integer> WRAP_MARGIN = Map.of("UseCase", 10, "Activity", 10);

    private final StyleProfile profile;
    private final TextMeasure measure;
    private final Map<String, JsonObject> nodes = new LinkedHashMap<>();
    private final List<JsonObject> edges = new ArrayList<>();
    private final List<JsonObject> findings = new ArrayList<>();

    private LayoutLinter(JsonObject graph, StyleProfile profile, TextMeasure measure) {
        this.profile = profile;
        this.measure = measure;
        for (JsonElement item : array(graph, "nodes")) {
            JsonObject node = item.getAsJsonObject();
            if (text(node, "id") != null) {
                nodes.put(text(node, "id"), node);
            }
        }
        for (JsonElement item : array(graph, "edges")) {
            edges.add(item.getAsJsonObject());
        }
    }

    /** Runs every layout rule; findings are in DiagramLinter's format. */
    public static List<JsonObject> run(JsonObject graph, StyleProfile profile, TextMeasure measure) {
        LayoutLinter linter = new LayoutLinter(graph, profile, measure);
        linter.offGrid();
        linter.sizes();
        linter.names();
        linter.lines();
        linter.labels();
        linter.connectorLabels();
        linter.alignment();
        linter.actorSides();
        linter.padding();
        linter.slack();
        return linter.findings;
    }

    /**
     * 0-100: 100 when no layout finding, falling smoothly with weighted
     * findings, normalized by diagram size so big diagrams are not punished
     * for having more shapes. Monotonic: every extra finding lowers it.
     */
    public static JsonObject score(JsonArray allFindings, int shapeCount, StyleProfile profile) {
        int penalty = 0;
        Map<String, Integer> byRule = new TreeMap<>();
        for (JsonElement item : allFindings) {
            JsonObject finding = item.getAsJsonObject();
            String rule = text(finding, "rule");
            if (rule == null || !LAYOUT_RULES.contains(rule)) {
                continue;
            }
            int weight = profile.scoreWeight(text(finding, "severity"));
            penalty += weight;
            byRule.merge(rule, weight, Integer::sum);
        }
        double scale = 10 + shapeCount;
        JsonObject out = new JsonObject();
        out.addProperty("score", (int) Math.round(100 * scale / (scale + penalty)));
        out.addProperty("penalty", penalty);
        out.addProperty("shapes", shapeCount);
        JsonObject rules = new JsonObject();
        byRule.forEach(rules::addProperty);
        out.add("penalty_by_rule", rules);
        out.addProperty("profile", profile.name());
        return out;
    }

    /** Shapes the score is normalized by: real elements, not containers or swimlane parts. */
    public static int shapeCount(JsonObject graph) {
        int count = 0;
        for (JsonElement item : array(graph, "nodes")) {
            JsonObject node = item.getAsJsonObject();
            if (DiagramLinter.bounds(node) != null && !isPart(node) && !isContainer(node)) {
                count++;
            }
        }
        return count;
    }

    // ---- rules -----------------------------------------------------------------

    private void offGrid() {
        int grid = profile.grid();
        List<JsonObject> off = new ArrayList<>();
        for (JsonObject node : elements(true)) {
            int[] r = DiagramLinter.bounds(node);
            if (!Geometry.onGrid(r[0], grid) || !Geometry.onGrid(r[1], grid)) {
                off.add(node);
            }
        }
        if (!off.isEmpty()) {
            add("off_grid", "info", off.size() + " shape(s) are not on the " + grid + "px grid.",
                    "Snap x/y to multiples of " + grid + " so edges line up.", off);
        }
    }

    private void sizes() {
        Map<String, List<JsonObject>> wrongByType = new TreeMap<>();
        for (JsonObject node : elements(false)) {
            String type = text(node, "model_type");
            int[] r = DiagramLinter.bounds(node);
            boolean wrong = profile.fixes(type, "height") && r[3] != profile.size(type, "height", r[3])
                    || profile.fixes(type, "width") && r[2] != profile.size(type, "width", r[2]);
            if (wrong) {
                wrongByType.computeIfAbsent(type, k -> new ArrayList<>()).add(node);
            }
        }
        wrongByType.forEach((type, list) -> add("inconsistent_size", "info",
                list.size() + " " + type + " shape(s) differ from the profile size ("
                        + describeSize(type) + ").",
                "Resize them to " + describeSize(type) + " so same kinds look the same.", list));
    }

    private void names() {
        int lineHeight = profile.lineHeight();
        for (JsonObject node : elements(false)) {
            String type = text(node, "model_type");
            String name = text(node, "name");
            if (!has(NAMED_BOXES, type) || name == null || name.trim().isEmpty()) {
                continue;
            }
            int[] r = DiagramLinter.bounds(node);
            int lines = TextMeasure.wrap(measure, name, r[2] - WRAP_MARGIN.getOrDefault(type, 10)).size();
            int ideal = idealWidth(type, name);
            if (lines * lineHeight > r[3] - 4) {
                add("name_truncated", "warning", "'" + name + "' does not fit its shape (" + r[2] + "×" + r[3]
                        + ") and is cut off.", "Widen it to " + ideal + "px.", node);
            } else if (lines > 1) {
                add("name_wraps", "info", "'" + name + "' wraps onto " + lines + " lines.",
                        "Widen it to " + ideal + "px for a single line.", node);
            }
        }
    }

    private void lines() {
        List<List<int[]>> paths = new ArrayList<>();
        for (JsonObject edge : edges) {
            paths.add(path(edge));
        }
        List<JsonObject> obstacles = new ArrayList<>();
        for (JsonObject node : elements(false)) {
            obstacles.add(node);
        }
        for (int i = 0; i < edges.size(); i++) {
            List<int[]> a = paths.get(i);
            if (a == null) {
                continue;
            }
            for (int j = i + 1; j < edges.size(); j++) {
                List<int[]> b = paths.get(j);
                if (b != null && !sharesEnd(edges.get(i), edges.get(j)) && Geometry.polylinesCross(a, b)) {
                    add("line_crossing", "info", "Two connectors cross (" + label(edges.get(i)) + " and "
                            + label(edges.get(j)) + ").", "Reorder or move the shapes, or route one line around.",
                            edgeRef(edges.get(i)), edgeRef(edges.get(j)));
                }
            }
            for (JsonObject shape : obstacles) {
                String id = text(shape, "id");
                if (id.equals(text(edges.get(i), "from")) || id.equals(text(edges.get(i), "to"))) {
                    continue;
                }
                if (Geometry.polylineThroughBox(a, DiagramLinter.bounds(shape), 3)) {
                    add("line_through_shape", "warning", "The " + label(edges.get(i)) + " connector passes through '"
                            + name(shape) + "'.", "Move the shape or route the line around it.",
                            edgeRef(edges.get(i)), shape);
                }
            }
        }
    }

    /** A line's label further than this from its own line reads as belonging to nothing. */
    static final int LABEL_REACH = 20;

    /**
     * Connector labels that sit away from their own line, or nearer another
     * line than their own (live WashWatch run: after a re-route the «include»
     * and «extend» labels each sat beside the other's line).
     */
    private void connectorLabels() {
        List<List<int[]>> paths = new ArrayList<>();
        for (JsonObject edge : edges) {
            paths.add(path(edge));
        }
        for (int i = 0; i < edges.size(); i++) {
            int[] box = edgeCaptionBox(edges.get(i));
            List<int[]> own = paths.get(i);
            if (box == null || own == null) {
                continue;
            }
            double mine = Geometry.boxToPolyline(box, own);
            JsonObject nearer = null;
            for (int j = 0; j < edges.size() && nearer == null; j++) {
                if (j != i && paths.get(j) != null && Geometry.boxToPolyline(box, paths.get(j)) + 5 < mine) {
                    nearer = edges.get(j);
                }
            }
            if (mine > LABEL_REACH || nearer != null) {
                String what = label(edges.get(i));
                add("label_misplaced", "warning", "The label of the " + what + " connector "
                        + (nearer != null ? "sits nearer the " + label(nearer) + " connector."
                                : "sits " + (int) mine + "px away from its line."),
                        "Move the label next to its own line (route_connector label).", edgeRef(edges.get(i)));
            }
        }
    }

    /** Absolute caption box of a connector that shows text; null otherwise. */
    private static int[] edgeCaptionBox(JsonObject edge) {
        JsonElement visual = edge.get("visual_properties");
        if (visual == null || !visual.isJsonObject()) {
            return null;
        }
        JsonElement caption = visual.getAsJsonObject().get("caption");
        if (caption == null || !caption.isJsonObject() || !caption.getAsJsonObject().has("bounds")) {
            return null;
        }
        JsonObject c = caption.getAsJsonObject();
        JsonElement visible = c.get("is_visible");
        if (visible != null && visible.isJsonPrimitive() && !visible.getAsBoolean()) {
            return null;
        }
        JsonObject b = c.getAsJsonObject("bounds");
        int[] r = {b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("width").getAsInt(), b.get("height").getAsInt()};
        return r[2] > 0 && r[3] > 0 ? r : null;
    }

    private void labels() {
        List<JsonObject> shapes = elements(false);
        for (JsonObject actor : shapes) {
            if (!"Actor".equals(text(actor, "model_type"))) {
                continue;
            }
            int[] caption = captionBox(actor);
            if (caption == null) {
                continue;
            }
            for (JsonObject other : shapes) {
                if (other == actor) {
                    continue;
                }
                int[] box = DiagramLinter.bounds(other);
                int[] otherCaption = "Actor".equals(text(other, "model_type")) ? captionBox(other) : null;
                boolean hit = Geometry.intersects(caption, box)
                        || otherCaption != null && text(actor, "id").compareTo(text(other, "id")) < 0
                        && Geometry.intersects(caption, otherCaption);
                if (hit) {
                    add("label_overlap", "warning", "The name of actor '" + name(actor) + "' overlaps '"
                            + name(other) + "'.", "Move them apart so the name is readable.", actor, other);
                }
            }
        }
    }

    /** Near-miss columns and rows, and uneven gaps within aligned columns. */
    private void alignment() {
        int tolerance = profile.spacing("align_tolerance", 8);
        Map<String, List<JsonObject>> byParentAndType = new TreeMap<>();
        for (JsonObject node : elements(false)) {
            String key = text(node, "parent_id") + "|" + text(node, "model_type");
            byParentAndType.computeIfAbsent(key, k -> new ArrayList<>()).add(node);
        }
        for (List<JsonObject> group : byParentAndType.values()) {
            for (List<JsonObject> column : clusters(group, true, tolerance)) {
                if (distinct(column, true) > 1) {
                    add("misaligned", "info", column.size() + " shapes almost line up in a column but not exactly.",
                            "Give them the same centre x.", column);
                }
                if (column.size() >= 3) {
                    unevenGaps(column);
                }
            }
            for (List<JsonObject> row : clusters(group, false, tolerance)) {
                if (distinct(row, false) > 1) {
                    add("misaligned", "info", row.size() + " shapes almost line up in a row but not exactly.",
                            "Give them the same centre y.", row);
                }
            }
        }
    }

    private void unevenGaps(List<JsonObject> column) {
        List<JsonObject> sorted = new ArrayList<>(column);
        sorted.sort(Comparator.comparingInt(n -> DiagramLinter.bounds(n)[1]));
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            int[] a = DiagramLinter.bounds(sorted.get(i));
            int[] b = DiagramLinter.bounds(sorted.get(i + 1));
            int gap = b[1] - (a[1] + a[3]);
            min = Math.min(min, gap);
            max = Math.max(max, gap);
        }
        if (max - min > profile.spacing("spacing_tolerance", 10)) {
            add("uneven_spacing", "info", "Gaps in a column of " + sorted.size() + " shapes vary from " + min
                    + "px to " + max + "px.", "Space them evenly (" + profile.spacing("sibling_gap", 20)
                    + "px is the profile gap).", sorted);
        }
    }

    private void actorSides() {
        if (!"left_right".equals(profile.actorSides())) {
            return;
        }
        for (JsonObject boundary : nodes.values()) {
            if (!"System".equals(text(boundary, "model_type")) || DiagramLinter.bounds(boundary) == null) {
                continue;
            }
            int[] box = DiagramLinter.bounds(boundary);
            for (JsonObject actor : nodes.values()) {
                int[] r = DiagramLinter.bounds(actor);
                if (!"Actor".equals(text(actor, "model_type")) || r == null) {
                    continue;
                }
                int cx = Geometry.centerX(r);
                if (cx > box[0] && cx < box[0] + box[2]) {
                    add("actor_wrong_side", "info", "Actor '" + name(actor) + "' is above, below or inside system '"
                            + name(boundary) + "' instead of beside it.",
                            "Place actors to the left or right of the system boundary.", actor, boundary);
                }
            }
        }
    }

    private void padding() {
        int pad = profile.spacing("container_padding", 20);
        Map<String, List<JsonObject>> tight = new LinkedHashMap<>();
        for (JsonObject node : elements(false)) {
            JsonObject parent = nodes.get(text(node, "parent_id"));
            int[] box = parent == null ? null : DiagramLinter.bounds(parent);
            if (box == null || !isContainer(parent) || "ActivitySwimlane2".equals(text(parent, "model_type"))) {
                continue;
            }
            int inset = Geometry.insetWithin(box, DiagramLinter.bounds(node));
            if (inset >= 0 && inset < pad) {
                tight.computeIfAbsent(text(parent, "id"), k -> new ArrayList<>()).add(node);
            }
        }
        tight.forEach((parentId, list) -> {
            List<JsonObject> involved = new ArrayList<>(list);
            involved.add(nodes.get(parentId));
            add("boundary_padding", "info", list.size() + " shape(s) sit closer than " + pad + "px to the edge of '"
                    + name(nodes.get(parentId)) + "'.", "Keep " + pad + "px between contents and the boundary.",
                    involved);
        });
    }

    /**
     * A boundary far bigger than what it holds wastes space and pushes actors
     * away from their use cases. Reports the slack per side and the size that
     * would fit the contents with the profile padding.
     */
    private void slack() {
        int pad = profile.spacing("container_padding", 20);
        int limit = profile.spacing("container_slack", pad * 4);
        for (JsonObject container : nodes.values()) {
            if (!"System".equals(text(container, "model_type")) && !"Package".equals(text(container, "model_type"))) {
                continue;
            }
            int[] box = DiagramLinter.bounds(container);
            int[] content = null;
            for (JsonObject child : nodes.values()) {
                int[] r = DiagramLinter.bounds(child);
                if (r == null || !text(container, "id").equals(text(child, "parent_id"))) {
                    continue;
                }
                content = content == null ? r.clone() : union(content, r);
            }
            if (box == null || content == null) {
                continue;
            }
            int left = content[0] - box[0];
            int top = content[1] - box[1];
            int right = box[0] + box[2] - (content[0] + content[2]);
            int bottom = box[1] + box[3] - (content[1] + content[3]);
            if (Math.max(Math.max(left, right), Math.max(top, bottom)) > limit) {
                int fitW = Geometry.ceilToGrid(content[2] + 2 * pad, profile.grid());
                int fitH = Geometry.ceilToGrid(content[3] + 2 * pad, profile.grid());
                add("container_slack", "info", "'" + name(container) + "' has empty space around its contents "
                        + "(left " + left + ", top " + top + ", right " + right + ", bottom " + bottom + " px).",
                        "Shrink it to about " + fitW + "×" + fitH + " around its contents.", container);
            }
        }
    }

    private static int[] union(int[] a, int[] b) {
        int x = Math.min(a[0], b[0]);
        int y = Math.min(a[1], b[1]);
        int r = Math.max(a[0] + a[2], b[0] + b[2]);
        int bottom = Math.max(a[1] + a[3], b[1] + b[3]);
        return new int[] {x, y, r - x, bottom - y};
    }

    // ---- helpers -------------------------------------------------------------

    /** Real elements with bounds (no swimlane parts); containers included only when asked. */
    private List<JsonObject> elements(boolean includeContainers) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject node : nodes.values()) {
            if (DiagramLinter.bounds(node) == null || isPart(node)) {
                continue;
            }
            if (!includeContainers && isContainer(node)) {
                continue;
            }
            out.add(node);
        }
        return out;
    }

    private static boolean isPart(JsonObject node) {
        return has(PART_SHAPES, text(node, "shape_type")) || text(node, "model_type") == null;
    }

    private static boolean isContainer(JsonObject node) {
        return has(CONTAINER_TYPES, text(node, "model_type")) || array(node, "child_ids").size() > 0;
    }

    /** Greedy 1-D clusters by centre x (columns) or centre y (rows), stacked along the other axis. */
    private static List<List<JsonObject>> clusters(List<JsonObject> group, boolean columns, int tolerance) {
        List<JsonObject> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingInt(n -> centre(n, columns)));
        List<List<JsonObject>> out = new ArrayList<>();
        List<JsonObject> current = new ArrayList<>();
        for (JsonObject node : sorted) {
            if (!current.isEmpty() && centre(node, columns) - centre(current.get(0), columns) > tolerance) {
                if (current.size() > 1) {
                    out.add(current);
                }
                current = new ArrayList<>();
            }
            current.add(node);
        }
        if (current.size() > 1) {
            out.add(current);
        }
        return out;
    }

    private static int centre(JsonObject node, boolean x) {
        int[] r = DiagramLinter.bounds(node);
        return x ? Geometry.centerX(r) : Geometry.centerY(r);
    }

    private static int distinct(List<JsonObject> cluster, boolean x) {
        Set<Integer> values = new HashSet<>();
        for (JsonObject node : cluster) {
            values.add(centre(node, x));
        }
        return values.size();
    }

    private int idealWidth(String type, String name) {
        int width = measure.width(name) + profile.size(type, "text_padding", 20);
        return Geometry.ceilToGrid(Math.max(width, profile.size(type, "min_width", 60)), profile.grid());
    }

    private String describeSize(String type) {
        String w = profile.fixes(type, "width") ? String.valueOf(profile.size(type, "width", 0)) : "any";
        String h = profile.fixes(type, "height") ? String.valueOf(profile.size(type, "height", 0)) : "any";
        return "width " + w + ", height " + h;
    }

    /** Waypoints when present, else a straight line between the end shapes' centres; null when unknown. */
    private List<int[]> path(JsonObject edge) {
        List<int[]> points = new ArrayList<>();
        for (JsonElement p : array(edge, "waypoints")) {
            JsonObject point = p.getAsJsonObject();
            points.add(new int[] {point.get("x").getAsInt(), point.get("y").getAsInt()});
        }
        if (points.size() >= 2) {
            return points;
        }
        int[] from = DiagramLinter.bounds(nodes.get(text(edge, "from")) == null ? new JsonObject()
                : nodes.get(text(edge, "from")));
        int[] to = DiagramLinter.bounds(nodes.get(text(edge, "to")) == null ? new JsonObject()
                : nodes.get(text(edge, "to")));
        if (from == null || to == null) {
            return null;
        }
        List<int[]> straight = new ArrayList<>();
        straight.add(new int[] {Geometry.centerX(from), Geometry.centerY(from)});
        straight.add(new int[] {Geometry.centerX(to), Geometry.centerY(to)});
        return straight;
    }

    private static boolean sharesEnd(JsonObject a, JsonObject b) {
        Set<String> ends = new HashSet<>();
        ends.add(text(a, "from"));
        ends.add(text(a, "to"));
        return ends.contains(text(b, "from")) || ends.contains(text(b, "to"));
    }

    /** Absolute caption box of an actor (south captions are reported in diagram coordinates). */
    private static int[] captionBox(JsonObject actor) {
        JsonElement visual = actor.get("visual_properties");
        if (visual == null || !visual.isJsonObject()) {
            return null;
        }
        JsonElement caption = visual.getAsJsonObject().get("caption");
        if (caption == null || !caption.isJsonObject()) {
            return null;
        }
        JsonObject c = caption.getAsJsonObject();
        if (!"south".equals(text(c, "side")) || !c.has("bounds")) {
            return null;
        }
        JsonObject b = c.getAsJsonObject("bounds");
        int[] r = {b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("width").getAsInt(), b.get("height").getAsInt()};
        return r[2] > 0 && r[3] > 0 ? r : null;
    }

    private String label(JsonObject edge) {
        return name(nodes.getOrDefault(text(edge, "from"), new JsonObject())) + " → "
                + name(nodes.getOrDefault(text(edge, "to"), new JsonObject()));
    }

    private static JsonObject edgeRef(JsonObject edge) {
        JsonObject ref = new JsonObject();
        ref.addProperty("id", text(edge, "id"));
        ref.addProperty("model_id", text(edge, "model_id"));
        ref.addProperty("name", text(edge, "model_type"));
        return ref;
    }

    private void add(String rule, String severity, String message, String suggestion, List<JsonObject> involved) {
        add(rule, severity, message, suggestion, involved.toArray(new JsonObject[0]));
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

    private static String name(JsonObject node) {
        String value = text(node, "name");
        return value == null || value.trim().isEmpty() ? String.valueOf(text(node, "id")) : value;
    }

    private static String text(JsonObject holder, String field) {
        JsonElement value = holder == null ? null : holder.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static JsonArray array(JsonObject holder, String field) {
        JsonElement value = holder == null ? null : holder.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    /** Null-safe membership (Set.of rejects null lookups; swimlane parts have no model type). */
    static boolean has(Set<String> set, String value) {
        return value != null && set.contains(value);
    }
}
