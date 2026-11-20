package vpmcp.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Linter contracts on hand-built graphs shaped like DiagramExtractor output.
 * Each test builds the smallest diagram that should (or should not) trip a
 * rule and asserts on rule names and involved ids, never on message text.
 */
class DiagramLinterTest {

    @Test
    void unconnectedActorAndUseCaseAreFlaggedConnectedOnesAreNot() {
        Graph g = new Graph();
        g.node("a1", "Actor", "Student", 0, 0, 30, 60, null);
        g.node("a2", "Actor", "Admin", 0, 200, 30, 60, null);
        g.node("u1", "UseCase", "Start Cycle", 200, 0, 120, 40, null);
        g.node("u2", "UseCase", "Lonely", 200, 200, 120, 40, null);
        g.edge("e1", "Association", "a1", "u1");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertEquals(List.of("a2"), idsFor(report, "unconnected_actor"));
        assertEquals(List.of("u2"), idsFor(report, "unconnected_use_case"));
    }

    @Test
    void childPastContainerEdgeIsFlaggedChildInsideIsNot() {
        Graph g = new Graph();
        g.node("sys", "System", "Shop", 100, 0, 300, 300, null, "in", "out");
        g.node("in", "UseCase", "Inside", 120, 20, 100, 40, "sys");
        g.node("out", "UseCase", "Overflow", 350, 100, 100, 40, "sys");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertEquals(List.of("out", "sys"), idsFor(report, "outside_container"));
    }

    @Test
    void overlapCrowdedAndSpacedShapesAreTold() {
        Graph g = new Graph();
        g.node("p", "UseCase", "P", 0, 0, 100, 40, null);
        g.node("q", "UseCase", "Q", 50, 20, 100, 40, null);     // overlaps p
        g.node("r", "UseCase", "R", 0, 300, 100, 40, null);
        g.node("s", "UseCase", "S", 0, 340, 100, 40, null);     // touches r
        g.node("t", "UseCase", "T", 400, 400, 100, 40, null);   // far from all

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertEquals(List.of("p", "q"), idsFor(report, "overlap"));
        assertEquals(List.of("r", "s"), idsFor(report, "crowded"));
        assertFalse(involves(report, "t"), "a well-spaced shape must not appear in spacing findings");
    }

    @Test
    void containersNeverOverlapTheirOwnContents() {
        Graph g = new Graph();
        g.node("sys", "System", "Shop", 0, 0, 400, 400, null, "u");
        g.node("u", "UseCase", "Checkout", 50, 50, 100, 40, "sys");
        g.node("a", "Actor", "Customer", 500, 0, 30, 60, null);
        g.edge("e", "Association", "a", "u");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertTrue(idsFor(report, "overlap").isEmpty(), report.toString());
    }

    @Test
    void actorInsideSystemBoundaryIsFlagged() {
        Graph g = new Graph();
        g.node("sys", "System", "Shop", 0, 0, 400, 400, null, "a");
        g.node("a", "Actor", "Clerk", 20, 20, 30, 60, "sys");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertEquals(List.of("a", "sys"), idsFor(report, "actor_inside_boundary"));
    }

    @Test
    void duplicateNamesNeedDifferentModelsNotTwoViewsOfOne() {
        Graph g = new Graph();
        g.node("v1", "UseCase", "Pay", 0, 0, 100, 40, null).addProperty("model_id", "m1");
        g.node("v2", "UseCase", "pay", 0, 100, 100, 40, null).addProperty("model_id", "m2");
        g.node("v3", "Actor", "Bank", 300, 0, 30, 60, null).addProperty("model_id", "shared");
        g.node("v4", "Actor", "Bank", 300, 200, 30, 60, null).addProperty("model_id", "shared");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        assertEquals(List.of("v1", "v2"), idsFor(report, "duplicate_name"));
    }

