package vpmcp.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import vpmcp.layout.StyleProfile;
import vpmcp.layout.TextMeasure;

/**
 * Layout rule contracts on hand-built graphs. A fixed-width text measure
 * (6 px per character) keeps name checks deterministic. Assertions are on
 * rule names, involved ids and score ordering, never on message text or
 * literal scores.
 */
class LayoutLinterTest {

    private static final StyleProfile PROFILE = StyleProfile.defaults();
    private static final TextMeasure MEASURE = TextMeasure.fixed(6);

    /** A diagram that follows the default profile: no layout finding. */
    private static Graph tidy() {
        Graph g = new Graph();
        g.node("sys", "System", "Shop", 200, 0, 300, 240, null, "u1", "u2", "u3");
        g.node("u1", "UseCase", "Pay", 260, 40, 180, 40, "sys");
        g.node("u2", "UseCase", "Browse", 260, 100, 180, 40, "sys");
        g.node("u3", "UseCase", "Ship", 260, 160, 180, 40, "sys");
        g.node("a1", "Actor", "Buyer", 100, 40, 30, 60, null);
        g.node("a2", "Actor", "Courier", 600, 40, 30, 60, null);
        g.edge("e1", "Association", "a1", "u1");
        g.edge("e2", "Association", "a1", "u2");
        g.edge("e3", "Association", "a2", "u3");
        return g;
    }

    private static JsonObject lint(Graph g) {
        return DiagramLinter.lint(g.build(), true, PROFILE, MEASURE);
    }

    @Test
    void tidyDiagramHasNoLayoutFindingAndAFullScore() {
        JsonObject report = lint(tidy());

        assertEquals(List.of(), layoutRules(report));
        assertEquals(100, score(report));
    }

    @Test
    void offGridShapesAreCollectedInOneFinding() {
        Graph g = tidy();
        g.move("u2", 263, 100);
        g.move("a2", 600, 47);

        JsonObject report = lint(g);

        assertEquals(1, findings(report, "off_grid").size());
        assertEquals(List.of("a2", "u2"), idsFor(report, "off_grid"));
    }

    @Test
    void nameFitIsMeasuredTruncatedWrappedOrFine() {
        Graph g = tidy();
        g.node("long", "UseCase", "Manage Locations Machines And Staff Access", 260, 220, 60, 40, "sys");
        g.node("wrap", "UseCase", "Review Reported Issues Today", 260, 280, 130, 40, "sys");
        g.edge("e4", "Association", "a1", "long");
        g.edge("e5", "Association", "a1", "wrap");

        JsonObject report = lint(g);

        assertEquals(List.of("long"), idsFor(report, "name_truncated"));
        assertEquals(List.of("wrap"), idsFor(report, "name_wraps"));
    }

    @Test
    void crossingConnectorsAreFoundButSharedEndsAreNot() {
        Graph g = new Graph();
        g.node("p", "UseCase", "P", 0, 0, 40, 40, null);
        g.node("q", "UseCase", "Q", 200, 200, 40, 40, null);
        g.node("r", "UseCase", "R", 200, 0, 40, 40, null);
        g.node("s", "UseCase", "S", 0, 200, 40, 40, null);
        g.edge("pq", "Association", "p", "q");   // diagonal \
        g.edge("rs", "Association", "r", "s");   // diagonal / : crosses pq
        g.edge("pr", "Association", "p", "r");   // shares p with pq: never counted against it

        JsonObject report = lint(g);

        assertEquals(List.of("pq", "rs"), idsFor(report, "line_crossing"));
    }

    @Test
    void lineThroughAThirdShapeIsFlaggedNotThroughItsOwnEnds() {
        Graph g = new Graph();
        g.node("left", "UseCase", "L", 0, 100, 60, 40, null);
        g.node("mid", "UseCase", "M", 200, 100, 60, 40, null);
        g.node("right", "UseCase", "R", 400, 100, 60, 40, null);
        g.edge("lr", "Association", "left", "right");

        JsonObject report = lint(g);

        assertEquals(List.of("lr", "mid"), idsFor(report, "line_through_shape"));
    }

    @Test
    void nearMissColumnsAreMisalignedExactOnesAreNot() {
        Graph g = tidy();
        g.move("u2", 264, 100);   // centre x 354 vs 350: a near miss

        JsonObject report = lint(g);

        assertTrue(idsFor(report, "misaligned").containsAll(List.of("u1", "u2", "u3")));
        assertEquals(List.of(), idsFor(lint(tidy()), "misaligned"));
    }

