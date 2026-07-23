package com.teamundefined.defined.pedro;

import com.pedropathing.geometry.Pose;

/**
 * A field zone you can test points against — a triangle, or an axis‑aligned rectangle.
 *
 * <p>Triangle containment uses barycentric coordinates with the edge vectors and the
 * denominator precomputed in the constructor, so {@link #contains(double, double)} costs
 * four multiplications and a handful of adds — no trig, no square roots. That matters:
 * zone checks typically run every loop on a Control Hub.
 *
 * <pre>{@code
 * // A triangular shooting zone, plus a 10" approach buffer around it
 * Perimeter zone   = Perimeter.triangle(0, 0, 60, 0, 60, 60);
 * Perimeter buffer = zone.expand(10);
 *
 * if (buffer.contains(pose) && !zone.contains(pose)) {
 *     // approaching the zone — start spinning up
 * }
 * }</pre>
 *
 * <p>Rectangles are a separate, faster implementation ({@link #rectangle}) that reduces
 * to four comparisons. All coordinates are in your localizer's field units (Pedro uses
 * inches by default).
 *
 * <p>Instances are immutable; {@link #expand(double)} returns a new perimeter.
 */
public class Perimeter {
    // Triangle vertices
    private final double ax, ay;
    private final double bx, by;
    private final double cx, cy;

    // Precomputed edge vectors (saves 6 subtractions per contains() call)
    private final double v0x, v0y;  // C - A
    private final double v1x, v1y;  // B - A

    // Precomputed denominator for barycentric coords
    private final double invDenom;

    /**
     * Triangle from three vertices, in either winding order.
     * A degenerate (zero-area) triangle contains nothing.
     */
    public Perimeter(double ax, double ay, double bx, double by, double cx, double cy) {
        this.ax = ax;
        this.ay = ay;
        this.bx = bx;
        this.by = by;
        this.cx = cx;
        this.cy = cy;

        this.v0x = cx - ax;
        this.v0y = cy - ay;
        this.v1x = bx - ax;
        this.v1y = by - ay;

        double denom = v0x * v1y - v0y * v1x;
        this.invDenom = (denom != 0) ? 1.0 / denom : 0;
    }

    /** Triangle from three poses; headings are ignored. */
    public Perimeter(Pose a, Pose b, Pose c) {
        this(a.getX(), a.getY(), b.getX(), b.getY(), c.getX(), c.getY());
    }

    /**
     * @return true if the point is inside the triangle or on its edge
     */
    public boolean contains(double x, double y) {
        double v2x = x - ax;
        double v2y = y - ay;

        double u = (v0x * v2y - v0y * v2x) * invDenom;
        double v = (v1x * v2y - v1y * v2x) * -invDenom;

        return (u >= 0) && (v >= 0) && (u + v <= 1);
    }

    /** {@link #contains(double, double)} for a pose; heading is ignored. */
    public boolean contains(Pose pose) {
        return pose != null && contains(pose.getX(), pose.getY());
    }

