package vpmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IProject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import vpmcp.tools.RelationshipRulesTool;
import vpmcp.write.ConnectionRules;
import vpmcp.write.PlanValidator;
import vpmcp.write.TypeTiers;

/** Preview refuses endpoint pairs VP rejects at apply; the catalog lists what each relationship joins. */
class ConnectionRulesTest {

    private final ConnectionRules rules = ConnectionRules.builtIn();

    @Test
    void requirementRelationshipsMustTargetARequirement() {
        for (String rel : new String[] {"RQTrace", "Satisfy", "Verify"}) {
            assertNull(rules.problem(rel, "UseCase", "Requirement"), rel);
            String refused = rules.problem(rel, "Requirement", "UseCase");
            assertNotNull(refused, rel);
            assertTrue(refused.contains("swap"), "the reverse is allowed, so the hint says so: " + refused);
        }
    }

    @Test
    void pairRulesAndUnprobedTypesBehave() {
        assertNull(rules.problem("Include", "UseCase", "UseCase"));
        assertNotNull(rules.problem("Include", "Actor", "UseCase"));
        assertNull(rules.problem("Generalization", "Actor", "Actor"));
        assertNotNull(rules.problem("Generalization", "Actor", "Class"));
        assertNull(rules.problem("Include", "UseCase", "Activity"), "types the probe did not cover are not judged");
        assertNull(rules.problem("NoSuchRel", "UseCase", "UseCase"));
    }

    @Test
    void previewRefusesAUseCaseAsTheTargetOfAnRqTrace() {
        IShapeUIModel uc = VpFakes.shape("uc", VpFakes.model("muc", "View Board", "UseCase"), "UseCase", 0, 0, 120, 40);
        IShapeUIModel rq = VpFakes.shape("rq", VpFakes.model("mrq", "Board", "Requirement"), "Requirement",
                200, 0, 200, 100);
        Map<String, Object> byId = Map.of("uc", uc, "rq", rq);
        Map<String, Object> values = new HashMap<>();
        values.put("toDiagramArray", new com.vp.plugin.diagram.IDiagramUIModel[] {
                VpFakes.diagram("d", "Trace", "RequirementDiagram", uc, rq)});
        IProject project = VpFakes.of(IProject.class, values, (method, args) ->
                "getDiagramElementById".equals(method.getName()) && args.length == 1 ? byId.get(args[0]) : null);
        TypeTiers tiers = new TypeTiers(java.util.Set.of("RequirementDiagram"), java.util.Set.of("RQTrace"));

        JsonObject wrong = PlanValidator.validate(project, JsonParser.parseString("[{\"id\":\"c\",\"op\":\"connect\","
                + "\"diagram\":\"d\",\"rel_type\":\"RQTrace\",\"from\":\"rq\",\"to\":\"uc\"}]").getAsJsonArray(), tiers);
        JsonObject right = PlanValidator.validate(project, JsonParser.parseString("[{\"id\":\"c\",\"op\":\"connect\","
                + "\"diagram\":\"d\",\"rel_type\":\"RQTrace\",\"from\":\"uc\",\"to\":\"rq\"}]").getAsJsonArray(), tiers);
        JsonObject fresh = PlanValidator.validate(project, JsonParser.parseString("["
                + "{\"id\":\"u\",\"op\":\"create_element\",\"diagram\":\"d\",\"model_type\":\"UseCase\",\"name\":\"X\"},"
                + "{\"id\":\"c\",\"op\":\"connect\",\"diagram\":\"d\",\"rel_type\":\"RQTrace\",\"from\":\"rq\","
                + "\"to\":{\"ref\":\"u\"}}]").getAsJsonArray(), tiers);

        assertFalse(wrong.get("valid").getAsBoolean(), wrong.toString());
        assertTrue(right.get("valid").getAsBoolean(), right.toString());
        assertFalse(fresh.get("valid").getAsBoolean(), "a use case created earlier in the batch is checked too");
    }

    @Test
    void anUnknownNameGetsTheRightOneAndTheCatalogFilters() {
        IProject empty = VpFakes.of(IProject.class, new HashMap<>(), (method, args) -> null);
        JsonObject trace = PlanValidator.validate(empty, JsonParser.parseString("["
                + "{\"id\":\"d\",\"op\":\"create_diagram\",\"diagram_type\":\"UseCaseDiagram\",\"name\":\"T\"},"
                + "{\"id\":\"c\",\"op\":\"connect\",\"diagram\":{\"ref\":\"d\"},\"rel_type\":\"Trace\","
                + "\"from\":\"a\",\"to\":\"b\"}]").getAsJsonArray());
        assertTrue(trace.toString().contains("RQTrace"), trace.toString());

        JsonObject toReq = RelationshipRulesTool.query(rules, null, "UseCase", "Requirement");
        assertTrue(toReq.getAsJsonObject("relationships").has("Satisfy"));
        assertFalse(toReq.getAsJsonObject("relationships").has("Include"));
        JsonObject one = RelationshipRulesTool.query(rules, "trace", null, null);
        assertTrue(one.get("note").getAsString().contains("RQTrace"));
    }
}
