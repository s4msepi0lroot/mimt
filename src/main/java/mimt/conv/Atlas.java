package mimt.conv;

import mimt.ir.FaceSrc;
import mimt.mi.BoxUv;
import mimt.mi.MiModel;
import mimt.mi.MiShape;
import java.awt.image.BufferedImage;
import java.util.*;

public class Atlas {
    public static class Item {
        final MiShape shape;
        final double w, h, d;
        final FaceSrc[] faces;
        int px, py, pw, ph;

        Item(MiShape s, double w, double h, double d, FaceSrc[] faces) {
            this.shape = s; this.w = w; this.h = h; this.d = d; this.faces = faces;
        }
    }

    private final List<Item> items = new ArrayList<>();

    public void add(MiShape shape, FaceSrc[] faces) {
        double w = shape.to[0] - shape.from[0], h = shape.to[1] - shape.from[1], d = shape.to[2] - shape.from[2];
        if (shape.type.equals("plane")) d = 0;
        items.add(new Item(shape, w, h, d, faces));
    }

    public boolean isEmpty() { return items.isEmpty(); }

    private static boolean nearInt(double v) { return Math.abs(v - Math.rint(v)) < 0.02; }

    public void build(MiModel model, String fileName) {
        if (items.isEmpty()) return;
        double dens = 1;
        for (Item it : items) {
            for (int f = 0; f < 6; f++) {
                FaceSrc s = it.faces[f];
                if (s == null || s.img == null) continue;
                double[] fs = faceSize(it, f);
                if (fs[0] > 1e-6) dens = Math.max(dens, Math.abs(s.u2 - s.u1) * s.img.getWidth() / fs[0]);
                if (fs[1] > 1e-6) dens = Math.max(dens, Math.abs(s.v2 - s.v1) * s.img.getHeight() / fs[1]);
            }
        }
        int k = 1;
        while (k < dens - 0.01 && k < 16) k *= 2;
        while (k < 16) {
            boolean ok = true;
            for (Item it : items) if (!nearInt(it.w * k) || !nearInt(it.h * k) || !nearInt(it.d * k)) { ok = false; break; }
            if (ok) break;
            k *= 2;
        }
        int size;
        while (true) {
            size = pack(k);
            if (size <= 4096 || k == 1) break;
            k /= 2;
        }
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (Item it : items) {
            double u0 = (double) it.px / k, v0 = (double) it.py / k;
            double U = u0 + it.d, V = v0 + it.d;
            it.shape.uv = new double[]{U, V};
            it.shape.mirror = false;
            for (int f = 0; f < 6; f++) {
                FaceSrc src = it.faces[f];
                if (src == null || src.img == null) continue;
                if (it.shape.type.equals("plane") && f != BoxUv.FRONT) continue;
                double[] r = BoxUv.rect(f, U, V, it.w, it.h, it.d, false);
                double ru1 = Math.min(r[0], r[2]), ru2 = Math.max(r[0], r[2]);
                double rv1 = Math.min(r[1], r[3]), rv2 = Math.max(r[1], r[3]);
                if (ru2 - ru1 < 1e-9 || rv2 - rv1 < 1e-9) continue;
                int x1 = (int) Math.round(ru1 * k), x2 = (int) Math.round(ru2 * k);
                int y1 = (int) Math.round(rv1 * k), y2 = (int) Math.round(rv2 * k);
                if (x2 <= x1) x2 = x1 + 1;
                if (y2 <= y1) y2 = y1 + 1;
                for (int y = y1; y < y2; y++) {
                    for (int x = x1; x < x2; x++) {
                        if (x < 0 || y < 0 || x >= size || y >= size) continue;
                        double cu = (x + 0.5) / k, cv = (y + 0.5) / k;
                        double s = (cu - r[0]) / (r[2] - r[0]), t = (cv - r[1]) / (r[3] - r[1]);
                        s = Math.max(0, Math.min(1, s)); t = Math.max(0, Math.min(1, t));
                        img.setRGB(x, y, src.sample(s, t));
                    }
                }
            }
        }
        model.textures.put(fileName, img);
        model.texture = fileName;
        model.textureSize = new double[]{(double) size / k, (double) size / k};
    }

    private static double[] faceSize(Item it, int f) {
        switch (f) {
            case BoxUv.FRONT: case BoxUv.BACK: return new double[]{it.w, it.h};
            case BoxUv.LEFT: case BoxUv.RIGHT: return new double[]{it.d, it.h};
            default: return new double[]{it.w, it.d};
        }
    }

    private int pack(int k) {
        long area = 0;
        for (Item it : items) {
            it.pw = Math.max(1, (int) Math.ceil((2 * it.d + 2 * it.w) * k - 0.02));
            it.ph = Math.max(1, (int) Math.ceil((it.d + it.h) * k - 0.02));
            area += (long) it.pw * it.ph;
        }
        int maxW = 0;
        for (Item it : items) maxW = Math.max(maxW, it.pw);
        int width = Math.max(maxW, (int) Math.ceil(Math.sqrt(area * 1.15)));
        width = Math.max(16, ((width + k - 1) / k) * k);
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> b.ph != a.ph ? b.ph - a.ph : b.pw - a.pw);
        int x = 0, y = 0, rowH = 0;
        for (Item it : sorted) {
            if (x + it.pw > width) { x = 0; y += rowH; rowH = 0; }
            it.px = x; it.py = y;
            x += it.pw;
            x = ((x + k - 1) / k) * k;
            rowH = Math.max(rowH, ((it.ph + k - 1) / k) * k);
        }
        int height = y + rowH;
        int s = Math.max(width, height);
        int p = 16;
        while (p < s) p *= 2;
        return p;
    }
}
