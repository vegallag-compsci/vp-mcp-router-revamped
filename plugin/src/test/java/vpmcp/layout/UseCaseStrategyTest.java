package vpmcp.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vpmcp.tools.PlanLayoutTool;

/**
 * Use case strategy contracts as invariants over fixture diagrams: sides,
 * containment, grid, no overlaps, equal column widths, names fit, ordering,
 * partners together, columns, routing, determinism and idempotence.
 * No coordinate is asserted literally.
 */
class UseCaseStrategyTest {

    private static final StyleProfile PROFILE = StyleProfile.defaults();
    private static final TextMeasure MEASURE = TextMeasure.fixed(6);

    /** A deliberately messy diagram: scattered, off-grid, wrong sizes, one include and one extend. */
    private static Fixture messy(int useCases) {
        Fixture f = new Fixture();
        List<String> kids = new ArrayList<>();
        for (int i = 1; i <= useCases; i++) {
            kids.add("u" + i);
        }
        f.node("sys", "System", "Shop", 133, 7, 900, 1500, null, kids.toArray(new String[0]));
        Random random = new Random(7);
        for (int i = 1; i <= useCases; i++) {
            f.node("u" + i, "UseCase", String.format("UC-%02d Do Thing Number %d", i, i),
                    150 + random.nextInt(600), 20 + random.nextInt(1300), 60 + random.nextInt(80), 37, "sys");
        }
        f.node("student", "Actor", "Student", 900, 30, 30, 60, null);
        f.node("clerk", "Actor", "Staff Clerk", 40, 900, 30, 60, null);
        f.node("sensor", "Actor", "Door Sensor", 20, 20, 30, 60, null);
        f.node("pay", "Actor", "Payment Gateway", 500, 1600, 30, 60, null);
        f.edge("e1", "Association", "student", "u1");
        f.edge("e2", "Association", "student", "u2");
        f.edge("e3", "Association", "clerk", "u4");
        f.edge("e4", "Association", "sensor", "u" + useCases);
        f.edge("e5", "Include", "u2", "u3");
        f.edge("e6", "Extend", "u5", "u2");
        return f;
    }

    private static UseCaseStrategy.Result plan(Fixture f) {
        return UseCaseStrategy.plan(f.build(), PROFILE, MEASURE, null);
    }

    private static Map<String, int[]> after(UseCaseStrategy.Result result) {
        Map<String, int[]> out = new HashMap<>();
        for (UseCaseStrategy.Placement p : result.placements) {
            out.put(p.viewId, p.after);
        }
        return out;
    }

    @Test
    void peopleGoLeftSystemsRightAndOverridesWin() {
        UseCaseStrategy.Result result = plan(messy(6));
        Map<String, String> sides = sides(result);

        assertEquals("left", sides.get("Student"));
        assertEquals("left", sides.get("Staff Clerk"));
        assertEquals("right", sides.get("Door Sensor"));
        assertEquals("right", sides.get("Payment Gateway"));

        Map<String, String> override = new HashMap<>();
        override.put("Staff Clerk", "right");
        UseCaseStrategy.Result overridden = UseCaseStrategy.plan(messy(6).build(), PROFILE, MEASURE, override);
        assertEquals("right", sides(overridden).get("Staff Clerk"));
        Map<String, int[]> geometry = after(overridden);
        assertTrue(geometry.get("clerk")[0] > geometry.get("sys")[0] + geometry.get("sys")[2],
                "an actor moved right sits right of the boundary");
    }

    @Test
    void documentationNeverDecidesASide() {
        Fixture f = messy(4);
        f.find("student").addProperty("documentation", "Uses a mobile device and the notification service.");

        assertEquals("left", sides(plan(f)).get("Student"));
    }

    @Test
    void everythingLandsOnTheGridInsideTheBoundaryWithoutOverlaps() {
        Map<String, int[]> g = after(plan(messy(12)));
        int[] box = g.get("sys");
        int pad = PROFILE.spacing("container_padding", 20);
        List<int[]> shapes = new ArrayList<>();
        for (Map.Entry<String, int[]> e : g.entrySet()) {
            int[] r = e.getValue();
            assertTrue(Geometry.onGrid(r[0], 10) && Geometry.onGrid(r[1], 10), e.getKey() + " on grid");
            if (e.getKey().startsWith("u")) {
                assertTrue(Geometry.insetWithin(box, r) >= pad / 2, e.getKey() + " inside with padding");
            } else if (!"sys".equals(e.getKey())) {
                assertFalse(Geometry.intersects(box, r), e.getKey() + " actor outside the boundary");
            }
            if (!"sys".equals(e.getKey())) {
                shapes.add(r);
            }
        }
        for (int i = 0; i < shapes.size(); i++) {
            for (int j = i + 1; j < shapes.size(); j++) {
                assertFalse(Geometry.intersects(shapes.get(i), shapes.get(j)), "no overlaps");
            }
        }
    }