    @Test
    void unevenGapsInAColumnAreReported() {
        Graph g = tidy();
        g.move("u3", 260, 220);   // gaps 20 then 80

        assertEquals(List.of("u1", "u2", "u3"), idsFor(lint(g), "uneven_spacing"));
    }

    @Test
    void actorAboveTheBoundaryIsOnTheWrongSide() {
        Graph g = tidy();
        g.move("a1", 330, 420);   // below the boundary, between its left and right edges

        assertEquals(List.of("a1", "sys"), idsFor(lint(g), "actor_wrong_side"));
    }

    @Test
    void contentHuggingTheBoundaryEdgeLacksPadding() {
        Graph g = tidy();
        g.move("u1", 205, 40);    // 5px from the boundary's left edge

        assertEquals(List.of("sys", "u1"), idsFor(lint(g), "boundary_padding"));
    }

    @Test
    void boundaryFarBiggerThanItsContentsHasSlack() {
        Graph g = tidy();
        g.resize("sys", 300, 600);   // 400px of empty space under the use cases

        assertEquals(List.of("sys"), idsFor(lint(g), "container_slack"));
        assertEquals(List.of(), idsFor(lint(tidy()), "container_slack"));
    }

    @Test
    void actorsOfTheWrongSizeAreInconsistent() {
        Graph g = tidy();
        g.resize("a2", 40, 60);

        assertEquals(List.of("a2"), idsFor(lint(g), "inconsistent_size"));
    }

    @Test
    void actorNameOverAShapeIsALabelOverlap() {
        Graph g = tidy();
        g.caption("a1", 90, 100, 60, 15);          // below the actor, clear of everything
        g.caption("a2", 400, 100, 120, 15);         // runs into u2 (260..440)

        assertEquals(List.of("a2", "u2"), idsFor(lint(g), "label_overlap"));
    }

    @Test
    void lineLabelsBesideTheirOwnLineAreFineSwappedOnesAreMisplaced() {
        Graph g = new Graph();
        g.node("a", "UseCase", "A", 0, 0, 120, 40, null);
        g.node("b", "UseCase", "B", 0, 60, 120, 40, null);
        g.node("c", "UseCase", "C", 300, 0, 120, 40, null);
        g.node("d", "UseCase", "D", 300, 60, 120, 40, null);
        g.edge("ab", "Include", "a", "b");
        g.edge("cd", "Include", "c", "d");
        g.path("ab", 60, 40, 60, 60);
        g.path("cd", 360, 40, 360, 60);
        g.edgeCaption("ab", 64, 43, 80, 15);        // just right of its own line
        g.edgeCaption("cd", 364, 43, 80, 15);

        assertEquals(List.of(), idsFor(lint(g), "label_misplaced"));

        g.edgeCaption("ab", 364, 43, 80, 15);       // labels swapped: each beside the other line
        g.edgeCaption("cd", 64, 43, 80, 15);

        assertEquals(List.of("ab", "cd"), idsFor(lint(g), "label_misplaced"));
    }

    @Test
    void swimlanePartsDoNotOverlapTheNodesTheyFrame() {
        Graph g = new Graph();
        g.node("lane", "ActivitySwimlane2", "Lanes", 0, 0, 400, 300, null, "hdr", "cmp", "act");
        JsonObject header = g.node("hdr", "ActivityPartition", "Student", 0, 0, 200, 30, "lane");
        header.addProperty("shape_type", "ActivityPartitionHeader");
        g.node("cmp", null, null, 0, 30, 200, 270, "lane");
        g.node("act", "Activity", "Show Board", 30, 100, 140, 40, "lane");

        JsonObject report = lint(g);

        assertEquals(List.of(), idsFor(report, "overlap"));
    }

    @Test
    void scoreFallsWithEveryProblemAndIsNormalizedBySize() {
        int clean = score(lint(tidy()));
        Graph one = tidy();
        one.move("u2", 263, 100);
        Graph two = tidy();
        two.move("u2", 263, 100);
        two.resize("a2", 40, 60);

        int withOne = score(lint(one));
        int withTwo = score(lint(two));

        assertTrue(clean > withOne && withOne > withTwo, clean + " > " + withOne + " > " + withTwo);
        assertTrue(withTwo >= 0 && clean <= 100);
    }

