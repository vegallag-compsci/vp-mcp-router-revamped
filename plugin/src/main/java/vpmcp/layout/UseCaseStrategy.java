package vpmcp.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lays out a use case diagram by meaning (ADR-0039). Pure: input is an
 * extractor graph, output is target geometry per view and a straight path
 * per connector, plus the actor classification it used. Deterministic: the
 * same graph and profile always give the same result.
 *
 * Rules (course convention, profile-tunable):
 * - Actors: people on the left, external systems on the right. An explicit
 *   side (by actor name or view id) always wins; otherwise a system keyword
 *   in the stereotype or the name decides (default: person), and the reason
 *   is reported.
 * - Use cases: ordered by number, grouped by the actor they belong to;
 *   «include»/«extend» partners placed right after their base. More than
 *   {@code max_column} use cases (or any tied only to right-side actors)
 *   split into two columns, right-actor use cases on the right.
 * - Sizes: width = measured name + text padding on the grid, equal within a
 *   column; profile height (taller shapes such as extension points keep
 *   their height, rounded to the grid). Actors use the profile size.
 * - Boundary: fits its contents plus padding and a title band.
 * - Actors sit level with the middle of their use cases, never colliding.
 * - Connectors: straight, from shape edge to shape edge.
 */
public final class UseCaseStrategy {

    private static final Pattern NUMBERED = Pattern.compile("^([A-Za-z]{1,6})[-_ ]?(\\d{1,4})\\b");
    private static final Pattern SYSTEM_WORDS = Pattern.compile(
            "\\b(service|system|sensor|scheduler|timer|clock|gateway|server|api|device|database|external"
            + "|bot|cron|job|function|platform|hardware|esp32|firebase|cloud)s?\\b");

    /** Target geometry for one view. */
    public static final class Placement {
        public final String viewId;
        public final String name;
        public final String modelType;
        public final int[] before;
        public final int[] after;

        Placement(String viewId, String name, String modelType, int[] before, int[] after) {
            this.viewId = viewId;
            this.name = name;
            this.modelType = modelType;
            this.before = before;
            this.after = after;
        }

        public boolean changes() {
            return !java.util.Arrays.equals(before, after);
        }
    }

    /** A connector's new path, and where its label goes (null when it shows none). */
    public static final class Route {
        public final String edgeId;
        public final List<int[]> points;
        public final int[] label;

        Route(String edgeId, List<int[]> points, int[] label) {
            this.edgeId = edgeId;
            this.points = points;
            this.label = label;
        }
    }

    /** Space between a connector and its label. */
    static final int LABEL_GAP = 4;

    /** Everything the strategy decided. */
    public static final class Result {
        public final List<Placement> placements = new ArrayList<>();
        public final List<Route> routes = new ArrayList<>();
        public final JsonArray actorSides = new JsonArray();
        public final JsonArray notes = new JsonArray();
    }

    private final StyleProfile profile;
    private final TextMeasure measure;
    private final Map<String, String> sideOverrides;
    private final Map<String, JsonObject> nodes = new LinkedHashMap<>();
    private final List<JsonObject> edges = new ArrayList<>();

    private UseCaseStrategy(JsonObject graph, StyleProfile profile, TextMeasure measure,
            Map<String, String> sideOverrides) {
        this.profile = profile;
        this.measure = measure;
        this.sideOverrides = sideOverrides == null ? Map.of() : sideOverrides;
        for (JsonElement item : array(graph, "nodes")) {
            JsonObject node = item.getAsJsonObject();
            if (text(node, "id") != null && bounds(node) != null) {
                nodes.put(text(node, "id"), node);
            }
        }
        for (JsonElement item : array(graph, "edges")) {
            edges.add(item.getAsJsonObject());
        }
    }

    /**
     * @param sideOverrides actor name or view id → "left" / "right"; may be null
     */
    public static Result plan(JsonObject graph, StyleProfile profile, TextMeasure measure,
            Map<String, String> sideOverrides) {
        return new UseCaseStrategy(graph, profile, measure, sideOverrides).run();
    }

