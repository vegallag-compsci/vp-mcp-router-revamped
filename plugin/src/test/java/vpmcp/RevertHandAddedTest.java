package vpmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IProject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import vpmcp.tools.RevertBatchTool;

/** Reverting a batch that created a diagram names what else that diagram holds now. */
class RevertHandAddedTest {

    @Test
    void viewsTheBatchDidNotMakeAreReportedOthersAreNot() {
        IShapeUIModel mine = VpFakes.shape("v1", VpFakes.model("m1", "Mine", "UseCase"), "UseCase", 0, 0, 100, 40);
        IShapeUIModel drawn = VpFakes.shape("v2", VpFakes.model("m2", "Drawn By Hand", "UseCase"), "UseCase",
                0, 80, 100, 40);
        IDiagramUIModel diagram = VpFakes.diagram("d", "Router Test", "UseCaseDiagram", mine, drawn);
        IProject project = VpFakes.of(IProject.class, new HashMap<>(), (method, args) ->
                "getDiagramById".equals(method.getName()) && "d".equals(args[0]) ? diagram : null);

        JsonArray extra = RevertBatchTool.handAddedContent(project, Map.of("d", Set.of("d", "v1", "m1")));

        assertEquals(1, extra.size());
        assertEquals("v2", extra.get(0).getAsJsonObject().get("view_id").getAsString());
        assertEquals(0, RevertBatchTool.handAddedContent(project, Map.of("d", Set.of("v1", "v2"))).size());
        assertEquals(0, RevertBatchTool.handAddedContent(project, Map.of("gone", Set.of())).size());
    }
}
