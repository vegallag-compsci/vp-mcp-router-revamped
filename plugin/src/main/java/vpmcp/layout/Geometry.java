package vpmcp.layout;

import java.util.List;

/**
 * Integer geometry for layout checks and, later, layout strategies.
 * Rectangles are {x, y, width, height}; points are {x, y}. Pure functions.
 */
public final class Geometry {

    private Geometry() {
    }

    public static int snap(int value, int grid) {
        return Math.round(value / (float) grid) * grid;
    }

    /** Rounds up to the next multiple of grid (sizes never shrink below content). */
    public static int ceilToGrid(int value, int grid) {
        return ((value + grid - 1) / grid) * grid;
    }

    public static boolean onGrid(int value, int grid) {
        return Math.floorMod(value, grid) == 0;
    }

    public static int centerX(int[] r) {
        return r[0] + r[2] / 2;
    }

    public static int centerY(int[] r) {
        return r[1] + r[3] / 2;
    }

    public static boolean contains(int[] outer, int[] inner) {
        return inner[0] >= outer[0] && inner[1] >= outer[1]
                && inner[0] + inner[2] <= outer[0] + outer[2]
                && inner[1] + inner[3] <= outer[1] + outer[3];
    }

    /** Smallest distance from inner's edges to outer's edges (negative when outside). */
    public static int insetWithin(int[] outer, int[] inner) {
        int left = inner[0] - outer[0];
        int top = inner[1] - outer[1];
        int right = outer[0] + outer[2] - (inner[0] + inner[2]);
        int bottom = outer[1] + outer[3] - (inner[1] + inner[3]);
        return Math.min(Math.min(left, right), Math.min(top, bottom));
    }

    public static boolean intersects(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    /** True when segments p1-p2 and q1-q2 cross at a point interior to both (touching ends do not count). */
    public static boolean segmentsCross(int[] p1, int[] p2, int[] q1, int[] q2) {
        long d1 = orient(q1, q2, p1);
        long d2 = orient(q1, q2, p2);
        long d3 = orient(p1, p2, q1);
        long d4 = orient(p1, p2, q2);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    /** True when two polylines cross somewhere (shared end points and collinear touching are ignored). */
    public static boolean polylinesCross(List<int[]> a, List<int[]> b) {
        for (int i = 0; i + 1 < a.size(); i++) {
            for (int j = 0; j + 1 < b.size(); j++) {
                if (segmentsCross(a.get(i), a.get(i + 1), b.get(j), b.get(j + 1))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the segment passes through the rectangle shrunk by {@code inset}
     * on every side (so lines that merely graze an edge, or end at a shape's
     * border, are not reported).
     */
    public static boolean segmentThroughBox(int[] p1, int[] p2, int[] box, int inset) {
        int x0 = box[0] + inset;
        int y0 = box[1] + inset;
        int x1 = box[0] + box[2] - inset;
        int y1 = box[1] + box[3] - inset;
        if (x1 <= x0 || y1 <= y0) {
            return false;
        }
        if (inside(p1, x0, y0, x1, y1) || inside(p2, x0, y0, x1, y1)) {
            return true;
        }
        int[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        for (int k = 0; k < 4; k++) {
            if (segmentsCross(p1, p2, corners[k], corners[(k + 1) % 4])) {
                return true;
            }
        }
        return false;
    }

    public static boolean polylineThroughBox(List<int[]> line, int[] box, int inset) {
        for (int i = 0; i + 1 < line.size(); i++) {
            if (segmentThroughBox(line.get(i), line.get(i + 1), box, inset)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(int[] p, int x0, int y0, int x1, int y1) {
        return p[0] > x0 && p[0] < x1 && p[1] > y0 && p[1] < y1;
    }

    private static long orient(int[] a, int[] b, int[] c) {
        return (long) (b[0] - a[0]) * (c[1] - a[1]) - (long) (b[1] - a[1]) * (c[0] - a[0]);
    }
}
