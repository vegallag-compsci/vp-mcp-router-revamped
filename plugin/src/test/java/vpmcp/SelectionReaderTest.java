package vpmcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IModelElement;
import org.junit.jupiter.api.Test;
import vpmcp.vp.SelectionReader;

/** Selection JSON contract: one entry per selected element, ids usable in batch ops. */
class SelectionReaderTest {

    @Test
    void shapesAndConnectorsAreDescribedWithIdsForBatchOps() {
        IModelElement actor = VpFakes.model("ma", "Student", "Actor");
        IModelElement useCase = VpFakes.model("mu", "Start Cycle", "UseCase");
        IShapeUIModel actorView = VpFakes.shape("va", actor, "Actor", 10, 20, 30, 60);
        IShapeUIModel useCaseView = VpFakes.shape("vu", useCase, "UseCase", 200, 20, 120, 40);
        IConnectorUIModel link = VpFakes.connector("vl", VpFakes.model("ml", null, "Association"),
                actorView, useCaseView, null);
        IDiagramUIModel diagram = VpFakes.diagram("d1", "UC", "UseCaseDiagram", actorView, useCaseView, link);

        JsonObject result = SelectionReader.describe(diagram, new IDiagramElement[] {actorView, link});

        assertEquals("d1", result.getAsJsonObject("diagram").get("id").getAsString());
        assertEquals(2, result.get("selected_count").getAsInt());
        JsonArray selected = result.getAsJsonArray("selected");
        JsonObject shape = selected.get(0).getAsJsonObject();
        assertEquals("va", shape.get("view_id").getAsString());
        assertEquals("ma", shape.get("model_id").getAsString());
        assertEquals("shape", shape.get("kind").getAsString());
        assertEquals(60, shape.getAsJsonObject("bounds").get("height").getAsInt());
        JsonObject connector = selected.get(1).getAsJsonObject();
        assertEquals("connector", connector.get("kind").getAsString());
        assertEquals("va", connector.get("from").getAsString());
        assertEquals("vu", connector.get("to").getAsString());
    }

    @Test
    void nothingSelectedIsAnEmptyListNotAnError() {
        IDiagramUIModel diagram = VpFakes.diagram("d1", "UC", "UseCaseDiagram");

        JsonObject result = SelectionReader.describe(diagram, new IDiagramElement[0]);

        assertEquals(0, result.get("selected_count").getAsInt());
        assertTrue(result.getAsJsonArray("selected").isEmpty());
        assertEquals(0, SelectionReader.ids(null).size());
    }
}
