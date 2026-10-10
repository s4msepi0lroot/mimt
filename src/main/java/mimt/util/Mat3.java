package mimt.util;

public final class Mat3 {
    public final double[][] m;

    public Mat3(double[][] m) { this.m = m; }

    public static Mat3 identity() { return new Mat3(new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}); }

    public static Mat3 rx(double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new Mat3(new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}});
    }

    public static Mat3 ry(double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new Mat3(new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}});
    }

    public static Mat3 rz(double deg) {
        double r = Math.toRadians(deg), c = Math.cos(r), s = Math.sin(r);
        return new Mat3(new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}});
    }

    public Mat3 mul(Mat3 o) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                r[i][j] = m[i][0] * o.m[0][j] + m[i][1] * o.m[1][j] + m[i][2] * o.m[2][j];
        return new Mat3(r);
    }

    public double[] apply(double[] v) {
        return new double[]{
                m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }

    public static Mat3 mi(double[] rot) {
        if (rot == null) return identity();
        return ry(rot[1]).mul(rx(rot[0])).mul(rz(rot[2]));
    }

    public double[] toMiEuler() {
        double sx = Math.max(-1, Math.min(1, -m[1][2]));
        double x = Math.asin(sx), y, z;
        if (Math.abs(sx) < 0.999999) {
            z = Math.atan2(m[1][0], m[1][1]);
            y = Math.atan2(m[0][2], m[2][2]);
        } else {
            z = 0;
            y = Math.atan2(-m[2][0], m[0][0]);
        }
        return new double[]{clean(Math.toDegrees(x)), clean(Math.toDegrees(y)), clean(Math.toDegrees(z))};
    }

    public static double clean(double v) {
        double r = Math.round(v * 10000.0) / 10000.0;
        if (r == 0) r = 0;
        return r;
    }

    public boolean isIdentity() {
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (Math.abs(m[i][j] - (i == j ? 1 : 0)) > 1e-9) return false;
        return true;
    }
}
