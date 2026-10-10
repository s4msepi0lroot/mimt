package mimt.gui;

import mimt.mi.MiMesher;
import java.awt.image.BufferedImage;
import java.util.List;

public final class Renderer {
    public double yaw = -35, pitch = 25, zoom = 1, panX, panY;

    public BufferedImage render(List<MiMesher.Quad> quads, int w, int h, double[] center, double radius) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        float[] zb = new float[w * h];
        for (int y = 0; y < h; y++) {
            int c1 = 0x3a3f4b, c2 = 0x23262e;
            double t = (double) y / Math.max(1, h - 1);
            int r = (int) (((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
            int g = (int) (((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
            int b = (int) ((c1 & 255) * (1 - t) + (c2 & 255) * t);
            int col = (r << 16) | (g << 8) | b;
            for (int x = 0; x < w; x++) { px[y * w + x] = col; zb[y * w + x] = Float.NEGATIVE_INFINITY; }
        }
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double scale = Math.min(w, h) * 0.45 / Math.max(1e-3, radius) * zoom;
        double ox = w / 2.0 + panX, oy = h / 2.0 + panY;
        double[] light = norm(new double[]{0.35, 0.85, 0.55});
        for (int i = -24; i <= 24; i += 8) {
            line(px, zb, w, h, proj(new double[]{i, 0, -24}, center, cy, sy, cp, sp, scale, ox, oy), proj(new double[]{i, 0, 24}, center, cy, sy, cp, sp, scale, ox, oy), i == 0 ? 0x7a5050 : 0x4a505c);
            line(px, zb, w, h, proj(new double[]{-24, 0, i}, center, cy, sy, cp, sp, scale, ox, oy), proj(new double[]{24, 0, i}, center, cy, sy, cp, sp, scale, ox, oy), i == 0 ? 0x50507a : 0x4a505c);
        }
        for (MiMesher.Quad q : quads) {
            double[][] s = new double[4][];
            for (int k = 0; k < 4; k++) s[k] = proj(q.p[k], center, cy, sy, cp, sp, scale, ox, oy);
            double shade = 0.55 + 0.45 * Math.abs(q.n[0] * light[0] + q.n[1] * light[1] + q.n[2] * light[2]);
            tri(px, zb, w, h, s[0], s[1], s[2], q.uv[0], q.uv[1], q.uv[2], q.tex, shade);
            tri(px, zb, w, h, s[0], s[2], s[3], q.uv[0], q.uv[2], q.uv[3], q.tex, shade);
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static double[] norm(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    private static double[] proj(double[] p, double[] c, double cy, double sy, double cp, double sp, double scale, double ox, double oy) {
        double x = p[0] - c[0], y = p[1] - c[1], z = p[2] - c[2];
        double x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
        double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;
        return new double[]{ox + x1 * scale, oy - y2 * scale, z2};
    }

    private static void line(int[] px, float[] zb, int w, int h, double[] a, double[] b, int col) {
        int n = (int) Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) + 1;
        if (n > 20000) return;
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            int x = (int) (a[0] + (b[0] - a[0]) * t), y = (int) (a[1] + (b[1] - a[1]) * t);
            if (x >= 0 && y >= 0 && x < w && y < h) px[y * w + x] = col;
        }
    }

    private static void tri(int[] px, float[] zb, int w, int h, double[] a, double[] b, double[] c,
                            double[] ta, double[] tb, double[] tc, BufferedImage tex, double shade) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        int minX = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int maxX = (int) Math.min(w - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int maxY = (int) Math.min(h - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        if (minX > maxX || minY > maxY) return;
        int tw = tex == null ? 0 : tex.getWidth(), th = tex == null ? 0 : tex.getHeight();
        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double pxx = x + 0.5;
                double w0 = ((b[0] - pxx) * (c[1] - py) - (b[1] - py) * (c[0] - pxx)) / area;
                double w1 = ((c[0] - pxx) * (a[1] - py) - (c[1] - py) * (a[0] - pxx)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int idx = y * w + x;
                if (z <= zb[idx]) continue;
                int col;
                if (tex != null) {
                    double u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0], v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
                    int tx = (int) Math.floor(u * tw), ty = (int) Math.floor(v * th);
                    if (tx < 0 || ty < 0 || tx >= tw || ty >= th) continue;
                    col = tex.getRGB(tx, ty);
                    if ((col >>> 24) < 128) continue;
                } else col = 0xFFB0B0B0;
                int r = (int) (((col >> 16) & 255) * shade), g = (int) (((col >> 8) & 255) * shade), bl = (int) ((col & 255) * shade);
                px[idx] = (r << 16) | (g << 8) | bl;
                zb[idx] = (float) z;
            }
        }
    }
}
