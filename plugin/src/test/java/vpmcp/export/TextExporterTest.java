package vpmcp.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import vpmcp.export.TextExporter.Format;

/** Text export contracts: determinism, completeness, nesting, position isolation. */
class TextExporterTest {

    /** Fixture builder: a use case diagram in the extractor's graph shape. */
    private static final class Graph {
        final JsonObject root = new JsonObject();
        final JsonArray nodes = new JsonArray();
        final JsonArray edges = new JsonArray();

        Graph(String type) {
            JsonObject diagram = new JsonObject();
            diagram.addProperty("id", "D1");
            diagram.addProperty("name", "Use \"cases\"");
            diagram.addProperty("type", type);
            root.add("diagram", diagram);
            root.add("nodes", nodes);
            root.add("edges", edges);
            root.add("warnings", new JsonArray());
        }

        JsonObject node(String id, String name, String type, String parent, int x) {
            JsonObject node = new JsonObject();
            node.addProperty("id", id);
            node.addProperty("model_id", "m" + id);
            node.addProperty("name", name);
            node.addProperty("model_type", type);
            node.addProperty("parent_id", parent);
            node.addProperty("z_order", nodes.size());
            JsonObject bounds = new JsonObject();
            bounds.addProperty("x", x);
            bounds.addProperty("y", 10);
            bounds.addProperty("width", 100);
            bounds.addProperty("height", 40);
            node.add("bounds", bounds);
            node.add("visual_properties", JsonParser.parseString("{\"background\":\"#7ACFF5\"}"));
            node.add("members", new JsonArray());
            nodes.add(node);
            return node;
        }

        Graph edge(String id, String type, String from, String to) {
            JsonObject edge = new JsonObject();
            edge.addProperty("id", id);
            edge.addProperty("model_type", type);
            edge.addProperty("from", from);
            edge.addProperty("to", to);
            edges.add(edge);
            return this;
        }
    }

    private static Graph useCases() {
        Graph graph = new Graph("UseCaseDiagram");
        graph.node("A1", "Student", "Actor", null, 10);
        graph.node("S", "WashWatch", "System", null, 100);
        graph.node("U1", "UC-01 Select Location", "UseCase", "S", 120);
        graph.node("U2", "UC-02 View Board", "UseCase", "S", 140);
        graph.node("U3", "UC-03 View Time", "UseCase", "S", 160);
        graph.edge("E1", "Association", "A1", "U1")
                .edge("E2", "Association", "A1", "U2")
                .edge("E3", "Include", "U2", "U3")
                .edge("E4", "Extend", "U3", "U2");
        return graph;
    }

    private static JsonObject shuffled(JsonObject graph, long seed) {
        JsonObject copy = graph.deepCopy();
        for (String key : new String[] {"nodes", "edges"}) {
            List<JsonElement> items = new ArrayList<>();
            copy.getAsJsonArray(key).forEach(items::add);
            Collections.shuffle(items, new Random(seed));
            JsonArray reordered = new JsonArray();
            items.forEach(reordered::add);
            copy.add(key, reordered);
        }
        return copy;
    }

    @Test
    void sameDiagramInAnyOrderGivesIdenticalText() {
        JsonObject graph = useCases().root;
        for (Format format : Format.values()) {
            String reference = TextExporter.export(graph, format);
            for (long seed = 1; seed <= 5; seed++) {
                assertEquals(reference, TextExporter.export(shuffled(graph, seed), format), format.name());
            }
        }
    }

    @Test
    void plantUmlDeclaresEveryNodeAndDrawsEveryEdge() {
        Graph graph = useCases();
        String text = TextExporter.export(graph.root, Format.PLANTUML);

        assertTrue(text.startsWith("@startuml\n") && text.endsWith("@enduml\n"));
        for (JsonElement node : graph.nodes) {
            String name = node.getAsJsonObject().get("name").getAsString();
            assertEquals(1, count(text, "\"" + name + "\" as "), name);
        }
        long arrows = text.lines().filter(line -> line.matches("\\S+ (--|\\.\\.>|-->|--\\|>)\\s.*")).count();
        assertEquals(graph.edges.size(), arrows);
        assertEquals(1, count(text, "<<include>>"));
        assertEquals(1, count(text, "<<extend>>"));
    }

    @Test
    void extendArrowsRunFromTheExtensionToTheBaseVpStoresAsFrom() {
        // VP keeps «extend» from = base (arrowhead + extension point there); E4 is U3 (base) → U2.
        for (Format format : new Format[] {Format.PLANTUML, Format.MERMAID}) {
            String text = TextExporter.export(useCases().root, format);
            String line = text.lines().filter(l -> l.contains("<<extend>>")).findFirst().orElseThrow();
            String include = text.lines().filter(l -> l.contains("<<include>>")).findFirst().orElseThrow();
            assertTrue(line.trim().startsWith("UC_02"), format + ": " + line);
            assertTrue(line.indexOf("UC_03") > line.indexOf("UC_02"), format + ": " + line);
            assertTrue(include.trim().startsWith("UC_02"), format + " include keeps VP order: " + include);
        }
    }

