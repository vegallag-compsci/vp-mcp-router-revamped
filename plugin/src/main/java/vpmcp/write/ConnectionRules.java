package vpmcp.write;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import vpmcp.vp.VpLog;

/**
 * Which element types each relationship can join, as probed live (ADR-0040).
 * Preview uses it to refuse pairs VP would reject at apply ("UseCase is not
 * allowed with RQTrace"); pairs outside the probed types are not judged.
 */
public final class ConnectionRules {

    private static final ConnectionRules BUILT_IN = load();

    private final JsonObject data;

    ConnectionRules(JsonObject data) {
        this.data = data;
    }

    public static ConnectionRules builtIn() {
        return BUILT_IN;
    }

    private static ConnectionRules load() {
        try (InputStream in = ConnectionRules.class.getResourceAsStream("/vpmcp/rules/connection-rules.json")) {
            if (in == null) {
                return new ConnectionRules(new JsonObject());
            }
            return new ConnectionRules(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject());
        } catch (Exception broken) {
            VpLog.info("CONNECTION RULES unreadable: " + broken);
            return new ConnectionRules(new JsonObject());
        }
    }

    /** The whole table, for the catalog tool. */
    public JsonObject table() {
        return data.deepCopy();
    }

    /** A hint for a relationship name VP does not have (e.g. Trace), or null. */
    public String notInVp(String relType) {
        JsonObject missing = object(data, "not_in_vp");
        if (missing == null || relType == null) {
            return null;
        }
        for (Map.Entry<String, JsonElement> entry : missing.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(relType)) {
                return entry.getValue().getAsString();
            }
        }
        return null;
    }

    /**
     * Null when the pair is allowed or not covered by the probe; otherwise why
     * VP will refuse it and what it does accept.
     */
    public String problem(String relType, String fromType, String toType) {
        JsonObject rule = object(object(data, "relationships"), relType);
        if (rule == null || fromType == null || toType == null) {
            return null;
        }
        JsonArray probed = rule.has("probed") ? rule.getAsJsonArray("probed") : new JsonArray();
        if (!contains(probed, fromType) || !contains(probed, toType)) {
            return null;
        }
        if (allowed(rule, fromType, toType)) {
            return null;
        }
        String reversed = allowed(rule, toType, fromType) ? " The reverse direction is allowed: swap from and to." : "";
        return relType + " cannot join " + fromType + " → " + toType + " (VP refuses it: \"" + toType
                + " is not allowed with " + relType + "\"). It accepts " + describe(rule) + "." + reversed;
    }

    private static boolean allowed(JsonObject rule, String fromType, String toType) {
        if (rule.has("pairs")) {
            for (JsonElement pair : rule.getAsJsonArray("pairs")) {
                JsonArray ends = pair.getAsJsonArray();
                if (ends.get(0).getAsString().equals(fromType) && ends.get(1).getAsString().equals(toType)) {
                    return true;
                }
            }
            return false;
        }
        return matches(rule.get("from"), fromType) && matches(rule.get("to"), toType);
    }

    static String describe(JsonObject rule) {
        if (rule.has("pairs")) {
            StringBuilder out = new StringBuilder();
            for (JsonElement pair : rule.getAsJsonArray("pairs")) {
                JsonArray ends = pair.getAsJsonArray();
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(ends.get(0).getAsString()).append(" → ").append(ends.get(1).getAsString());
            }
            return out.toString();
        }
        return side(rule.get("from")) + " → " + side(rule.get("to"));
    }

    private static String side(JsonElement value) {
        if (value == null || value.isJsonPrimitive()) {
            return "any element";
        }
        StringBuilder out = new StringBuilder();
        for (JsonElement type : value.getAsJsonArray()) {
            out.append(out.length() == 0 ? "" : " or ").append(type.getAsString());
        }
        return out.toString();
    }

    private static boolean matches(JsonElement value, String type) {
        if (value == null || value.isJsonPrimitive() && "*".equals(value.getAsString())) {
            return true;
        }
        return value.isJsonArray() && contains(value.getAsJsonArray(), type);
    }

    private static boolean contains(JsonArray values, String type) {
        for (JsonElement value : values) {
            if (value.getAsString().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject object(JsonObject holder, String key) {
        JsonElement value = holder == null ? null : holder.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }
}