    @Test
    void numberingGapsAndDuplicatesNeedAtLeastThreeNumbers() {
        Graph series = new Graph();
        series.node("u1", "UseCase", "UC-01 A", 0, 0, 100, 40, null);
        series.node("u2", "UseCase", "UC-02 B", 0, 100, 100, 40, null);
        series.node("u4", "UseCase", "UC-04 D", 0, 200, 100, 40, null);
        series.node("u4b", "UseCase", "UC-04 Again", 0, 300, 100, 40, null);

        JsonObject report = DiagramLinter.lint(series.build(), true);

        assertEquals(1, findings(report, "numbering_gap").size());
        assertEquals(List.of("u4", "u4b"), idsFor(report, "duplicate_number"));

        Graph pair = new Graph();
        pair.node("x1", "UseCase", "UC-01 A", 0, 0, 100, 40, null);
        pair.node("x5", "UseCase", "UC-05 B", 0, 100, 100, 40, null);
        assertTrue(findings(DiagramLinter.lint(pair.build(), true), "numbering_gap").isEmpty());
    }

    @Test
    void includeTouchingAnActorIsAnError() {
        Graph g = new Graph();
        g.node("a", "Actor", "Student", 0, 0, 30, 60, null);
        g.node("u", "UseCase", "Start", 200, 0, 100, 40, null);
        g.node("v", "UseCase", "Pick", 200, 200, 100, 40, null);
        g.edge("bad", "Include", "a", "u");
        g.edge("good", "Include", "u", "v");

        JsonObject report = DiagramLinter.lint(g.build(), true);

        List<JsonObject> errors = findings(report, "include_extend_ends");
        assertEquals(1, errors.size());
        assertEquals("error", errors.get(0).get("severity").getAsString());
        assertEquals(List.of("a", "u"), idsFor(report, "include_extend_ends"));
    }

    @Test
    void excludingInfoKeepsCountsButDropsInfoFindings() {
        Graph g = new Graph();
        g.node("a", "Actor", "Student", 0, 0, 30, 60, null);              // unconnected: warning
        g.node("u", "UseCase", "Undocumented", 300, 0, 100, 40, null);     // unconnected + no doc

        JsonObject all = DiagramLinter.lint(g.build(), true);
        JsonObject quiet = DiagramLinter.lint(g.build(), false);

        int infos = all.getAsJsonObject("summary").get("info").getAsInt();
        assertTrue(infos > 0, all.toString());
        assertEquals(all.getAsJsonObject("summary"), quiet.getAsJsonObject("summary"));
        assertEquals(all.getAsJsonArray("findings").size() - infos, quiet.getAsJsonArray("findings").size());
        for (JsonElement finding : quiet.getAsJsonArray("findings")) {
            assertFalse("info".equals(finding.getAsJsonObject().get("severity").getAsString()));
        }
    }

    @Test
    void gapIsNegativeForOverlapZeroForTouchingPositiveForSpace() {
        int[] base = {0, 0, 10, 10};
        assertTrue(DiagramLinter.gap(base, new int[] {5, 5, 10, 10}) < 0);
        assertEquals(0, DiagramLinter.gap(base, new int[] {10, 0, 10, 10}));
        assertEquals(7, DiagramLinter.gap(base, new int[] {0, 17, 10, 10}));
    }

    // --- fixture builder ---------------------------------------------------

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
            node.addProperty("documentation", "");
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

    private static List<JsonObject> findings(JsonObject report, String rule) {
        List<JsonObject> matches = new ArrayList<>();
        for (JsonElement item : report.getAsJsonArray("findings")) {
            if (rule.equals(item.getAsJsonObject().get("rule").getAsString())) {
                matches.add(item.getAsJsonObject());
            }
        }
        return matches;
    }

    /** Sorted view ids across all findings of one rule. */
    private static List<String> idsFor(JsonObject report, String rule) {
        List<String> ids = new ArrayList<>();
        for (JsonObject finding : findings(report, rule)) {
            for (JsonElement element : finding.getAsJsonArray("elements")) {
                ids.add(element.getAsJsonObject().get("view_id").getAsString());
            }
        }
        ids.sort(null);
        return ids;
    }

    private static boolean involves(JsonObject report, String viewId) {
        for (String rule : new String[] {"overlap", "crowded"}) {
            if (idsFor(report, rule).contains(viewId)) {
                return true;
            }
        }
        return false;
    }
}
