package vpmcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import vpmcp.core.McpTool;
import vpmcp.core.McpToolException;
import vpmcp.core.ToolDefinition;
import vpmcp.write.ConnectionRules;

/**
 * The short list of relationship names and which element types each joins
 * (ADR-0040), so nobody has to read the factory-creates resource to find
 * RQTrace. Static data; works without an open project.
 */
public final class RelationshipRulesTool implements McpTool {

    private static final String DESCRIPTION =
            "List the relationship names connect accepts and which element types each can join, as "
            + "probed live in VP (e.g. RQTrace, Satisfy, Verify and RequirementDerive must target a "
            + "Requirement; Include/Extend join use cases). Optional rel_type narrows to one; "
            + "from_type + to_type lists the relationships that accept that pair. Also lists names VP "
            + "does not have (Trace, Refine, DeriveReqt) with what to use instead. vp_preview_batch "
            + "refuses pairs outside these rules; pairs of types the probe did not cover are not judged.";

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{"
            + "\"rel_type\":{\"type\":\"string\"},\"from_type\":{\"type\":\"string\"},"
            + "\"to_type\":{\"type\":\"string\"}},\"additionalProperties\":false}";

    private final ToolDefinition definition = new ToolDefinition("vp_relationship_rules", DESCRIPTION,
            JsonParser.parseString(INPUT_SCHEMA).getAsJsonObject());

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject params) throws McpToolException {
        return query(ConnectionRules.builtIn(), text(params, "rel_type"), text(params, "from_type"),
                text(params, "to_type"));
    }

    /** The filtered catalog; public static for unit tests. */
    public static JsonObject query(ConnectionRules rules, String relType, String fromType, String toType) {
        JsonObject table = rules.table();
        JsonObject all = table.has("relationships") ? table.getAsJsonObject("relationships") : new JsonObject();
        JsonObject picked = new JsonObject();
        for (Map.Entry<String, JsonElement> rel : all.entrySet()) {
            if (relType != null && !rel.getKey().equalsIgnoreCase(relType)) {
                continue;
            }
            if (fromType != null && toType != null && rules.problem(rel.getKey(), fromType, toType) != null) {
                continue;
            }
            picked.add(rel.getKey(), rel.getValue());
        }
        JsonObject out = new JsonObject();
        out.add("vp_version", table.get("vp_version"));
        out.add("relationships", picked);
        if (relType != null && picked.size() == 0) {
            String hint = rules.notInVp(relType);
            out.addProperty("note", hint != null ? hint : "No probed rule for \"" + relType + "\".");
        } else if (relType == null) {
            out.add("not_in_vp", table.get("not_in_vp"));
        }
        if (fromType != null && toType != null) {
            out.addProperty("note_pairs", "Relationships whose probed rules accept " + fromType + " → " + toType
                    + " (or whose probe did not cover these types).");
        }
        return out;
    }

    private static String text(JsonObject params, String key) {
        JsonElement value = params.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsString().trim().isEmpty()
                ? value.getAsString().trim() : null;
    }
}