    private Result run() {
        Result result = new Result();
        int grid = profile.grid();
        int gap = profile.spacing("sibling_gap", 20);
        int pad = profile.spacing("container_padding", 20);
        int titleBand = profile.spacing("title_band", 30);
        int columnGap = profile.spacing("column_gap", 40);
        int actorGap = profile.spacing("actor_gap", 80);
        int margin = profile.spacing("margin", 40);
        int maxColumn = profile.spacing("max_column", 8);
        int actorW = profile.size("Actor", "width", 30);
        int actorH = profile.size("Actor", "height", 60);

        JsonObject boundary = null;
        List<JsonObject> actors = new ArrayList<>();
        List<JsonObject> useCases = new ArrayList<>();
        for (JsonObject node : nodes.values()) {
            String type = text(node, "model_type");
            if ("System".equals(type) && (boundary == null || childCount(node) > childCount(boundary))) {
                boundary = node;
            } else if ("Actor".equals(type)) {
                actors.add(node);
            } else if ("UseCase".equals(type)) {
                useCases.add(node);
            }
        }
        useCases.sort(Comparator.comparing(this::orderKey));

        // --- actors: sides -------------------------------------------------------
        Map<String, Boolean> right = new HashMap<>();
        List<JsonObject> leftActors = new ArrayList<>();
        List<JsonObject> rightActors = new ArrayList<>();
        actors.sort(Comparator.comparingInt((JsonObject a) -> bounds(a)[1]).thenComparing(a -> text(a, "name")));
        for (JsonObject actor : actors) {
            String[] decision = side(actor);
            boolean isRight = "right".equals(decision[0]);
            right.put(text(actor, "id"), isRight);
            (isRight ? rightActors : leftActors).add(actor);
            JsonObject entry = new JsonObject();
            entry.addProperty("view_id", text(actor, "id"));
            entry.addProperty("name", text(actor, "name"));
            entry.addProperty("side", decision[0]);
            entry.addProperty("reason", decision[1]);
            result.actorSides.add(entry);
        }

        // --- use cases: anchors, partners, columns ---------------------------------
        Map<String, List<String>> actorsOf = new HashMap<>();
        Map<String, List<String>> partners = new HashMap<>();
        for (JsonObject edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            JsonObject a = nodes.get(from);
            JsonObject b = nodes.get(to);
            if (a == null || b == null) {
                continue;
            }
            String ta = text(a, "model_type");
            String tb = text(b, "model_type");
            if ("Actor".equals(ta) && "UseCase".equals(tb)) {
                actorsOf.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            } else if ("UseCase".equals(ta) && "Actor".equals(tb)) {
                actorsOf.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            } else if ("UseCase".equals(ta) && "UseCase".equals(tb)) {
                partners.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                partners.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }
        List<String> leftCol = new ArrayList<>();
        List<String> rightCol = new ArrayList<>();
        List<String> loose = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        // anchored use cases, in actor order then number order
        for (List<JsonObject> side : List.of(leftActors, rightActors)) {
            for (JsonObject actor : side) {
                for (JsonObject uc : useCases) {
                    String id = text(uc, "id");
                    List<String> owners = actorsOf.getOrDefault(id, List.of());
                    if (placed.contains(id) || owners.isEmpty() || !owners.contains(text(actor, "id"))) {
                        continue;
                    }
                    boolean anyLeft = owners.stream().anyMatch(o -> !right.getOrDefault(o, false));
                    List<String> column = anyLeft ? leftCol : rightCol;
                    addWithPartners(id, column, placed, partners, useCases);
                }
            }
        }
        for (JsonObject uc : useCases) {
            if (!placed.contains(text(uc, "id"))) {
                loose.add(text(uc, "id"));
            }
        }
        boolean twoColumns = !rightCol.isEmpty() || useCases.size() > maxColumn;
        int target = twoColumns ? (useCases.size() + 1) / 2 : useCases.size();
        for (String id : loose) {
            if (placed.contains(id)) {
                continue;
            }
            // Fill the left column up to half, then the right: number order is kept within each.
            List<String> column = !twoColumns || leftCol.size() < target ? leftCol : rightCol;
            addWithPartners(id, column, placed, partners, useCases);
        }

        // --- sizes -------------------------------------------------------------------
        int ucH = profile.size("UseCase", "height", 40);
        int leftW = columnWidth(leftCol);
        int rightW = rightCol.isEmpty() ? 0 : columnWidth(rightCol);

        // --- horizontal frame ----------------------------------------------------------
        int leftCaption = maxCaption(leftActors);
        int rightCaption = maxCaption(rightActors);
        int leftActorCenter = margin + Math.max(actorW, leftCaption) / 2;
        int boxX = Geometry.ceilToGrid(leftActors.isEmpty() ? margin
                : leftActorCenter + Math.max(actorW, leftCaption) / 2 + actorGap, grid);
        // A labelled bracket to the right of a column needs room before the next column
        // (live WashWatch run: a 40px gap left the «extend» label nowhere to go).
        int leftRoom = detourRoom(leftCol);
        int rightRoom = detourRoom(rightCol);
        if (!rightCol.isEmpty()) {
            columnGap = Math.max(columnGap, leftRoom);
        }
        int colLeftX = boxX + pad;
        int colRightX = colLeftX + leftW + columnGap;
        int boxW = pad + leftW + (rightCol.isEmpty() ? 0 : columnGap + rightW)
                + Math.max(pad, rightCol.isEmpty() ? leftRoom : rightRoom);
        boxW = Geometry.ceilToGrid(boxW, grid);
        int boxY = margin;
        int firstRow = Geometry.ceilToGrid(boxY + titleBand + pad / 2, grid);

        Map<String, int[]> target2 = new LinkedHashMap<>();
        int bottom = layoutColumn(leftCol, colLeftX, leftW, firstRow, ucH, gap, target2);
        if (!rightCol.isEmpty()) {
            bottom = Math.max(bottom, layoutColumn(rightCol, colRightX, rightW, firstRow, ucH, gap, target2));
        }
        int boxH = Geometry.ceilToGrid(bottom + pad - boxY, grid);
        int rightActorCenter = boxX + boxW + actorGap + Math.max(actorW, rightCaption) / 2;

        // --- actors: vertical placement ------------------------------------------------
        placeActors(leftActors, leftActorCenter, actorsOf, target2, boxY, boxH, actorW, actorH, grid);
        placeActors(rightActors, rightActorCenter, actorsOf, target2, boxY, boxH, actorW, actorH, grid);

        if (boundary != null) {
            target2.put(text(boundary, "id"), new int[] {boxX, boxY, boxW, boxH});
        } else if (!useCases.isEmpty()) {
            result.notes.add("No System boundary on this diagram: use cases are arranged without one.");
        }

        for (Map.Entry<String, int[]> entry : target2.entrySet()) {
            JsonObject node = nodes.get(entry.getKey());
            result.placements.add(new Placement(entry.getKey(), text(node, "name"), text(node, "model_type"),
                    bounds(node), entry.getValue()));
        }

        // --- connectors ----------------------------------------------------------------
        int detours = 0;
        for (JsonObject edge : edges) {
            int[] a = target2.get(text(edge, "from"));
            int[] b = target2.get(text(edge, "to"));
            if (a == null || b == null) {
                continue;
            }
            if (a[0] == b[0] && a[2] == b[2] && blockedBetween(a, b, target2, text(edge, "from"), text(edge, "to"))) {
                // Same column with shapes in between: a bracket out to the right of the column.
                int out = a[0] + a[2] + profile.spacing("detour_offset", 20) + 10 * detours++;
                List<int[]> path = new ArrayList<>();
                path.add(new int[] {a[0] + a[2], Geometry.centerY(a)});
                path.add(new int[] {out, Geometry.centerY(a)});
                path.add(new int[] {out, Geometry.centerY(b)});
                path.add(new int[] {b[0] + b[2], Geometry.centerY(b)});
                int[] bracketLabel = label(edge, path);
                if (!sameAsNow(edge, path, bracketLabel)) {
                    result.routes.add(new Route(text(edge, "id"), path, bracketLabel));
                }
                continue;
            }
            boolean aEllipse = "UseCase".equals(text(nodes.get(text(edge, "from")), "model_type"));
            boolean bEllipse = "UseCase".equals(text(nodes.get(text(edge, "to")), "model_type"));
            int[] ca = {Geometry.centerX(a), Geometry.centerY(a)};
            int[] cb = {Geometry.centerX(b), Geometry.centerY(b)};
            List<int[]> path = new ArrayList<>();
            path.add(border(a, aEllipse, cb));
            path.add(border(b, bEllipse, ca));
            int[] label = label(edge, path);
            if (sameAsNow(edge, path, label) || label == null && alreadyStraight(edge, a, b)) {
                // VP redraws straight lines its own way (live: both Student lines became
                // horizontal); one that already joins the two target shapes needs no route.
                continue;
            }
            result.routes.add(new Route(text(edge, "id"), path, label));
        }
        for (JsonObject uc : useCases) {
            JsonObject parent = nodes.get(text(uc, "parent_id"));
            if (boundary != null && parent != boundary) {
                result.notes.add("'" + text(uc, "name") + "' is placed inside the boundary but is not nested in it; "
                        + "nest it (create_element container) so VP treats it as part of the system.");
            }
        }
        return result;
    }

    /**
     * True when the connector's current path is a single straight segment
     * whose ends already sit on the borders of the two shapes at their target
     * bounds (the line-through-shape lint still checks what it crosses).
     */
    private boolean alreadyStraight(JsonObject edge, int[] a, int[] b) {
        JsonArray points = array(edge, "waypoints");
        if (points.size() != 2) {
            return false;
        }
        int[] p = point(points.get(0));
        int[] q = point(points.get(1));
        if (p == null || q == null) {
            return false;
        }
        boolean aEllipse = "UseCase".equals(text(nodes.get(text(edge, "from")), "model_type"));
        boolean bEllipse = "UseCase".equals(text(nodes.get(text(edge, "to")), "model_type"));
        return onBorder(a, aEllipse, p) && onBorder(b, bEllipse, q);
    }

    /**
     * True when the connector already follows {@code path} (each point within
     * 2px: VP rounds) and, when a label is planned, its caption already sits there.
     */
    private boolean sameAsNow(JsonObject edge, List<int[]> path, int[] label) {
        JsonArray points = array(edge, "waypoints");
        if (points.size() != path.size()) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            int[] now = point(points.get(i));
            if (now == null || Math.abs(now[0] - path.get(i)[0]) > 2 || Math.abs(now[1] - path.get(i)[1]) > 2) {
                return false;
            }
        }
        if (label == null) {
            return true;
        }
        JsonElement visual = edge.get("visual_properties");
        JsonElement caption = visual != null && visual.isJsonObject() ? visual.getAsJsonObject().get("caption") : null;
        JsonElement raw = caption != null && caption.isJsonObject() ? caption.getAsJsonObject().get("bounds") : null;
        int[] now = raw == null ? null : point(raw);
        return now != null && Math.abs(now[0] - label[0]) <= 2 && Math.abs(now[1] - label[1]) <= 2;
    }