    @Test
    void columnsHaveEqualWidthsAndEveryNameFits() {
        Fixture f = messy(12);
        Map<String, int[]> g = after(plan(f));
        Map<Integer, Integer> widthByColumn = new HashMap<>();
        for (int i = 1; i <= 12; i++) {
            int[] r = g.get("u" + i);
            Integer known = widthByColumn.putIfAbsent(r[0], r[2]);
            assertTrue(known == null || known == r[2], "equal widths in a column");
            String name = f.find("u" + i).get("name").getAsString();
            assertTrue(MEASURE.width(name) + 10 <= r[2], name + " fits on one line");
        }
        assertEquals(2, widthByColumn.size(), "12 use cases split into two columns");
    }

    @Test
    void sixUseCasesWithOnlyLeftActorsStayInOneColumn() {
        Fixture f = messy(6);
        f.removeEdge("e4");   // the sensor no longer owns a use case
        Map<String, int[]> g = after(plan(f));
        int x = g.get("u1")[0];
        for (int i = 2; i <= 6; i++) {
            assertEquals(x, g.get("u" + i)[0], "u" + i + " in the single column");
        }
    }

    @Test
    void partnersFollowTheirBaseAndUnanchoredOnesKeepNumberOrder() {
        Map<String, int[]> g = after(plan(messy(12)));
        // u2 is the base of u3 (include) and u5 (extend): both directly below it in its column
        assertEquals(g.get("u2")[0], g.get("u3")[0]);
        assertEquals(g.get("u2")[0], g.get("u5")[0]);
        assertTrue(g.get("u3")[1] > g.get("u2")[1] && g.get("u5")[1] > g.get("u3")[1]);
        // unanchored use cases in one column appear in number order
        List<Integer> ys = new ArrayList<>();
        for (int i : new int[] {6, 7, 8, 9, 10, 11}) {
            if (g.get("u" + i)[0] == g.get("u6")[0]) {
                ys.add(g.get("u" + i)[1]);
            }
        }
        List<Integer> sorted = new ArrayList<>(ys);
        Collections.sort(sorted);
        assertEquals(sorted, ys);
    }

    @Test
    void useCaseOwnedOnlyByARightActorGoesToTheRightColumn() {
        Map<String, int[]> g = after(plan(messy(12)));
        assertTrue(g.get("u12")[0] > g.get("u1")[0], "the sensor's use case is in the right column");
    }

    @Test
    void blockedSameColumnLinksDetourAndOthersAreStraightBorderToBorder() {
        UseCaseStrategy.Result result = plan(messy(12));
        Map<String, int[]> g = after(result);
        for (UseCaseStrategy.Route route : result.routes) {
            if ("e6".equals(route.edgeId)) {    // u5 extend u2, with u3 in between
                assertEquals(4, route.points.size(), "a detour bracket");
                int out = route.points.get(1)[0];
                assertTrue(out > g.get("u2")[0] + g.get("u2")[2], "the bracket runs outside the column");
            } else if ("e1".equals(route.edgeId)) {
                assertEquals(2, route.points.size());
                int[] end = route.points.get(1);
                int[] uc = g.get("u1");
                double nx = (end[0] - (uc[0] + uc[2] / 2.0)) / (uc[2] / 2.0);
                double ny = (end[1] - (uc[1] + uc[3] / 2.0)) / (uc[3] / 2.0);
                assertEquals(1.0, nx * nx + ny * ny, 0.08, "ends on the use case's ellipse");
            }
        }
    }

    @Test
    void lineLabelsSitBesideTheirOwnLineAndClearOfEveryShape() {
        UseCaseStrategy.Result result = plan(messy(12));
        Map<String, int[]> g = after(result);
        int labelled = 0;
        for (UseCaseStrategy.Route route : result.routes) {
            if (route.label == null) {
                continue;
            }
            labelled++;
            double own = Geometry.boxToPolyline(route.label, route.points);
            assertTrue(own <= 20, route.edgeId + " label is " + own + "px from its line");
            for (UseCaseStrategy.Route other : result.routes) {
                if (other != route) {
                    assertTrue(Geometry.boxToPolyline(route.label, other.points) + 5 >= own,
                            route.edgeId + " label is nearer " + other.edgeId);
                }
            }
            for (Map.Entry<String, int[]> shape : g.entrySet()) {
                if (!"sys".equals(shape.getKey())) {
                    assertTrue(!Geometry.intersects(route.label, shape.getValue()),
                            route.edgeId + " label overlaps " + shape.getKey());
                }
            }
        }
        assertEquals(2, labelled, "the «include» and «extend» lines carry labels; associations none");
    }

    @Test
    void sameDiagramInAnyOrderGivesTheSameLayout() {
        Fixture f = messy(10);
        Map<String, int[]> reference = after(plan(f));
        for (long seed = 1; seed <= 4; seed++) {
            Map<String, int[]> shuffled = after(UseCaseStrategy.plan(f.shuffled(seed), PROFILE, MEASURE, null));
            for (String id : reference.keySet()) {
                assertEquals(List.of(reference.get(id)[0], reference.get(id)[1], reference.get(id)[2], reference.get(id)[3]),
                        List.of(shuffled.get(id)[0], shuffled.get(id)[1], shuffled.get(id)[2], shuffled.get(id)[3]), id);
            }
        }
    }

