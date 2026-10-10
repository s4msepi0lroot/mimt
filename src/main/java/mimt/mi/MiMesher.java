package mimt.mi;

import mimt.util.Mat3;
import java.awt.image.BufferedImage;
import java.util.*;

public final class MiMesher {
    public static final class Quad {
        public final double[][] p = new double[4][];
        public final double[][] uv = new double[4][];
        public BufferedImage tex;
        public double[] n;
    }

    static final class Xf {
        final double[][] m;
        final double[] t;

        Xf(double[][] m, double[] t) { this.m = m; this.t = t; }

        static Xf id() { return new Xf(Mat3.identity().m, new double[]{0, 0, 0}); }

        double[] apply(double[] v) {
            return new double[]{
                    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2] + t[0],
                    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2] + t[1],
                    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2] + t[2]};
        }

        Xf then(double[] pos, double[] rot, double[] scale) {
            double[][] r = Mat3.mi(rot).m;
            double[][] rs = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) rs[i][j] = r[i][j] * (scale == null ? 1 : scale[j]);
            double[][] nm = new double[3][3];
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) nm[i][j] = m[i][0] * rs[0][j] + m[i][1] * rs[1][j] + m[i][2] * rs[2][j];
            double[] p = pos == null ? new double[]{0, 0, 0} : pos;
            double[] nt = apply(p);
            return new Xf(nm, nt);
        }
    }

    public static List<Quad> build(MiModel m) {
        List<Quad> out = new ArrayList<>();
        Map<String, BufferedImage> tex = m.textures;
        for (MiPart p : m.parts) part(out, p, Xf.id(), tex.get(m.texture), m.textureSize, tex);
        return out;
    }

    private static double sq(double[] ts) { return Math.max(ts[0], ts[1]); }

    private static void part(List<Quad> out, MiPart p, Xf parent, BufferedImage tex, double[] ts, Map<String, BufferedImage> all) {
        Xf x = parent.then(p.position, p.rotation, p.scale);
        if (p.texture != null && all.containsKey(p.texture)) { tex = all.get(p.texture); ts = p.textureSize; }
        for (MiShape s : p.shapes) {
            BufferedImage t = tex;
            double[] tsz = ts;
            if (s.texture != null && all.containsKey(s.texture)) { t = all.get(s.texture); tsz = s.textureSize; }
            shape(out, s, x, t, tsz);
        }
        for (MiPart c : p.parts) part(out, c, x, tex, ts, all);
    }

    private static void shape(List<Quad> out, MiShape s, Xf px, BufferedImage tex, double[] ts) {
        double[] sc = s.scale == null ? new double[]{1, 1, 1} : s.scale;
        double inf = s.inflate;
        double[] a = new double[3], b = new double[3];
        for (int i = 0; i < 3; i++) {
            a[i] = (s.from[i] - inf) * sc[i];
            b[i] = (s.to[i] + inf) * sc[i];
        }
        boolean plane = s.type.equals("plane");
        if (plane) {
            if (s.is3d) { b[2] = (s.to[2] + 1 + inf) * sc[2]; }
            else { a[2] = s.from[2] * sc[2]; b[2] = a[2]; }
        }
        Xf x = px.then(s.position, s.rotation, null);
        double w = s.to[0] - s.from[0], h = s.to[1] - s.from[1], d = plane ? 0 : s.to[2] - s.from[2];
        double size = sq(ts);
        for (int f = 0; f < 6; f++) {
            if (plane && f != BoxUv.FRONT && f != BoxUv.BACK) continue;
            double[] r;
            if (plane) {
                r = new double[]{s.uv[0], s.uv[1], s.uv[0] + w, s.uv[1] + h};
                if (s.mirror) { double tt = r[0]; r[0] = r[2]; r[2] = tt; }
                if (f == BoxUv.BACK) { double tt = r[0]; r[0] = r[2]; r[2] = tt; }
            } else r = BoxUv.rect(f, s.uv[0], s.uv[1], w, h, d, s.mirror);
            double[] fs = faceSize(f, b[0] - a[0], b[1] - a[1], b[2] - a[2]);
            if (fs[0] <= 1e-9 || fs[1] <= 1e-9) {
                if (!(plane && (f == BoxUv.FRONT || f == BoxUv.BACK))) continue;
            }
            Quad q = new Quad();
            double[][] st = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int k = 0; k < 4; k++) {
                q.p[k] = x.apply(BoxUv.point(f, a, b, st[k][0], st[k][1]));
                q.uv[k] = new double[]{(r[0] + st[k][0] * (r[2] - r[0])) / size, (r[1] + st[k][1] * (r[3] - r[1])) / size};
            }
            q.tex = tex;
            q.n = normal(q.p);
            out.add(q);
        }
    }

    private static double[] faceSize(int f, double w, double h, double d) {
        switch (f) {
            case BoxUv.FRONT: case BoxUv.BACK: return new double[]{w, h};
            case BoxUv.LEFT: case BoxUv.RIGHT: return new double[]{d, h};
            default: return new double[]{w, d};
        }
    }

    static double[] normal(double[][] p) {
        double[] u = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
        double[] v = {p[3][0] - p[0][0], p[3][1] - p[0][1], p[3][2] - p[0][2]};
        double[] n = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        double l = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (l < 1e-12) return new double[]{0, 1, 0};
        return new double[]{n[0] / l, n[1] / l, n[2] / l};
    }
}
