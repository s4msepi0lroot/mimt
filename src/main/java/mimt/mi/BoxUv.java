package mimt.mi;

public final class BoxUv {
    public static final int FRONT = 0, BACK = 1, LEFT = 2, RIGHT = 3, TOP = 4, BOTTOM = 5;
    public static final String[] NAMES = {"south", "north", "west", "east", "up", "down"};

    public static int fromBlockName(String n) {
        switch (n) {
            case "south": return FRONT;
            case "north": return BACK;
            case "west": return LEFT;
            case "east": return RIGHT;
            case "up": case "top": return TOP;
            case "down": case "bottom": return BOTTOM;
            default: return -1;
        }
    }

    public static double[] rect(int face, double U, double V, double w, double h, double d, boolean mirror) {
        if (mirror) {
            if (face == LEFT) face = RIGHT;
            else if (face == RIGHT) face = LEFT;
        }
        double[] r;
        switch (face) {
            case FRONT: r = new double[]{U, V, U + w, V + h}; break;
            case BACK: r = new double[]{U + d + w, V, U + d + 2 * w, V + h}; break;
            case LEFT: r = new double[]{U - d, V, U, V + h}; break;
            case RIGHT: r = new double[]{U + w, V, U + w + d, V + h}; break;
            case TOP: r = new double[]{U, V - d, U + w, V}; break;
            default: r = new double[]{U + w, V, U + 2 * w, V - d}; break;
        }
        if (mirror) { double t = r[0]; r[0] = r[2]; r[2] = t; }
        return r;
    }

    public static double[] point(int face, double[] a, double[] b, double s, double t) {
        double w = b[0] - a[0], h = b[1] - a[1], d = b[2] - a[2];
        switch (face) {
            case FRONT: return new double[]{a[0] + s * w, b[1] - t * h, b[2]};
            case BACK: return new double[]{b[0] - s * w, b[1] - t * h, a[2]};
            case LEFT: return new double[]{a[0], b[1] - t * h, a[2] + s * d};
            case RIGHT: return new double[]{b[0], b[1] - t * h, b[2] - s * d};
            case TOP: return new double[]{a[0] + s * w, b[1], a[2] + t * d};
            default: return new double[]{a[0] + s * w, a[1], b[2] - t * d};
        }
    }

    public static final double[][] NORMALS = {{0, 0, 1}, {0, 0, -1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    public static double[] rotateST(double s, double t, int rot) {
        switch (((rot % 360) + 360) % 360) {
            case 90: return new double[]{t, 1 - s};
            case 180: return new double[]{1 - s, 1 - t};
            case 270: return new double[]{1 - t, s};
            default: return new double[]{s, t};
        }
    }
}
