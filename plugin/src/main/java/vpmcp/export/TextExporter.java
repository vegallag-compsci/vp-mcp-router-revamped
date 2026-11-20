package vpmcp.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an extractor graph ({diagram, nodes[], edges[]}) into text that diffs
 * well in git (ADR-0034): PlantUML, Mermaid, or normalized JSON.
 *
 * Output is deterministic: nodes and edges are sorted (by type, name, id),
 * aliases are derived from names (with a numeric suffix on collision, in
 * sorted order), and no volatile fields (z-order, colours, caption boxes)
 * are written. Positions appear only in JSON, so a layout change shows up
 * there and nowhere else. Pure: no VP calls.
 */
public final class TextExporter {

    public enum Format {
        PLANTUML("puml"), MERMAID("mmd"), JSON("json");

        public final String extension;

        Format(String extension) {
            this.extension = extension;
        }

        public static Format parse(String value) {
            for (Format format : values()) {
                if (format.name().equalsIgnoreCase(value)) {
                    return format;
                }
            }
            throw new IllegalArgumentException("Unknown format \"" + value
                    + "\"; use plantuml, mermaid or json.");
        }
    }

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .serializeNulls().create();

    private TextExporter() {
    }

    public static String export(JsonObject graph, Format format) {
        switch (format) {
            case PLANTUML:
                return new View(graph).plantUml();
            case MERMAID:
                return new View(graph).mermaid();
            default:
                return normalizedJson(graph);
        }
    }