    /** A point within a few px of a shape's outline (ellipse for use cases, else box). */
    static boolean onBorder(int[] r, boolean ellipse, int[] p) {
        if (ellipse) {
            double rx = r[2] / 2.0;
            double ry = r[3] / 2.0;
            double dx = p[0] - (r[0] + rx);
            double dy = p[1] - (r[1] + ry);
            double k = Math.sqrt(dx * dx / (rx * rx) + dy * dy / (ry * ry));
            // k = 1 on the outline; allow about 3px either way along the nearer radius
            double tolerance = 3.0 / Math.min(rx, ry);
            return Math.abs(k - 1) <= Math.max(tolerance, 0.08);
        }
        boolean nearX = p[0] >= r[0] - 3 && p[0] <= r[0] + r[2] + 3;
        boolean nearY = p[1] >= r[1] - 3 && p[1] <= r[1] + r[3] + 3;
        if (!nearX || !nearY) {
            return false;
        }
        int inX = Math.min(Math.abs(p[0] - r[0]), Math.abs(r[0] + r[2] - p[0]));
        int inY = Math.min(Math.abs(p[1] - r[1]), Math.abs(r[1] + r[3] - p[1]));
        return Math.min(inX, inY) <= 3;
    }

    private static int[] point(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject o = raw.getAsJsonObject();
        return o.has("x") && o.has("y") ? new int[] {o.get("x").getAsInt(), o.get("y").getAsInt()} : null;
    }

