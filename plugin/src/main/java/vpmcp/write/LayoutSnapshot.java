package vpmcp.write;

import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Geometry of every view on a diagram, taken before an auto-layout (ADR-0035).
 * Shapes keep bounds; connectors keep waypoints and pin ratios. {@link #restore}
 * puts all of it back, which is the layout's undo step; {@link #changed} lists
 * the views the layout actually moved.
 */
public final class LayoutSnapshot {

    private static final int MAX_PASSES = 4;

    private final List<ShapeState> shapes = new ArrayList<>();
    private final List<ConnectorState> connectors = new ArrayList<>();

    private LayoutSnapshot() {
    }

    public static LayoutSnapshot capture(IDiagramUIModel diagram) {
        LayoutSnapshot snapshot = new LayoutSnapshot();
        IDiagramElement[] elements = diagram.toDiagramElementArray();
        if (elements == null) {
            return snapshot;
        }
        for (IDiagramElement element : elements) {
            if (element instanceof IConnectorUIModel) {
                snapshot.connectors.add(new ConnectorState((IConnectorUIModel) element));
            } else if (element instanceof IShapeUIModel) {
                snapshot.shapes.add(new ShapeState((IShapeUIModel) element));
            }
        }
        return snapshot;
    }

    public int shapeCount() {
        return shapes.size();
    }

    public int connectorCount() {
        return connectors.size();
    }

    /** Views whose geometry differs from the snapshot now; shapes first. */
    public List<IDiagramElement> changed() {
        List<IDiagramElement> moved = new ArrayList<>();
        for (ShapeState state : shapes) {
            if (!state.matches()) {
                moved.add(state.shape);
            }
        }
        for (ConnectorState state : connectors) {
            if (!state.matches()) {
                moved.add(state.connector);
            }
        }
        return moved;
    }

    /**
     * Puts every captured view back. Shapes first so connectors re-anchor to
     * their final positions, then waypoints. Continues past failures and
     * throws the first one at the end so the caller reports a partial undo.
     */
    public void restore() {
        RuntimeException first = null;
        // VP stores geometry as doubles and setBounds can land a pixel off
        // (live run, ADR-0035); moving a container may also drag its
        // children. So restore in passes, aiming past each observed error,
        // until every shape reads back exactly.
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean anyOff = false;
            for (ShapeState state : shapes) {
                try {
                    if (!state.matches()) {
                        anyOff = true;
                        state.nudge();
                    }
                } catch (RuntimeException failure) {
                    first = first == null ? failure : first;
                }
            }
            if (!anyOff) {
                break;
            }
        }
        for (ConnectorState state : connectors) {
            try {
                state.restore();
            } catch (RuntimeException failure) {
                first = first == null ? failure : first;
            }
        }
        if (first != null) {
            throw first;
        }
        int off = 0;
        for (ShapeState state : shapes) {
            if (!state.matches()) {
                off++;
            }
        }
        if (off > 0) {
            throw new IllegalStateException(off + " of " + shapes.size()
                    + " shapes did not return to their exact previous bounds (VP rounding).");
        }
    }

    private static final class ShapeState {
        final IShapeUIModel shape;
        final int x;
        final int y;
        final int width;
        final int height;

        ShapeState(IShapeUIModel shape) {
            this.shape = shape;
            this.x = shape.getX();
            this.y = shape.getY();
            this.width = shape.getWidth();
            this.height = shape.getHeight();
        }

        boolean matches() {
            return shape.getX() == x && shape.getY() == y && shape.getWidth() == width
                    && shape.getHeight() == height;
        }

        /** Where the next setBounds aims; moves past each error VP introduced. */
        int aimX = Integer.MIN_VALUE;
        int aimY;
        int aimWidth;
        int aimHeight;

        void nudge() {
            if (aimX == Integer.MIN_VALUE) {
                aimX = x;
                aimY = y;
                aimWidth = width;
                aimHeight = height;
            } else {
                aimX += x - shape.getX();
                aimY += y - shape.getY();
                aimWidth += width - shape.getWidth();
                aimHeight += height - shape.getHeight();
            }
            shape.setBounds(aimX, aimY, aimWidth, aimHeight);
        }
    }

    private static final class ConnectorState {
        final IConnectorUIModel connector;
        final Point[] points;
        final Point2D fromPin;
        final Point2D toPin;

        ConnectorState(IConnectorUIModel connector) {
            this.connector = connector;
            this.points = copy(connector.getPoints());
            this.fromPin = copy(connector.getFromPinRatio());
            this.toPin = copy(connector.getToPinRatio());
        }

        boolean matches() {
            return Arrays.equals(points, copy(connector.getPoints()))
                    && Objects.equals(fromPin, connector.getFromPinRatio())
                    && Objects.equals(toPin, connector.getToPinRatio());
        }

        void restore() {
            if (fromPin != null) {
                connector.setFromPinRatio(fromPin);
            }
            if (toPin != null) {
                connector.setToPinRatio(toPin);
            }
            if (points != null && !Arrays.equals(points, copy(connector.getPoints()))) {
                connector.clearPoints();
                for (Point point : points) {
                    connector.addPoint(new Point(point));
                }
            }
        }

        private static Point[] copy(Point[] source) {
            if (source == null) {
                return null;
            }
            Point[] out = new Point[source.length];
            for (int i = 0; i < source.length; i++) {
                out[i] = source[i] == null ? null : new Point(source[i]);
            }
            return out;
        }

        private static Point2D copy(Point2D source) {
            return source == null ? null : new Point2D.Double(source.getX(), source.getY());
        }
    }
}