    @Test
    void layoutBlockIsAbsentWithoutAProfile() {
        JsonObject report = DiagramLinter.lint(tidy().build(), true);

        assertTrue(!report.has("layout"));
    }

    // ---- fixtures ------------------------------------------------------------

    private static final class Graph {
        private final JsonArray nodes = new JsonArray();
        private final JsonArray edges = new JsonArray();

        JsonObject node(String id, String type, String name, int x, int y, int w, int h, String parent,
                String... children) {
            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("model_id", "m-" + id);
            node.addProperty("model_type", type);
            node.addProperty("name", name);
            node.addProperty("documentation", "doc");
            JsonObject bounds = new JsonObject();
            bounds.addProperty("x", x);
            bounds.addProperty("y", y);
            bounds.addProperty("width", w);
            bounds.addProperty("height", h);
            node.add("bounds", bounds);
            node.addProperty("parent_id", parent);
            JsonArray childIds = new JsonArray();
            for (String child : children) {
                childIds.add(child);
            }
            node.add("child_ids", childIds);
            nodes.add(node);
            return node;
        }

        void edge(String id, String type, String from, String to) {
            JsonObject edge = new JsonObject();
            edge.addProperty("id", id);
            edge.addProperty("model_type", type);
            edge.addProperty("from", from);
            edge.addProperty("to", to);
            edges.add(edge);
        }

        private JsonObject find(String id) {
            for (JsonElement n : nodes) {
                if (id.equals(n.getAsJsonObject().get("id").getAsString())) {
                    return n.getAsJsonObject();
                }
            }
            throw new AssertionError(id);
        }

        void move(String id, int x, int y) {
            JsonObject b = find(id).getAsJsonObject("bounds");
            b.addProperty("x", x);
            b.addProperty("y", y);
        }

        void resize(String id, int w, int h) {
            JsonObject b = find(id).getAsJsonObject("bounds");
            b.addProperty("width", w);
            b.addProperty("height", h);
        }

        void caption(String id, int x, int y, int w, int h) {
            find(id).add("visual_properties", JsonParser.parseString(
                    "{\"caption\":{\"side\":\"south\",\"bounds\":{\"x\":" + x + ",\"y\":" + y + ",\"width\":" + w
                            + ",\"height\":" + h + "}}}"));
        }

        private JsonObject findEdge(String id) {
            for (JsonElement e : edges) {
                if (id.equals(e.getAsJsonObject().get("id").getAsString())) {
                    return e.getAsJsonObject();
                }
            }
            throw new AssertionError(id);
        }

        void path(String id, int... xy) {
            JsonArray points = new JsonArray();
            for (int i = 0; i + 1 < xy.length; i += 2) {
                JsonObject p = new JsonObject();
                p.addProperty("x", xy[i]);
                p.addProperty("y", xy[i + 1]);
                points.add(p);
            }
            findEdge(id).add("waypoints", points);
        }

        void edgeCaption(String id, int x, int y, int w, int h) {
            findEdge(id).add("visual_properties", JsonParser.parseString(
                    "{\"caption\":{\"is_visible\":true,\"side\":\"none\",\"bounds\":{\"x\":" + x + ",\"y\":" + y
                            + ",\"width\":" + w + ",\"height\":" + h + "}}}"));
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
    }

    private static int score(JsonObject report) {
        return report.getAsJsonObject("layout").get("score").getAsInt();
    }

    private static List<String> layoutRules(JsonObject report) {
        List<String> rules = new ArrayList<>();
        for (JsonElement item : report.getAsJsonArray("findings")) {
            String rule = item.getAsJsonObject().get("rule").getAsString();
            if (LayoutLinter.LAYOUT_RULES.contains(rule)) {
                rules.add(rule);
            }
        }
        return rules;
    }

    private static List<JsonObject> findings(JsonObject report, String rule) {
        List<JsonObject> matches = new ArrayList<>();
        for (JsonElement item : report.getAsJsonArray("findings")) {
            if (rule.equals(item.getAsJsonObject().get("rule").getAsString())) {
                matches.add(item.getAsJsonObject());
            }
        }
        return matches;
    }

    private static List<String> idsFor(JsonObject report, String rule) {
        List<String> ids = new ArrayList<>();
        for (JsonObject finding : findings(report, rule)) {
            for (JsonElement element : finding.getAsJsonArray("elements")) {
                String id = element.getAsJsonObject().get("view_id").getAsString();
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        ids.sort(null);
        return ids;
    }
}