    /** Stable, review-friendly JSON: model facts and bounds, no rendering noise. */
    public static String normalizedJson(JsonObject graph) {
        View view = new View(graph);
        JsonObject out = new JsonObject();
        JsonObject diagram = new JsonObject();
        copy(graph.getAsJsonObject("diagram"), diagram, "id", "name", "type", "documentation");
        out.add("diagram", diagram);
        JsonArray nodes = new JsonArray();
        for (JsonObject node : view.nodes) {
            JsonObject json = new JsonObject();
            copy(node, json, "id", "model_id", "name", "model_type", "parent_id", "documentation");
            nonEmpty(node, json, "stereotypes");
            JsonObject tags = new JsonObject();
            if (node.has("tagged_values") && node.get("tagged_values").isJsonObject()) {
                node.getAsJsonObject("tagged_values").entrySet().stream()
                        .filter(entry -> !isBlank(entry.getValue()))
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> tags.add(entry.getKey(), entry.getValue()));
            }
            if (tags.size() > 0) {
                json.add("tagged_values", tags);
            }
            if (node.has("members") && node.get("members").isJsonArray()
                    && node.getAsJsonArray("members").size() > 0) {
                JsonArray members = new JsonArray();
                for (JsonElement member : node.getAsJsonArray("members")) {
                    JsonObject m = new JsonObject();
                    copy(member.getAsJsonObject(), m, "name", "model_type", "visibility", "type",
                            "multiplicity", "return_type", "parameters", "type_name", "length",
                            "nullable", "primary_key", "unique");
                    members.add(m);
                }
                json.add("members", members);
            }
            if (node.has("bounds")) {
                json.add("bounds", node.get("bounds"));
            }
            nodes.add(json);
        }
        out.add("nodes", nodes);
        JsonArray edges = new JsonArray();
        for (JsonObject edge : view.edges) {
            JsonObject json = new JsonObject();
            copy(edge, json, "id", "model_id", "model_type", "name", "from", "to");
            json.addProperty("from_name", view.nameOf(str(edge, "from")));
            json.addProperty("to_name", view.nameOf(str(edge, "to")));
            for (String key : new String[] {"from_multiplicity", "to_multiplicity", "from_aggregation",
                    "to_aggregation", "from_navigable", "to_navigable", "identifying"}) {
                if (edge.has(key) && !isBlank(edge.get(key))) {
                    json.add(key, edge.get(key));
                }
            }
            nonEmpty(edge, json, "stereotypes");
            edges.add(json);
        }
        out.add("edges", edges);
        return PRETTY.toJson(out) + "\n";
    }

    // ---- shared view ---------------------------------------------------------

    private static final class View {
        final JsonObject diagram;
        final List<JsonObject> nodes = new ArrayList<>();
        final List<JsonObject> edges = new ArrayList<>();
        final Map<String, JsonObject> byId = new HashMap<>();
        final Map<String, String> alias = new LinkedHashMap<>();

        View(JsonObject graph) {
            diagram = graph.getAsJsonObject("diagram");
            for (JsonElement node : array(graph, "nodes")) {
                nodes.add(node.getAsJsonObject());
            }
            for (JsonElement edge : array(graph, "edges")) {
                edges.add(edge.getAsJsonObject());
            }
            Comparator<JsonObject> order = Comparator
                    .comparing((JsonObject o) -> nz(str(o, "model_type")))
                    .thenComparing(o -> nz(str(o, "name")))
                    .thenComparing(o -> nz(str(o, "id")));
            nodes.sort(order);
            for (JsonObject node : nodes) {
                byId.put(str(node, "id"), node);
            }
            edges.sort(Comparator
                    .comparing((JsonObject o) -> nz(nameOf(str(o, "from"))))
                    .thenComparing(o -> nz(str(o, "model_type")))
                    .thenComparing(o -> nz(nameOf(str(o, "to"))))
                    .thenComparing(o -> nz(str(o, "id"))));
            Set<String> used = new HashSet<>();
            for (JsonObject node : nodes) {
                String base = identifier(str(node, "name"), str(node, "model_type"));
                String candidate = base;
                for (int n = 2; !used.add(candidate); n++) {
                    candidate = base + "_" + n;
                }
                alias.put(str(node, "id"), candidate);
            }
        }

        String nameOf(String id) {
            JsonObject node = byId.get(id);
            return node == null ? id : str(node, "name");
        }

        List<JsonObject> roots() {
            List<JsonObject> roots = new ArrayList<>();
            for (JsonObject node : nodes) {
                String parent = str(node, "parent_id");
                if (parent == null || !byId.containsKey(parent)) {
                    roots.add(node);
                }
            }
            return roots;
        }

        List<JsonObject> children(JsonObject parent) {
            List<JsonObject> children = new ArrayList<>();
            String id = str(parent, "id");
            for (JsonObject node : nodes) {
                if (id.equals(str(node, "parent_id"))) {
                    children.add(node);
                }
            }
            return children;
        }

        // ---- PlantUML ----

        String plantUml() {
            StringBuilder out = new StringBuilder("@startuml\n");
            out.append("' ").append(nz(str(diagram, "name"))).append(" (")
                    .append(nz(str(diagram, "type"))).append(", id ").append(nz(str(diagram, "id")))
                    .append(")\n");
            out.append("title ").append(oneLine(str(diagram, "name"))).append("\n");
            if ("UseCaseDiagram".equals(str(diagram, "type"))) {
                out.append("left to right direction\n");
            }
            for (JsonObject root : roots()) {
                plantNode(root, out, "");
            }
            for (JsonObject edge : edges) {
                String from = alias.get(str(edge, arrowTail(edge)));
                String to = alias.get(str(edge, arrowHead(edge)));
                if (from == null || to == null) {
                    out.append("' skipped ").append(nz(str(edge, "model_type")))
                            .append(" with an end off this diagram\n");
                    continue;
                }
                out.append(from).append(' ');
                String fromMult = multiplicity(edge, "from_multiplicity");
                String toMult = multiplicity(edge, "to_multiplicity");
                if (fromMult != null) {
                    out.append('"').append(fromMult).append("\" ");
                }
                out.append(plantArrow(edge));
                if (toMult != null) {
                    out.append(" \"").append(toMult).append('"');
                }
                out.append(' ').append(to);
                String label = edgeLabel(edge);
                if (label != null) {
                    out.append(" : ").append(label);
                }
                out.append('\n');
            }
            return out.append("@enduml\n").toString();
        }

        private void plantNode(JsonObject node, StringBuilder out, String indent) {
            String type = nz(str(node, "model_type"));
            String name = quote(str(node, "name"));
            String id = alias.get(str(node, "id"));
            List<JsonObject> children = children(node);
            switch (type) {
                case "Actor":
                    out.append(indent).append("actor ").append(name).append(" as ").append(id).append('\n');
                    return;
                case "UseCase":
                    out.append(indent).append("usecase ").append(name).append(" as ").append(id).append('\n');
                    return;
                case "Class":
                case "Interface":
                case "DBTable":
                    out.append(indent).append("Interface".equals(type) ? "interface " : "DBTable".equals(type)
                            ? "entity " : "class ").append(name).append(" as ").append(id);
                    JsonArray members = array(node, "members");
                    if (members.size() == 0) {
                        out.append('\n');
                        return;
                    }
                    out.append(" {\n");
                    for (JsonElement member : members) {
                        out.append(indent).append("  ").append(memberLine(member.getAsJsonObject())).append('\n');
                    }
                    out.append(indent).append("}\n");
                    return;
                default:
                    String keyword = children.isEmpty() ? "rectangle" : "System".equals(type) ? "rectangle" : "package";
                    out.append(indent).append(keyword).append(' ').append(name).append(" as ").append(id);
                    if (!"System".equals(type)) {
                        out.append(" <<").append(type).append(">>");
                    }
                    if (children.isEmpty()) {
                        out.append('\n');
                        return;
                    }
                    out.append(" {\n");
                    for (JsonObject child : children) {
                        plantNode(child, out, indent + "  ");
                    }
                    out.append(indent).append("}\n");
            }
        }

        /**
         * VP stores an «extend» from the extended (base) use case to the
         * extending one, and draws the arrowhead and extension point at its
         * from end (probed 2026-11-20 against a hand-drawn extend). UML text
         * draws the arrow from the extension to the base, so «extend» ends swap.
         */
        private String arrowTail(JsonObject edge) {
            return "Extend".equals(str(edge, "model_type")) ? "to" : "from";
        }

        private String arrowHead(JsonObject edge) {
            return "Extend".equals(str(edge, "model_type")) ? "from" : "to";
        }

        private String plantArrow(JsonObject edge) {
            String type = nz(str(edge, "model_type"));
            switch (type) {
                case "Generalization":
                    return "--|>";
                case "Realization":
                    return "..|>";
                case "Include":
                case "Extend":
                case "Dependency":
                case "Usage":
                    return "..>";
                case "Association":
                    String fromAgg = nz(str(edge, "from_aggregation"));
                    if ("composite".equals(fromAgg)) {
                        return "*--";
                    }
                    if ("shared".equals(fromAgg)) {
                        return "o--";
                    }
                    String toAgg = nz(str(edge, "to_aggregation"));
                    if ("composite".equals(toAgg)) {
                        return "--*";
                    }
                    if ("shared".equals(toAgg)) {
                        return "--o";
                    }
                    return "--";
                default:
                    return "-->";
            }
        }

        // ---- Mermaid ----

        String mermaid() {
            boolean classLike = "ClassDiagram".equals(str(diagram, "type"));
            return classLike ? mermaidClass() : mermaidFlow();
        }

        private String mermaidClass() {
            StringBuilder out = new StringBuilder("---\ntitle: ").append(oneLine(str(diagram, "name")))
                    .append("\n---\nclassDiagram\n");
            for (JsonObject node : nodes) {
                String id = alias.get(str(node, "id"));
                out.append("  class ").append(id).append("[\"").append(mermaidText(str(node, "name")))
                        .append("\"]");
                JsonArray members = array(node, "members");
                if (members.size() == 0) {
                    out.append('\n');
                    continue;
                }
                out.append(" {\n");
                for (JsonElement member : members) {
                    out.append("    ").append(memberLine(member.getAsJsonObject())).append('\n');
                }
                out.append("  }\n");
            }
            for (JsonObject edge : edges) {
                String from = alias.get(str(edge, "from"));
                String to = alias.get(str(edge, "to"));
                if (from == null || to == null) {
                    continue;
                }
                String arrow;
                switch (nz(str(edge, "model_type"))) {
                    case "Generalization":
                        arrow = "--|>";
                        break;
                    case "Realization":
                        arrow = "..|>";
                        break;
                    case "Dependency":
                    case "Usage":
                        arrow = "..>";
                        break;
                    default:
                        arrow = "composite".equals(str(edge, "from_aggregation")) ? "*--"
                                : "shared".equals(str(edge, "from_aggregation")) ? "o--" : "--";
                }
                out.append("  ").append(from).append(' ');
                String fromMult = multiplicity(edge, "from_multiplicity");
                String toMult = multiplicity(edge, "to_multiplicity");
                if (fromMult != null) {
                    out.append('"').append(fromMult).append("\" ");
                }
                out.append(arrow);
                if (toMult != null) {
                    out.append(" \"").append(toMult).append('"');
                }
                out.append(' ').append(to);
                String label = str(edge, "name");
                if (label != null && !label.isBlank()) {
                    out.append(" : ").append(mermaidText(label));
                }
                out.append('\n');
            }
            return out.toString();
        }

        private String mermaidFlow() {
            StringBuilder out = new StringBuilder("---\ntitle: ").append(oneLine(str(diagram, "name")))
                    .append("\n---\nflowchart LR\n");
            for (JsonObject root : roots()) {
                mermaidNode(root, out, "  ");
            }
            for (JsonObject edge : edges) {
                String from = alias.get(str(edge, arrowTail(edge)));
                String to = alias.get(str(edge, arrowHead(edge)));
                if (from == null || to == null) {
                    continue;
                }
                String type = nz(str(edge, "model_type"));
                String label = edgeLabel(edge);
                boolean dotted = type.equals("Include") || type.equals("Extend") || type.equals("Dependency")
                        || type.equals("Realization") || type.equals("Usage");
                String arrow = type.equals("Association") ? "---" : dotted ? "-.->" : "-->";
                out.append("  ").append(from).append(' ').append(arrow);
                if (label != null) {
                    out.append("|\"").append(mermaidText(label)).append("\"|");
                }
                out.append(' ').append(to).append('\n');
            }
            return out.toString();
        }

        private void mermaidNode(JsonObject node, StringBuilder out, String indent) {
            String id = alias.get(str(node, "id"));
            String text = mermaidText(str(node, "name"));
            List<JsonObject> children = children(node);
            if (!children.isEmpty()) {
                out.append(indent).append("subgraph ").append(id).append("[\"").append(text).append("\"]\n");
                for (JsonObject child : children) {
                    mermaidNode(child, out, indent + "  ");
                }
                out.append(indent).append("end\n");
                return;
            }
            String type = nz(str(node, "model_type"));
            String open;
            String close;
            switch (type) {
                case "Actor":
                    open = "[/\"";
                    close = "\"/]";
                    text = "«actor» " + text;
                    break;
                case "UseCase":
                    open = "([\"";
                    close = "\"])";
                    break;
                case "DecisionNode":
                case "MergeNode":
                    open = "{\"";
                    close = "\"}";
                    break;
                case "InitialNode":
                case "ActivityFinalNode":
                case "FlowFinalNode":
                    open = "((\"";
                    close = "\"))";
                    break;
                default:
                    open = "[\"";
                    close = "\"]";
            }
            out.append(indent).append(id).append(open).append(text).append(close).append('\n');
        }

        // ---- labels ----

        private String edgeLabel(JsonObject edge) {
            String type = nz(str(edge, "model_type"));
            String name = str(edge, "name");
            List<String> parts = new ArrayList<>();
            if (type.equals("Include") || type.equals("Extend")) {
                parts.add("<<" + type.toLowerCase() + ">>");
            } else if (!type.equals("Association") && !type.equals("Generalization")
                    && !type.equals("Realization") && !type.equals("Dependency")
                    && !type.isEmpty()) {
                parts.add("<<" + type + ">>");
            }
            if (name != null && !name.isBlank()) {
                parts.add(oneLine(name));
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private static String memberLine(JsonObject member) {
        String visibility = visibilitySymbol(str(member, "visibility"));
        String name = nz(str(member, "name"));
        String type = nz(str(member, "model_type"));
        if ("Operation".equals(type)) {
            List<String> params = new ArrayList<>();
            for (JsonElement parameter : array(member, "parameters")) {
                JsonObject p = parameter.getAsJsonObject();
                String pType = str(p, "type");
                params.add(nz(str(p, "name")) + (pType == null || pType.isBlank() ? "" : " : " + pType));
            }
            String ret = str(member, "return_type");
            return visibility + name + "(" + String.join(", ", params) + ")"
                    + (ret == null || ret.isBlank() ? "" : " : " + ret);
        }
        String memberType = str(member, "type");
        if (memberType == null || memberType.isBlank()) {
            memberType = str(member, "type_name");
        }
        return visibility + name + (memberType == null || memberType.isBlank() ? "" : " : " + memberType);
    }

    private static String visibilitySymbol(String visibility) {
        if (visibility == null) {
            return "";
        }
        switch (visibility.toLowerCase()) {
            case "public":
                return "+";
            case "private":
                return "-";
            case "protected":
                return "#";
            case "package":
                return "~";
            default:
                return "";
        }
    }

    private static String multiplicity(JsonObject edge, String key) {
        String value = str(edge, key);
        return value == null || value.isBlank() || "Unspecified".equalsIgnoreCase(value) ? null : value;
    }

    /** A readable identifier from a name; type-prefixed when the name has no letters. */
    static String identifier(String name, String type) {
        String base = name == null ? "" : name.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = (type == null || type.isEmpty() ? "node" : type);
        }
        if (Character.isDigit(base.charAt(0))) {
            base = "n_" + base;
        }
        return base;
    }

    private static String quote(String name) {
        return "\"" + oneLine(name).replace("\"", "'") + "\"";
    }

    private static String mermaidText(String text) {
        return oneLine(text).replace("\"", "#quot;");
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
    }

    private static String str(JsonObject object, String key) {
        if (object == null) {
            return null;
        }
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static boolean isBlank(JsonElement value) {
        return value == null || value.isJsonNull()
                || (value.isJsonPrimitive() && value.getAsString().isBlank())
                || (value.isJsonArray() && value.getAsJsonArray().size() == 0);
    }

    private static void copy(JsonObject from, JsonObject to, String... keys) {
        for (String key : keys) {
            if (from != null && from.has(key) && !isBlank(from.get(key))) {
                to.add(key, from.get(key));
            }
        }
    }

    private static void nonEmpty(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && from.get(key).isJsonArray() && from.getAsJsonArray(key).size() > 0) {
            to.add(key, from.get(key));
        }
    }
}
