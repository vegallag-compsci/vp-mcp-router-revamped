package vpmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IProject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vpmcp.write.PlanValidator;

/** route_connector contracts: connectors only, a full path, refs to connect ops. */
class RouteConnectorValidationTest {

    private final IProject project = project();

    private static IProject project() {
        IShapeUIModel a = VpFakes.shape("a", VpFakes.model("ma", "A", "Actor"), "Actor", 0, 0, 30, 60);
        IShapeUIModel u = VpFakes.shape("u", VpFakes.model("mu", "U", "UseCase"), "UseCase", 200, 0, 120, 40);
        IConnectorUIModel c = VpFakes.connector("c", VpFakes.model("mc", "", "Association"), a, u, null);
        Map<String, Object> byId = Map.of("a", a, "u", u, "c", c);
        Map<String, Object> values = new HashMap<>();
        values.put("toDiagramArray", new com.vp.plugin.diagram.IDiagramUIModel[] {
                VpFakes.diagram("d", "UC", "UseCaseDiagram", a, u, c)});
        return VpFakes.of(IProject.class, values, (method, args) ->
                "getDiagramElementById".equals(method.getName()) && args.length == 1 ? byId.get(args[0]) : null);
    }

    private JsonObject validate(String ops) {
        return PlanValidator.validate(project, JsonParser.parseString(ops).getAsJsonArray());
    }

    @Test
    void existingConnectorWithAFullPathIsValid() {
        JsonObject result = validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"c\","
                + "\"points\":[{\"x\":30,\"y\":30},{\"x\":200,\"y\":20}]}]");

        assertTrue(result.get("valid").getAsBoolean(), result.toString());
    }

    @Test
    void aShapeIsRefusedWithAHintToMoveIt() {
        JsonObject result = validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"u\","
                + "\"points\":[{\"x\":30,\"y\":30},{\"x\":200,\"y\":20}]}]");

        assertFalse(result.get("valid").getAsBoolean());
        assertTrue(result.toString().contains("move_element"));
    }

    @Test
    void missingOrShortPathsAreRefused() {
        assertFalse(validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"c\"}]")
                .get("valid").getAsBoolean());
        assertFalse(validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"c\","
                + "\"points\":[{\"x\":30,\"y\":30}]}]").get("valid").getAsBoolean());
        assertFalse(validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"nope\","
                + "\"points\":[{\"x\":30,\"y\":30},{\"x\":1,\"y\":1}]}]").get("valid").getAsBoolean());
    }

    @Test
    void aBatchWhoseIdsAllMissPointsAtTheOpenProject() {
        JsonObject allMiss = validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"gone\","
                + "\"points\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":1}]},"
                + "{\"id\":\"m\",\"op\":\"move_element\",\"element\":\"gone2\",\"x\":10}]");
        JsonObject oneMiss = validate("[{\"id\":\"r\",\"op\":\"route_connector\",\"element\":\"c\","
                + "\"points\":[{\"x\":0,\"y\":0},{\"x\":1,\"y\":1}]},"
                + "{\"id\":\"m\",\"op\":\"move_element\",\"element\":\"gone2\",\"x\":10}]");

        assertTrue(allMiss.has("hint") && allMiss.get("hint").getAsString().contains("open project"));
        assertFalse(oneMiss.has("hint"), "a single stale id is an ordinary error, not a project switch");
    }

    @Test
    void aConnectorCreatedEarlierInTheBatchCanBeRouted() {
        JsonObject result = validate("[{\"id\":\"k\",\"op\":\"connect\",\"diagram\":\"d\",\"rel_type\":\"Association\","
                + "\"from\":\"a\",\"to\":\"u\"},"
                + "{\"id\":\"r\",\"op\":\"route_connector\",\"element\":{\"ref\":\"k\"},"
                + "\"points\":[{\"x\":30,\"y\":30},{\"x\":200,\"y\":20}]}]");

        assertTrue(result.get("valid").getAsBoolean(), result.toString());
    }
}