    /** Label box for a connector's new path; null when the connector shows no label. */
    private int[] label(JsonObject edge, List<int[]> path) {
        int[] size = labelSize(edge);
        return size == null ? null : Geometry.labelBox(path, size[0], size[1], LABEL_GAP);
    }

    /**
     * {width, height} of a connector's label: its current caption box when
     * VP shows one, else the measured «stereotype» or name; null for none.
     */
    private int[] labelSize(JsonObject edge) {
        JsonElement visual = edge.get("visual_properties");
        if (visual != null && visual.isJsonObject()) {
            JsonElement caption = visual.getAsJsonObject().get("caption");
            if (caption != null && caption.isJsonObject()) {
                JsonObject c = caption.getAsJsonObject();
                JsonElement visible = c.get("is_visible");
                JsonElement raw = c.get("bounds");
                if ((visible == null || visible.getAsBoolean()) && raw != null && raw.isJsonObject()) {
                    JsonObject b = raw.getAsJsonObject();
                    int w = b.has("width") ? b.get("width").getAsInt() : 0;
                    int h = b.has("height") ? b.get("height").getAsInt() : 0;
                    if (w > 0 && h > 0) {
                        return new int[] {w, h};
                    }
                    if (b.has("height")) {
                        return null; // VP reports a zero-height caption for lines that show no text
                    }
                }
            }
        }
        JsonArray stereotypes = array(edge, "stereotypes");
        String name = text(edge, "name");
        String shown = stereotypes.size() > 0 ? "<<" + stereotypes.get(0).getAsString() + ">>"
                : name != null && !name.trim().isEmpty() ? name : null;
        return shown == null ? null : new int[] {measure.width(shown) + 6, profile.lineHeight()};
    }

