package vpmcp.write;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.vp.plugin.model.IRequirement;
import java.util.Map;

/**
 * A Requirement's box fields, written through IRequirement's own setters
 * (live 2026-11-20): the shown ID is the model's user ID (VP numbers it
 * REQ001, REQ002, ...), Text is the documentation, and source, kind,
 * verifyMethod and risk are built-in tags the setters keep in step.
 */
final class RequirementFields {

    /** Internal key for the ID tag alone; never accepted from ops (the validator lists the public keys). */
    static final String ID_TAG = "_id_tag";

    private RequirementFields() {
    }

    /** Every field as JSON (null when VP has none). */
    static JsonObject read(IRequirement req) {
        JsonObject out = new JsonObject();
        // The box shows the model's user ID (VP's REQ001 auto-number), not the ID tag
        // (live 2026-11-20): "id" is that user ID, and writing it keeps the tag in step.
        out.addProperty("id", req.getUserID());
        out.addProperty("text", req.getText());
        out.addProperty("kind", req.getKind());
        out.addProperty("source", req.getSource());
        out.addProperty("risk", req.getRisk());
        out.addProperty("verify_method", req.getVerifyMethod());
        // Restore-only: the ID tag on its own, so an undo puts back both values exactly.
        out.addProperty(ID_TAG, req.getRequirementId());
        return out;
    }

    /** Writes the fields present in {@code fields}; a JSON null writes an empty string. */
    static void write(IRequirement req, JsonObject fields) {
        for (Map.Entry<String, JsonElement> field : fields.entrySet()) {
            String value = field.getValue() == null || field.getValue().isJsonNull() ? ""
                    : field.getValue().getAsString();
            switch (field.getKey()) {
                case "id":
                    req.setUserID(value);
                    req.setRequirementId(value);
                    break;
                case "text":
                    req.setText(value);
                    break;
                case "kind":
                    req.setKind(value);
                    break;
                case "source":
                    req.setSource(value);
                    break;
                case "risk":
                    req.setRisk(value);
                    break;
                case "verify_method":
                    req.setVerifyMethod(value);
                    break;
                case ID_TAG:
                    req.setRequirementId(value);
                    break;
                default:
                    throw new IllegalStateException("Unknown requirement field \"" + field.getKey() + "\".");
            }
        }
    }
}
