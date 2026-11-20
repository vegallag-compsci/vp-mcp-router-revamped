package vpmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import vpmcp.tools.ApplyBatchTool;

/** The default apply result keeps what an agent acts on and drops the repeated plan. */
class ApplySummaryTest {

    private static JsonObject fullResult(int ops) {
        JsonObject full = new JsonObject();
        full.addProperty("mutated", true);
        JsonObject validation = new JsonObject();
        validation.addProperty("valid", true);
        JsonArray plan = new JsonArray();
        JsonArray applied = new JsonArray();
        for (int i = 0; i < ops; i++) {
            JsonObject step = new JsonObject();
            step.addProperty("id", "o" + i);
            step.addProperty("summary", "a fairly long human-readable summary of what op " + i + " does here");
            step.addProperty("undo", "remove it from its diagram and delete its model");
            plan.add(step);
            JsonObject entry = new JsonObject();
            entry.addProperty("id", "o" + i);
            entry.addProperty("kind", i % 2 == 0 ? "element" : "relationship");
            entry.addProperty("vp_id", "m" + i);
            entry.addProperty("view_id", "v" + i);
            entry.addProperty("name", "Element number " + i);
            entry.addProperty("documentation", "Documentation text that is echoed back in full " + i);
            if (i == 3) {
                entry.addProperty("name_warning", "VP kept another name");
            }
            applied.add(entry);
        }
        validation.add("plan", plan);
        validation.add("errors", new JsonArray());
        full.add("validation", validation);
        full.add("applied", applied);
        full.add("compensated", new JsonArray());
        full.add("errors", new JsonArray());
        full.addProperty("batch_id", "b1");
        return full;
    }

    @Test
    void summaryKeepsIdsWarningsAndBatchIdAndIsMuchSmaller() {
        JsonObject full = fullResult(40);
        JsonObject summary = ApplyBatchTool.summarize(full);

        assertTrue(summary.toString().length() * 2 < full.toString().length(),
                summary.toString().length() + " vs " + full.toString().length());
        assertEquals("b1", summary.get("batch_id").getAsString());
        assertFalse(summary.has("validation"), "the plan is not repeated");
        JsonArray lines = summary.getAsJsonArray("applied");
        assertEquals(40, lines.size());
        int counted = 0;
        for (java.util.Map.Entry<String, JsonElement> kind : summary.getAsJsonObject("counts").entrySet()) {
            counted += kind.getValue().getAsInt();
        }
        assertEquals(40, counted);
        JsonObject warned = lines.get(3).getAsJsonObject();
        assertEquals("v3", warned.get("view_id").getAsString());
        assertTrue(warned.has("name_warning"), "warnings survive the summary");
        assertFalse(lines.get(2).getAsJsonObject().has("documentation"), "echoed fields are dropped");
    }
}