    /**
     * Nearest point on the boundary to {@code (x, y)}, pushed {@value #PUSH_MARGIN}" further
     * outward (away from the centroid) for clearance. Use this to evict a target pose that
     * landed inside a no-go zone.
     *
     * @return {@code double[2]} of the pushed-out {x, y}
     */
    public double[] pushOutside(double x, double y) {
        double centroidX = (ax + bx + cx) / 3.0;
        double centroidY = (ay + by + cy) / 3.0;

        double bestDist = Double.MAX_VALUE;
        double bestPx = x, bestPy = y;

        double[] proj = projectOntoSegment(x, y, ax, ay, bx, by);
        double dist = distSq(x, y, proj[0], proj[1]);
        if (dist < bestDist) { bestDist = dist; bestPx = proj[0]; bestPy = proj[1]; }

        proj = projectOntoSegment(x, y, bx, by, cx, cy);
        dist = distSq(x, y, proj[0], proj[1]);
        if (dist < bestDist) { bestDist = dist; bestPx = proj[0]; bestPy = proj[1]; }

        proj = projectOntoSegment(x, y, cx, cy, ax, ay);
        dist = distSq(x, y, proj[0], proj[1]);
        if (dist < bestDist) { bestPx = proj[0]; bestPy = proj[1]; }

        double dx = bestPx - centroidX;
        double dy = bestPy - centroidY;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001) {
            return new double[]{x, y}; // degenerate — return original
        }
        return new double[]{bestPx + (dx / len) * PUSH_MARGIN, bestPy + (dy / len) * PUSH_MARGIN};
    }

    /** Clearance added beyond the boundary by {@link #pushOutside}, in field units. */
    protected static final double PUSH_MARGIN = 1.0;

    /**
     * Projects {@code (px,py)} onto the segment, clamped to the segment's bounds.
     */
    private static double[] projectOntoSegment(double px, double py,
                                               double sx, double sy,
                                               double ex, double ey) {
        double edx = ex - sx, edy = ey - sy;
        double lenSq = edx * edx + edy * edy;
        if (lenSq < 0.0001) return new double[]{sx, sy}; // degenerate segment

        double t = ((px - sx) * edx + (py - sy) * edy) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return new double[]{sx + t * edx, sy + t * edy};
    }

    private static double distSq(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    /**
     * A larger perimeter, each vertex moved {@code radius} outward from the centroid.
     * Useful for buffer/approach zones around a core zone.
     *
     * <p><b>Caveat — this is not a true polygon offset.</b> Each vertex is displaced by
     * {@code radius} along <em>each coordinate axis independently</em>, not along the
     * outward normal. Axis-aligned edges move by {@code radius}, but a diagonal edge can
     * move less — or not at all. For the right triangle {@code (0,0),(10,0),(0,10)}, whose
     * hypotenuse lies on {@code x + y = 10}, {@code expand(5)} produces
     * {@code (-5,-5),(15,-5),(-5,15)} — a hypotenuse still on {@code x + y = 10}. The zone
     * grows along the legs and not one inch along the diagonal.
     *
     * <p>So a buffer built this way is <em>not</em> uniformly larger. If you need guaranteed
     * clearance in every direction, size the triangle explicitly rather than relying on
     * {@code expand}. This behavior is preserved deliberately: it is what Team Undefined's
     * competition zones were tuned against.
     *
     * <p>{@link #rectangle} perimeters do not have this problem — every edge is axis-aligned,
     * so their {@code expand} grows the box by exactly {@code radius} on all four sides.
     */
    public Perimeter expand(double radius) {
        double centroidX = (ax + bx + cx) / 3.0;
        double centroidY = (ay + by + cy) / 3.0;

        return new Perimeter(
                expandPoint(ax, centroidX, radius), expandPoint(ay, centroidY, radius),
                expandPoint(bx, centroidX, radius), expandPoint(by, centroidY, radius),
                expandPoint(cx, centroidX, radius), expandPoint(cy, centroidY, radius));
    }

    private static double expandPoint(double coord, double center, double radius) {
        double delta = coord - center;
        double dist = Math.abs(delta);
        if (dist < 0.001) return coord; // avoid division by zero
        return coord + (delta / dist) * radius;
    }

    /** Triangle from coordinates. */
    public static Perimeter triangle(double ax, double ay,
                                     double bx, double by,
                                     double cx, double cy) {
        return new Perimeter(ax, ay, bx, by, cx, cy);
    }

    /** Triangle from poses; headings are ignored. */
    public static Perimeter triangle(Pose a, Pose b, Pose c) {
        return new Perimeter(a.getX(), a.getY(), b.getX(), b.getY(), c.getX(), c.getY());
    }

    /**
     * Axis-aligned rectangle from two opposite corners (in any order). Containment is
     * four comparisons — cheaper than the triangle path.
     */
    public static Perimeter rectangle(double x1, double y1, double x2, double y2) {
        return new RectanglePerimeter(x1, y1, x2, y2);
    }

    /**
     * Axis-aligned box. Overrides every geometric operation — the superclass state is a
     * dummy degenerate triangle and must never be consulted for this subtype.
     */
    private static class RectanglePerimeter extends Perimeter {
        private final double minX, minY, maxX, maxY;

        RectanglePerimeter(double x1, double y1, double x2, double y2) {
            super(0, 0, 0, 0, 0, 0); // unused; all operations are overridden below

            this.minX = Math.min(x1, x2);
            this.minY = Math.min(y1, y2);
            this.maxX = Math.max(x1, x2);
            this.maxY = Math.max(y1, y2);
        }

        @Override
        public boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        @Override
        public Perimeter expand(double radius) {
            return new RectanglePerimeter(minX - radius, minY - radius,
                                          maxX + radius, maxY + radius);
        }

        @Override
        public double[] pushOutside(double x, double y) {
            // Distance to each of the four edges; push out through the nearest one.
            double dLeft = x - minX, dRight = maxX - x;
            double dBottom = y - minY, dTop = maxY - y;

            double best = Math.min(Math.min(dLeft, dRight), Math.min(dBottom, dTop));
            if (best == dLeft)   return new double[]{minX - PUSH_MARGIN, y};
            if (best == dRight)  return new double[]{maxX + PUSH_MARGIN, y};
            if (best == dBottom) return new double[]{x, minY - PUSH_MARGIN};
            return new double[]{x, maxY + PUSH_MARGIN};
        }
    }
}
