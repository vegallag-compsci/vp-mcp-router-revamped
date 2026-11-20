package vpmcp.write;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Layout undo contracts: exact geometry restore, accurate change detection. */
class LayoutSnapshotTest {

    /** Mutable fake view: geometry setters change what getters return. */
    private static final class Geometry {
        int x;
        int y;
        int width;
        int height;
        List<Point> points = new ArrayList<>();
        Point2D fromPin = new Point2D.Double(0.5, 0.5);
        Point2D toPin = new Point2D.Double(0.5, 0.5);
        /** Pixels VP-style rounding loses on each setBounds (x, y, width). */
        int loss;
        boolean frozen;
    }

    private final Map<Object, Geometry> geometry = new HashMap<>();

    private <T extends IDiagramElement> T view(Class<T> type, String id, Geometry state) {
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    switch (method.getName()) {
                        case "getId": return id;
                        case "getX": return state.x;
                        case "getY": return state.y;
                        case "getWidth": return state.width;
                        case "getHeight": return state.height;
                        case "setBounds":
                            if (state.frozen) {
                                return null;
                            }
                            state.x = (int) args[0] - state.loss;
                            state.y = (int) args[1] - state.loss;
                            state.width = (int) args[2] - state.loss;
                            state.height = (int) args[3];
                            return null;
                        case "getPoints": return state.points.toArray(new Point[0]);
                        case "clearPoints": state.points.clear(); return null;
                        case "addPoint": state.points.add((Point) args[0]); return null;
                        case "getFromPinRatio": return state.fromPin;
                        case "getToPinRatio": return state.toPin;
                        case "setFromPinRatio": state.fromPin = (Point2D) args[0]; return null;
                        case "setToPinRatio": state.toPin = (Point2D) args[0]; return null;
                        case "hashCode": return System.identityHashCode(self);
                        case "equals": return self == args[0];
                        default: return null;
                    }
                });
        geometry.put(proxy, state);
        return type.cast(proxy);
    }

    private static Geometry box(int x, int y) {
        Geometry state = new Geometry();
        state.x = x;
        state.y = y;
        state.width = 100;
        state.height = 40;
        return state;
    }

    private IDiagramUIModel diagram(IDiagramElement... elements) {
        return (IDiagramUIModel) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IDiagramUIModel.class}, (self, method, args) ->
                        "toDiagramElementArray".equals(method.getName()) ? elements : null);
    }

    @Test
    void restorePutsEveryShapeAndConnectorBackExactly() {
        Geometry a = box(10, 20);
        Geometry b = box(300, 40);
        Geometry line = new Geometry();
        line.points.add(new Point(110, 40));
        line.points.add(new Point(300, 60));
        IShapeUIModel shapeA = view(IShapeUIModel.class, "A", a);
        IShapeUIModel shapeB = view(IShapeUIModel.class, "B", b);
        IConnectorUIModel connector = view(IConnectorUIModel.class, "L", line);
        LayoutSnapshot snapshot = LayoutSnapshot.capture(diagram(shapeA, shapeB, connector));

        shapeA.setBounds(500, 500, 120, 60);
        connector.clearPoints();
        connector.addPoint(new Point(1, 1));
        connector.addPoint(new Point(2, 2));
        connector.addPoint(new Point(3, 3));
        connector.setToPinRatio(new Point2D.Double(0, 1));

        snapshot.restore();

        assertArrayEquals(new int[] {10, 20, 100, 40}, new int[] {a.x, a.y, a.width, a.height});
        assertArrayEquals(new int[] {300, 40, 100, 40}, new int[] {b.x, b.y, b.width, b.height});
        assertEquals(List.of(new Point(110, 40), new Point(300, 60)), line.points);
        assertEquals(new Point2D.Double(0.5, 0.5), line.toPin);
        assertTrue(snapshot.changed().isEmpty());
    }

    @Test
    void lossySetBoundsIsCorrectedUntilTheShapeReadsBackExactly() {
        Geometry a = box(43, 545);
        IShapeUIModel shape = view(IShapeUIModel.class, "A", a);
        LayoutSnapshot snapshot = LayoutSnapshot.capture(diagram(shape));
        shape.setBounds(1118, 20, 100, 40);
        a.loss = 1;

        snapshot.restore();

        assertArrayEquals(new int[] {43, 545, 100, 40}, new int[] {a.x, a.y, a.width, a.height});
    }

    @Test
    void shapeThatNeverLandsIsReportedNotHidden() {
        Geometry a = box(10, 20);
        Geometry b = box(300, 20);
        IShapeUIModel stuck = view(IShapeUIModel.class, "A", a);
        IShapeUIModel fine = view(IShapeUIModel.class, "B", b);
        LayoutSnapshot snapshot = LayoutSnapshot.capture(diagram(stuck, fine));
        stuck.setBounds(500, 500, 100, 40);
        fine.setBounds(600, 600, 100, 40);
        a.frozen = true;

        IllegalStateException reported = assertThrows(IllegalStateException.class, snapshot::restore);

        assertTrue(reported.getMessage().startsWith("1 of 2"));
        assertArrayEquals(new int[] {300, 20}, new int[] {b.x, b.y}, "the others are still restored");
    }

    @Test
    void changedListsOnlyViewsTheLayoutTouched() {
        IShapeUIModel moved = view(IShapeUIModel.class, "A", box(10, 20));
        IShapeUIModel resized = view(IShapeUIModel.class, "B", box(200, 20));
        IShapeUIModel still = view(IShapeUIModel.class, "C", box(400, 20));
        Geometry line = new Geometry();
        line.points.add(new Point(0, 0));
        IConnectorUIModel rerouted = view(IConnectorUIModel.class, "L", line);
        IConnectorUIModel straight = view(IConnectorUIModel.class, "M", new Geometry());
        LayoutSnapshot snapshot = LayoutSnapshot.capture(diagram(moved, resized, still, rerouted, straight));

        moved.setBounds(11, 20, 100, 40);
        resized.setBounds(200, 20, 101, 40);
        rerouted.addPoint(new Point(5, 5));

        List<IDiagramElement> changed = snapshot.changed();
        assertEquals(3, changed.size());
        assertTrue(changed.contains(moved) && changed.contains(resized) && changed.contains(rerouted));
        assertEquals(3, snapshot.shapeCount());
        assertEquals(2, snapshot.connectorCount());
    }
}