    /**
     * Width a column needs on its right for brackets to non-adjacent partners
     * in the same column, and their labels; 0 when there are none.
     */
    private int detourRoom(List<String> column) {
        int count = 0;
        int widest = 0;
        for (JsonObject edge : edges) {
            int a = column.indexOf(text(edge, "from"));
            int b = column.indexOf(text(edge, "to"));
            if (a < 0 || b < 0 || Math.abs(a - b) < 2) {
                continue;
            }
            count++;
            int[] size = labelSize(edge);
            widest = Math.max(widest, size == null ? 0 : size[0]);
        }
        if (count == 0) {
            return 0;
        }
        int needed = profile.spacing("detour_offset", 20) + 10 * (count - 1) + LABEL_GAP + widest + 10;
        return Geometry.ceilToGrid(needed, profile.grid());
    }

    /** True when another placed shape sits vertically between two same-column shapes. */
    private static boolean blockedBetween(int[] a, int[] b, Map<String, int[]> placed, String idA, String idB) {
        int top = Math.min(a[1] + a[3], b[1] + b[3]);
        int bottom = Math.max(a[1], b[1]);
        for (Map.Entry<String, int[]> e : placed.entrySet()) {
            int[] r = e.getValue();
            if (e.getKey().equals(idA) || e.getKey().equals(idB) || r[0] != a[0]) {
                continue;
            }
            if (r[1] >= top && r[1] + r[3] <= bottom) {
                return true;
            }
        }
        return false;
    }

    /** Adds a use case and, right after it, its include/extend partners not yet placed. */
    private void addWithPartners(String id, List<String> column, Set<String> placed,
            Map<String, List<String>> partners, List<JsonObject> ordered) {
        if (!placed.add(id)) {
            return;
        }
        column.add(id);
        List<String> mine = new ArrayList<>(partners.getOrDefault(id, List.of()));
        mine.sort(Comparator.comparing(p -> orderKey(nodes.get(p))));
        for (String partner : mine) {
            if (!placed.contains(partner)) {
                placed.add(partner);
                column.add(partner);
            }
        }
    }

    private int layoutColumn(List<String> column, int x, int width, int y, int height, int gap,
            Map<String, int[]> out) {
        int grid = profile.grid();
        int cursor = y;
        for (String id : column) {
            int[] current = bounds(nodes.get(id));
            int h = current[3] > height ? Geometry.ceilToGrid(current[3], grid) : height;
            out.put(id, new int[] {x, cursor, width, h});
            cursor += h + gap;
        }
        return column.isEmpty() ? y : cursor - gap;
    }

    private int columnWidth(List<String> column) {
        int grid = profile.grid();
        int width = profile.size("UseCase", "min_width", 100);
        for (String id : column) {
            int ideal = measure.width(text(nodes.get(id), "name")) + profile.size("UseCase", "text_padding", 30);
            width = Math.max(width, ideal);
        }
        return Geometry.ceilToGrid(width, grid);
    }

    private int maxCaption(List<JsonObject> actors) {
        int width = 0;
        for (JsonObject actor : actors) {
            width = Math.max(width, measure.width(text(actor, "name")) + 21);
        }
        return width;
    }

