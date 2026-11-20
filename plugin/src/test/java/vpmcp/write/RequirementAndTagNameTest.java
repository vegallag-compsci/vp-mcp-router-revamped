package vpmcp.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.model.IRequirement;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tag near-misses are refused; requirement fields go through IRequirement's setters and back. */
class RequirementAndTagNameTest {

    @Test
    void aTagDifferingOnlyInCaseOrSpacingFromAnExistingOneIsRefused() {
        String problem = PlanValidator.tagNameProblem(Set.of("use case status"), "UseCase",
                List.of("Use Case Status", "Author"));

        assertNotNull(problem);
        assertTrue(problem.contains("Use Case Status"), problem);
        assertNull(PlanValidator.tagNameProblem(Set.of("Use Case Status", "Brand New"), "UseCase",
                List.of("Use Case Status")));
    }

    @Test
    void requirementFieldNamesAsTagsOnARequirementPointAtTheRequirementObject() {
        for (String name : new String[] {"id", "ID", "Text", "text"}) {
            String problem = PlanValidator.tagNameProblem(Set.of(name), "Requirement", List.of());
            assertNotNull(problem, name);
            assertTrue(problem.contains("\"requirement\""), problem);
        }
        assertNull(PlanValidator.tagNameProblem(Set.of("ID"), "UseCase", List.of()),
                "only requirements have these fields");
        assertNull(PlanValidator.tagNameProblem(Set.of("risk", "verifyMethod"), "Requirement",
                List.of("risk", "verifyMethod")), "exact built-in tags are fine");
        assertNotNull(PlanValidator.tagNameProblem(Set.of("Risk"), "Requirement", List.of("risk")),
                "a case near-miss of a built-in tag is refused");
    }

    @Test
    void requirementFieldsAreCheckedForShapeKeysAndModelType() {
        assertNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString(
                "{\"id\":\"UR-01\",\"text\":\"The system shall...\"}"), "Requirement"));
        assertNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString("{\"id\":\"UR-01\"}"), null),
                "a plan ref is checked when applied");
        assertNotNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString("{\"id\":\"UR-01\"}"),
                "UseCase"));
        assertNotNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString("{\"status\":\"x\"}"),
                "Requirement"));
        assertNotNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString("{}"), "Requirement"));
        assertNotNull(PlanValidator.requirementFieldsProblem(JsonParser.parseString("{\"id\":{}}"),
                "Requirement"));
    }

    @Test
    void writtenFieldsReadBackAndARestoreUndoesThem() {
        IRequirement req = fakeRequirement();
        JsonObject before = RequirementFields.read(req);

        RequirementFields.write(req, JsonParser.parseString(
                "{\"id\":\"UR-01\",\"risk\":\"High\",\"verify_method\":\"Test\"}").getAsJsonObject());
        JsonObject after = RequirementFields.read(req);
        assertEquals("UR-01", after.get("id").getAsString());
        assertEquals("High", after.get("risk").getAsString());
        assertEquals("Test", after.get("verify_method").getAsString());
        assertEquals(before.get("text"), after.get("text"), "untouched fields stay");

        assertEquals("UR-01", req.getRequirementId(), "the ID tag follows the shown ID");

        RequirementFields.write(req, before);
        assertEquals(before.get("id"), RequirementFields.read(req).get("id"));
        assertEquals("", req.getRequirementId(), "undo restores the tag on its own value");
    }

    /** A requirement whose getters return what the setters stored. */
    private static IRequirement fakeRequirement() {
        Map<String, Object> values = new HashMap<>();
        values.put("UserID", "REQ001");
        values.put("RequirementId", "");
        values.put("Text", "original");
        return (IRequirement) Proxy.newProxyInstance(IRequirement.class.getClassLoader(),
                new Class<?>[] {IRequirement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 1) {
                        values.put(name.substring(3), args[0]);
                        return null;
                    }
                    if (name.startsWith("get") && (args == null || args.length == 0)) {
                        return values.get(name.substring(3));
                    }
                    return null;
                });
    }
}
