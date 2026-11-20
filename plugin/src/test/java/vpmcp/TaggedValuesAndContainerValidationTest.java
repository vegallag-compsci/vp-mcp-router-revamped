package vpmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vpmcp.write.PlanValidator;

/** Preview contracts for update_element tagged_values and create_element container. */
class TaggedValuesAndContainerValidationTest {

    private final IProject project = projectWith();

    private static IProject projectWith() {
        IModelElement model = VpFakes.model("mx", "Start Cycle", "UseCase");
        IShapeUIModel boundary = VpFakes.shape("sys", VpFakes.model("ms", "Shop", "System"),
                "System", 0, 0, 500, 500);
        Map<String, Object> values = new HashMap<>();
        values.put("toDiagramArray", new IDiagramUIModel[] {VpFakes.diagram("d1", "UC", "UseCaseDiagram")});
        return VpFakes.of(IProject.class, values, (method, args) -> {
            if ("getModelElementById".equals(method.getName()) && "mx".equals(args[0])) {
                return model;
            }
            if ("getDiagramElementById".equals(method.getName()) && "sys".equals(args[0])) {
                return boundary;
            }
            return null;
        });
    }

    private JsonObject validate(String ops) {
        JsonArray parsed = JsonParser.parseString(ops).getAsJsonArray();
        return PlanValidator.validate(project, parsed);
    }

    @Test
    void taggedValuesAloneAreAValidUpdateAndNameTheTags() {
        JsonObject result = validate("[{\"id\":\"u\",\"op\":\"update_element\",\"model\":\"mx\","
                + "\"tagged_values\":{\"Preconditions\":\"Logged in\",\"Old\":null,\"Rank\":2,\"Done\":true}}]");

        assertTrue(result.get("valid").getAsBoolean(), result.toString());
        String summary = result.getAsJsonArray("plan").get(0).getAsJsonObject().get("summary").getAsString();
        assertTrue(summary.contains("Preconditions") && summary.contains("Old"), summary);
    }

    @Test
    void malformedTaggedValuesAreRejected() {
        for (String bad : new String[] {"\"text\"", "{}", "{\"\":\"x\"}", "{\"Tag\":{\"nested\":1}}",
                "{\"Tag\":[1,2]}"}) {
            JsonObject result = validate("[{\"id\":\"u\",\"op\":\"update_element\",\"model\":\"mx\","
                    + "\"tagged_values\":" + bad + "}]");
            assertFalse(result.get("valid").getAsBoolean(), "should reject tagged_values " + bad);
        }
    }

    @Test
    void createInsideAnExistingContainerValidates() {
        JsonObject result = validate("[{\"id\":\"c\",\"op\":\"create_element\",\"diagram\":\"d1\","
                + "\"model_type\":\"UseCase\",\"name\":\"Pay\",\"x\":20,\"y\":20,\"container\":\"sys\"}]");

        assertTrue(result.get("valid").getAsBoolean(), result.toString());
        String summary = result.getAsJsonArray("plan").get(0).getAsJsonObject().get("summary").getAsString();
        assertTrue(summary.contains("sys"), summary);
    }

    @Test
    void createInsideAPlannedContainerValidates() {
        JsonObject result = validate("["
                + "{\"id\":\"box\",\"op\":\"create_element\",\"diagram\":\"d1\",\"model_type\":\"Class\","
                + " \"name\":\"Holder\"},"
                + "{\"id\":\"c\",\"op\":\"create_element\",\"diagram\":\"d1\",\"model_type\":\"UseCase\","
                + " \"name\":\"Pay\",\"container\":{\"ref\":\"box\"}}]");

        assertTrue(result.get("valid").getAsBoolean(), result.toString());
    }

    @Test
    void unknownContainerIsRejected() {
        JsonObject result = validate("[{\"id\":\"c\",\"op\":\"create_element\",\"diagram\":\"d1\","
                + "\"model_type\":\"UseCase\",\"name\":\"Pay\",\"container\":\"ghost\"}]");

        assertFalse(result.get("valid").getAsBoolean());
        assertTrue(result.toString().contains("container"));
    }
}
