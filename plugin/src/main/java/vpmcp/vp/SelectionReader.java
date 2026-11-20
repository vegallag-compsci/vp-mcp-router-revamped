package vpmcp.vp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IModelElement;
import vpmcp.core.McpToolException;

/**
 * What the operator is looking at (ADR-0027): the diagram in front in the
 * editor and the elements selected on it. Reading VP's singletons is split
 * from describing them so the JSON contract is testable with fakes.
 */
public final class SelectionReader {

    private SelectionReader() {
    }

    /** The active diagram, or a structured error when no diagram is in front. */
    public static IDiagramUIModel requireActiveDiagram() throws McpToolException {
        DiagramManager diagrams = diagramManager();
        IDiagramUIModel active = diagrams.getActiveDiagram();
        if (active == null) {
            throw new McpToolException("No diagram is active in Visual Paradigm; ask the user to "
                    + "open the diagram they mean, or pass a diagram id.");
        }
        return active;
    }

    /** Selected elements on the active diagram; empty (never null) when nothing is selected. */
    public static IDiagramElement[] selectedElements(IDiagramUIModel active) throws McpToolException {
        IDiagramElement[] selected = null;
        try {
            selected = diagramManager().getSelectedDiagramElements();
        } catch (RuntimeException unavailable) {
            // fall back to the diagram's own selection below
        }
        if ((selected == null || selected.length == 0) && active != null) {
            selected = active.getSelectedDiagramElement();
        }
        return selected == null ? new IDiagramElement[0] : selected;
    }

    private static DiagramManager diagramManager() throws McpToolException {
        ApplicationManager application = ApplicationManager.instance();
        DiagramManager diagrams = application == null ? null : application.getDiagramManager();
        if (diagrams == null) {
            throw new McpToolException("The Visual Paradigm Open API is unavailable; "
                    + "this code is not running inside Visual Paradigm.");
        }
        return diagrams;
    }

    /** {diagram: {id, name, type} | null, selected_count, selected: [..]}. */
    public static JsonObject describe(IDiagramUIModel active, IDiagramElement[] selected) {
        JsonObject result = new JsonObject();
        if (active == null) {
            result.add("diagram", null);
        } else {
            JsonObject diagram = new JsonObject();
            diagram.addProperty("id", active.getId());
            diagram.addProperty("name", active.getName());
            diagram.addProperty("type", active.getType());
            result.add("diagram", diagram);
        }
        JsonArray entries = new JsonArray();
        if (selected != null) {
            for (IDiagramElement element : selected) {
                if (element != null) {
                    entries.add(describeElement(element));
                }
            }
        }
        result.addProperty("selected_count", entries.size());
        result.add("selected", entries);
        return result;
    }

    /** Ids of the selected elements, in selection order. */
    public static JsonArray ids(IDiagramElement[] selected) {
        JsonArray ids = new JsonArray();
        if (selected != null) {
            for (IDiagramElement element : selected) {
                if (element != null) {
                    ids.add(element.getId());
                }
            }
        }
        return ids;
    }

    private static JsonObject describeElement(IDiagramElement element) {
        JsonObject entry = new JsonObject();
        entry.addProperty("view_id", element.getId());
        IModelElement model = element.getModelElement();
        entry.addProperty("model_id", model == null ? null : model.getId());
        entry.addProperty("name", model == null ? null : model.getName());
        entry.addProperty("model_type", model == null ? null : model.getModelType());
        entry.addProperty("shape_type", element.getShapeType());
        if (element instanceof IConnectorUIModel) {
            IConnectorUIModel connector = (IConnectorUIModel) element;
            entry.addProperty("kind", "connector");
            entry.addProperty("from", connector.getFromShape() == null ? null : connector.getFromShape().getId());
            entry.addProperty("to", connector.getToShape() == null ? null : connector.getToShape().getId());
        } else {
            entry.addProperty("kind", "shape");
            JsonObject bounds = new JsonObject();
            bounds.addProperty("x", element.getX());
            bounds.addProperty("y", element.getY());
            bounds.addProperty("width", element.getWidth());
            bounds.addProperty("height", element.getHeight());
            entry.add("bounds", bounds);
            if (element instanceof IShapeUIModel) {
                IDiagramElement parent = ((IShapeUIModel) element).getParent();
                entry.addProperty("parent_id", parent == null ? null : parent.getId());
            }
        }
        return entry;
    }
}