    /**
     * Each actor at the middle of its use cases; unconnected actors fill in
     * after them in their current order. Collisions are pushed down so every
     * actor keeps actor height + caption + gap of space.
     */
    private void placeActors(List<JsonObject> actors, int centerX, Map<String, List<String>> actorsOf,
            Map<String, int[]> placed, int top, int height, int actorW, int actorH, int grid) {
        if (actors.isEmpty()) {
            return;
        }
        int slot = actorH + 15 + profile.spacing("actor_spacing", 40);
        List<int[]> wanted = new ArrayList<>(); // {index, desiredY}
        for (int i = 0; i < actors.size(); i++) {
            String id = text(actors.get(i), "id");
            int sum = 0;
            int count = 0;
            for (Map.Entry<String, List<String>> e : actorsOf.entrySet()) {
                if (e.getValue().contains(id) && placed.containsKey(e.getKey())) {
                    sum += Geometry.centerY(placed.get(e.getKey()));
                    count++;
                }
            }
            wanted.add(new int[] {i, count == 0 ? Integer.MAX_VALUE : sum / count - actorH / 2});
        }
        // connected first by desired y, then unconnected in their current order
        wanted.sort(Comparator.comparingInt((int[] w) -> w[1]).thenComparingInt(w -> w[0]));
        int cursor = top + 30;
        // One step for the whole column, so gaps are even (live run: mixing a
        // tight step for connected actors with a wide one for the rest read as uneven).
        int step = Geometry.ceilToGrid(Math.max(slot, actors.size() > 1
                ? (height - actorH) / Math.max(1, actors.size()) : slot), grid);
        for (int[] w : wanted) {
            int desired = w[1] == Integer.MAX_VALUE ? cursor : Math.max(w[1], cursor);
            int y = Geometry.ceilToGrid(desired, grid);
            JsonObject actor = actors.get(w[0]);
            placed.put(text(actor, "id"), new int[] {Geometry.snap(centerX - actorW / 2, grid), y, actorW, actorH});
            cursor = y + step;
        }
    }

    /** {"left"|"right", reason}. */
    private String[] side(JsonObject actor) {
        String name = text(actor, "name");
        for (String key : new String[] {text(actor, "id"), name}) {
            String override = key == null ? null : sideOverrides.get(key);
            if ("left".equals(override) || "right".equals(override)) {
                return new String[] {override, "explicit"};
            }
        }
        for (JsonElement stereotype : array(actor, "stereotypes")) {
            String s = stereotype.getAsString().toLowerCase(Locale.ROOT);
            if (SYSTEM_WORDS.matcher(s).find()) {
                return new String[] {"right", "stereotype «" + stereotype.getAsString() + "»"};
            }
        }
        Matcher byName = SYSTEM_WORDS.matcher(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (byName.find()) {
            return new String[] {"right", "name contains '" + byName.group(1) + "' (external system)"};
        }
        // Documentation is not used: prose about people mentions devices and services too
        // (live WashWatch run: "Student ... anonymous device only" was misread as a system).
        return new String[] {"left", "person (no system keyword)"};
    }

    private String orderKey(JsonObject node) {
        String name = text(node, "name");
        Matcher m = NUMBERED.matcher(name == null ? "" : name.trim());
        if (m.find()) {
            return String.format("0|%s|%06d|%s", m.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(m.group(2)), name);
        }
        return "1|" + (name == null ? "" : name.toLowerCase(Locale.ROOT)) + "|" + text(node, "id");
    }

    /** Point on a shape's border along the line from its centre toward {@code toward}. */
    public static int[] border(int[] r, boolean ellipse, int[] toward) {
        double cx = r[0] + r[2] / 2.0;
        double cy = r[1] + r[3] / 2.0;
        double dx = toward[0] - cx;
        double dy = toward[1] - cy;
        if (dx == 0 && dy == 0) {
            return new int[] {(int) Math.round(cx), (int) Math.round(cy)};
        }
        double a = r[2] / 2.0;
        double b = r[3] / 2.0;
        double t;
        if (ellipse) {
            t = 1.0 / Math.sqrt((dx * dx) / (a * a) + (dy * dy) / (b * b));
        } else {
            double tx = dx == 0 ? Double.MAX_VALUE : a / Math.abs(dx);
            double ty = dy == 0 ? Double.MAX_VALUE : b / Math.abs(dy);
            t = Math.min(tx, ty);
        }
        return new int[] {(int) Math.round(cx + dx * t), (int) Math.round(cy + dy * t)};
    }

    private static int childCount(JsonObject node) {
        return array(node, "child_ids").size();
    }

    static int[] bounds(JsonObject node) {
        JsonElement raw = node == null ? null : node.get("bounds");
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject b = raw.getAsJsonObject();
        if (!b.has("x") || !b.has("y") || !b.has("width") || !b.has("height")) {
            return null;
        }
        int[] r = {b.get("x").getAsInt(), b.get("y").getAsInt(), b.get("width").getAsInt(), b.get("height").getAsInt()};
        return r[2] > 0 && r[3] > 0 ? r : null;
    }

    private static String text(JsonObject holder, String field) {
        JsonElement value = holder == null ? null : holder.get(field);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static JsonArray array(JsonObject holder, String field) {
        JsonElement value = holder == null ? null : holder.get(field);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