    @Test
    void planningATidiedDiagramChangesNothing() {
        JsonObject graph = messy(12).build();
        JsonObject first = PlanLayoutTool.plan(graph, PROFILE, MEASURE, null);
        assertTrue(first.get("recommended").getAsBoolean(), first.toString());
        assertTrue(first.get("score_predicted").getAsInt() > first.get("score_before").getAsInt());

        JsonObject tidied = graph.deepCopy();
        apply(tidied, first.getAsJsonArray("ops"));
        JsonObject second = PlanLayoutTool.plan(tidied, PROFILE, MEASURE, null);

        assertEquals(0, second.get("moves").getAsInt(), "idempotent: " + second.get("ops"));
        assertEquals(0, second.get("routes").getAsInt(), "lines already in place are left alone: "
                + second.get("ops"));
        assertEquals(first.get("score_predicted").getAsInt(), second.get("score_before").getAsInt());
    }

    // ---- helpers ------------------------------------------------------------

    private static Map<String, String> sides(UseCaseStrategy.Result result) {
        Map<String, String> out = new HashMap<>();
        for (JsonElement e : result.actorSides) {
            out.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject().get("side").getAsString());
        }
        return out;
    }

    /** Applies move/route ops to a graph the way the batch applier would. */
    private static void apply(JsonObject graph, JsonArray ops) {
        Map<String, JsonObject> byId = new HashMap<>();
        for (String key : new String[] {"nodes", "edges"}) {
            for (JsonElement e : graph.getAsJsonArray(key)) {
                byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        for (JsonElement item : ops) {
            JsonObject op = item.getAsJsonObject();
            JsonObject target = byId.get(op.get("element").getAsString());
            if ("move_element".equals(op.get("op").getAsString())) {
                JsonObject b = target.getAsJsonObject("bounds");
                for (String k : new String[] {"x", "y", "width", "height"}) {
                    b.add(k, op.get(k));
                }
            } else {
                target.add("waypoints", op.get("points"));
                if (op.has("label")) {
                    target.add("visual_properties", com.google.gson.JsonParser.parseString(
                            "{\"caption\":{\"is_visible\":true,\"side\":\"none\",\"bounds\":"
                                    + op.get("label") + "}}"));
                }
            }
        }
    }

    private static final class Fixture {
        private final JsonArray nodes = new JsonArray();
        private final JsonArray edges = new JsonArray();

        void node(String id, String type, String name, int x, int y, int w, int h, String parent, String... kids) {
            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("model_id", "m-" + id);
            node.addProperty("model_type", type);
            node.addProperty("name", name);
            node.addProperty("documentation", "");
            JsonObject b = new JsonObject();
            b.addProperty("x", x);
            b.addProperty("y", y);
            b.addProperty("width", w);
            b.addProperty("height", h);
            node.add("bounds", b);
            node.addProperty("parent_id", parent);
            JsonArray children = new JsonArray();
            for (String k : kids) {
                children.add(k);
            }
            node.add("child_ids", children);
            nodes.add(node);
        }

        void edge(String id, String type, String from, String to) {
            JsonObject edge = new JsonObject();
            edge.addProperty("id", id);
            edge.addProperty("model_type", type);
            edge.addProperty("from", from);
            edge.addProperty("to", to);
            if ("Include".equals(type) || "Extend".equals(type)) {
                JsonArray stereotypes = new JsonArray();
                stereotypes.add(type);
                edge.add("stereotypes", stereotypes);
            }
            edges.add(edge);
        }

        void removeEdge(String id) {
            for (int i = 0; i < edges.size(); i++) {
                if (id.equals(edges.get(i).getAsJsonObject().get("id").getAsString())) {
                    edges.remove(i);
                    return;
                }
            }
        }

        JsonObject find(String id) {
            for (JsonElement n : nodes) {
                if (id.equals(n.getAsJsonObject().get("id").getAsString())) {
                    return n.getAsJsonObject();
                }
            }
            throw new AssertionError(id);
        }

        JsonObject build() {
            JsonObject diagram = new JsonObject();
            diagram.addProperty("id", "d");
            diagram.addProperty("name", "Test");
            diagram.addProperty("type", "UseCaseDiagram");
            JsonObject graph = new JsonObject();
            graph.add("diagram", diagram);
            graph.add("nodes", nodes.deepCopy());
            graph.add("edges", edges.deepCopy());
            return graph;
        }

        JsonObject shuffled(long seed) {
            JsonObject graph = build();
            for (String key : new String[] {"nodes", "edges"}) {
                List<JsonElement> items = new ArrayList<>();
                graph.getAsJsonArray(key).forEach(items::add);
                Collections.shuffle(items, new Random(seed));
                JsonArray reordered = new JsonArray();
                items.forEach(reordered::add);
                graph.add(key, reordered);
            }
            return graph;
        }
    }
}