    @Test
    void containerChildrenAreNestedInsideTheBoundary() {
        String text = TextExporter.export(useCases().root, Format.PLANTUML);
        int open = text.indexOf("rectangle \"WashWatch\"");
        int close = text.indexOf("}\n", open);
        for (String child : new String[] {"UC-01 Select Location", "UC-02 View Board", "UC-03 View Time"}) {
            int at = text.indexOf("\"" + child + "\"");
            assertTrue(open < at && at < close, child + " should sit inside the boundary block");
        }
        assertTrue(text.indexOf("actor \"Student\"") < open || text.indexOf("actor \"Student\"") > close);

        String mermaid = TextExporter.export(useCases().root, Format.MERMAID);
        int subgraph = mermaid.indexOf("subgraph ");
        int end = mermaid.indexOf("\n  end\n", subgraph);
        assertTrue(subgraph >= 0 && end > subgraph);
        assertTrue(mermaid.indexOf("UC-02 View Board") > subgraph && mermaid.indexOf("UC-02 View Board") < end);
    }

    @Test
    void movesShowInJsonOnlyAndRenderingNoiseNever() {
        Graph moved = useCases();
        moved.nodes.get(2).getAsJsonObject().getAsJsonObject("bounds").addProperty("x", 999);
        moved.nodes.get(2).getAsJsonObject().addProperty("z_order", 77);
        JsonObject original = useCases().root;

        assertEquals(TextExporter.export(original, Format.PLANTUML), TextExporter.export(moved.root, Format.PLANTUML));
        assertEquals(TextExporter.export(original, Format.MERMAID), TextExporter.export(moved.root, Format.MERMAID));
        String json = TextExporter.export(moved.root, Format.JSON);
        assertNotEquals(TextExporter.export(original, Format.JSON), json);
        assertFalse(json.contains("z_order"));
        assertFalse(json.contains("visual_properties"));
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(moved.nodes.size(), parsed.getAsJsonArray("nodes").size());
        assertEquals(moved.edges.size(), parsed.getAsJsonArray("edges").size());
    }

    @Test
    void duplicateNamesGetDistinctAliases() {
        Graph graph = new Graph("UseCaseDiagram");
        graph.node("U1", "Pay", "UseCase", null, 0);
        graph.node("U2", "Pay", "UseCase", null, 0);
        graph.edge("E", "Include", "U1", "U2");
        String text = TextExporter.export(graph.root, Format.PLANTUML);

        String include = text.lines().filter(line -> line.contains("<<include>>")).findFirst().orElseThrow();
        String[] parts = include.split(" ");
        assertNotEquals(parts[0], parts[2], "an edge between two same-named nodes must not become a self-loop");
    }

    @Test
    void edgeToANodeOffTheDiagramIsSkippedVisibly() {
        Graph graph = useCases();
        graph.edge("E9", "Association", "A1", "ELSEWHERE");
        String text = TextExporter.export(graph.root, Format.PLANTUML);

        assertEquals(1, count(text, "' skipped Association"));
        assertFalse(text.contains("ELSEWHERE"));
    }

    @Test
    void quotesInNamesCannotBreakTheSyntax() {
        Graph graph = new Graph("UseCaseDiagram");
        graph.node("U1", "Say \"hi\"", "UseCase", null, 0);
        String mermaid = TextExporter.export(graph.root, Format.MERMAID);
        String plant = TextExporter.export(graph.root, Format.PLANTUML);

        assertTrue(mermaid.contains("#quot;hi#quot;"));
        assertEquals(2, count(plant.lines().filter(l -> l.startsWith("usecase")).findFirst().orElseThrow(), "\""));
    }

    @Test
    void classDiagramsBecomeClassDiagramsWithMembers() {
        Graph graph = new Graph("ClassDiagram");
        JsonObject order = graph.node("C1", "Order", "Class", null, 0);
        graph.node("C2", "Item", "Class", null, 0);
        order.add("members", JsonParser.parseString("[{\"name\":\"total\",\"model_type\":\"Attribute\","
                + "\"type\":\"double\",\"visibility\":\"private\"},{\"name\":\"pay\",\"model_type\":\"Operation\","
                + "\"return_type\":\"void\",\"visibility\":\"public\",\"parameters\":[]}]"));
        graph.edge("E", "Association", "C1", "C2");
        graph.edges.get(0).getAsJsonObject().addProperty("from_aggregation", "composite");
        graph.edges.get(0).getAsJsonObject().addProperty("to_multiplicity", "0..*");

        String mermaid = TextExporter.export(graph.root, Format.MERMAID);
        String plant = TextExporter.export(graph.root, Format.PLANTUML);

        assertTrue(mermaid.contains("classDiagram"));
        for (String text : new String[] {mermaid, plant}) {
            assertTrue(text.contains("-total : double"));
            assertTrue(text.contains("+pay() : void"));
            assertTrue(text.contains("*-- \"0..*\""));
        }
    }

    @Test
    void unknownFormatIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Format.parse("dot"));
        assertEquals(Format.MERMAID, Format.parse("Mermaid"));
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }
}
