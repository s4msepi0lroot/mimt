package mimt.conv;

import mimt.util.I18n;
import mimt.ir.BModel;
import mimt.ir.FaceSrc;
import mimt.mi.*;
import mimt.util.Img;
import mimt.util.Mat3;
import java.awt.image.BufferedImage;

public final class BbConv {
    static double[] w(double[] p) { return new double[]{-p[0], p[1], -p[2]}; }

    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }

    public static double[] rotation(double[] r) {
        if (r == null || (r[0] == 0 && r[1] == 0 && r[2] == 0)) return null;
        return Mat3.rz(-r[2]).mul(Mat3.ry(r[1])).mul(Mat3.rx(-r[0])).toMiEuler();
    }

    public static MiModel convert(BModel bm, String texName, BufferedImage overrideTex) {
        MiModel m = new MiModel();
        m.name = bm.name;
        if (overrideTex != null) {
            if (bm.textures.isEmpty()) bm.textures.add(new BModel.BTex("texture", overrideTex));
            else bm.textures.get(0).img = overrideTex;
        }
        if (bm.textures.isEmpty() || bm.textures.get(0).img == null) {
            BufferedImage ph = Img.placeholder((int) bm.texW, (int) bm.texH);
            if (bm.textures.isEmpty()) bm.textures.add(new BModel.BTex("texture", ph));
            else bm.textures.get(0).img = ph;
            m.notes.add(I18n.t("Texture not found — placeholder used"));
        }
        boolean atlas = needsAtlas(bm, bm.root);
        Atlas at = atlas ? new Atlas() : null;
        double[] zero = {0, 0, 0};
        for (BModel.BCube c : bm.root.cubes) {
            MiPart p = new MiPart(c.name);
            p.position = new double[]{0, 0, 0};
            MiShape s = shape(bm, c, zero, at);
            if (s != null) { p.shapes.add(s); m.parts.add(p); }
        }
        for (BModel.BGroup g : bm.root.children) {
            MiPart p = part(bm, g, zero, at);
            if (p != null) m.parts.add(p);
        }
        if (atlas) {
            at.build(m, texName);
            m.notes.add(I18n.t("UV rebuilt into an atlas (per-face UV)"));
        } else {
            BModel.BTex t = bm.textures.get(0);
            double tw = t.uvW > 0 ? t.uvW : bm.texW, th = t.uvH > 0 ? t.uvH : bm.texH;
            double s = Math.max(tw, th);
            BufferedImage img = t.img;
            m.textures.put(texName, Img.square(img));
            m.texture = texName;
            m.textureSize = new double[]{s, s};
        }
        m.uniquifyNames();
        return m;
    }

    private static boolean needsAtlas(BModel bm, BModel.BGroup g) {
        for (BModel.BCube c : g.cubes) {
            if (!c.boxUv || c.boxTexture != 0) return true;
        }
        for (BModel.BGroup ch : g.children) if (needsAtlas(bm, ch)) return true;
        if (g == bm.root && bm.textures.size() > 0) {
            BModel.BTex t = bm.textures.get(0);
            if (t.img != null) {
                double tw = t.uvW > 0 ? t.uvW : bm.texW, th = t.uvH > 0 ? t.uvH : bm.texH;
                double a1 = (double) t.img.getWidth() / t.img.getHeight(), a2 = tw / th;
                if (Math.abs(a1 - a2) > 1e-3) return true;
            }
        }
        return false;
    }

    private static MiPart part(BModel bm, BModel.BGroup g, double[] parentOrigin, Atlas at) {
        if (!g.visible) return null;
        MiPart p = new MiPart(g.name);
        p.position = sub(w(g.origin), w(parentOrigin));
        p.rotation = rotation(g.rotation);
        for (BModel.BCube c : g.cubes) {
            MiShape s = shape(bm, c, g.origin, at);
            if (s != null) p.shapes.add(s);
        }
        for (BModel.BGroup ch : g.children) {
            MiPart cp = part(bm, ch, g.origin, at);
            if (cp != null) p.parts.add(cp);
        }
        return p;
    }

    private static MiShape shape(BModel bm, BModel.BCube c, double[] groupOrigin, Atlas at) {
        if (!c.visible || c.from == null || c.to == null) return null;
        MiShape s = new MiShape();
        s.description = c.name;
        double[] a = w(c.from), b = w(c.to);
        double[] lo = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
        double[] hi = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        double[] rot = rotation(c.rotation);
        double[] base;
        if (rot != null) {
            base = w(c.origin);
            s.position = sub(base, w(groupOrigin));
            s.rotation = rot;
        } else base = w(groupOrigin);
        s.from = sub(lo, base);
        s.to = sub(hi, base);
        s.inflate = c.inflate;
        double sw = hi[0] - lo[0], sh = hi[1] - lo[1], sd = hi[2] - lo[2];
        if (c.boxUv) {
            s.uv = new double[]{c.uvOffset[0] + sd, c.uvOffset[1] + sd};
            s.mirror = c.mirror;
        }
        if (at != null) {
            FaceSrc[] faces = new FaceSrc[6];
            if (c.boxUv) {
                BModel.BTex t = tex(bm, c.boxTexture);
                if (t != null) {
                    double tw = t.uvW > 0 ? t.uvW : bm.texW, th = t.uvH > 0 ? t.uvH : bm.texH;
                    for (int f = 0; f < 6; f++) {
                        double[] r = BoxUv.rect(f, s.uv[0], s.uv[1], sw, sh, sd, c.mirror);
                        faces[f] = new FaceSrc(t.img, r[0] / tw, r[1] / th, r[2] / tw, r[3] / th, 0);
                    }
                }
            } else {
                int[] map = {BModel.NORTH, BModel.SOUTH, BModel.EAST, BModel.WEST, BModel.UP, BModel.DOWN};
                for (int f = 0; f < 6; f++) {
                    BModel.BFace bf = c.faces[map[f]];
                    if (bf == null || bf.uv == null || bf.texture < 0) continue;
                    BModel.BTex t = tex(bm, bf.texture);
                    if (t == null) continue;
                    double tw = t.uvW > 0 ? t.uvW : bm.texW, th = t.uvH > 0 ? t.uvH : bm.texH;
                    int extra = (f == BoxUv.TOP || f == BoxUv.BOTTOM) ? 180 : 0;
                    faces[f] = new FaceSrc(t.img, bf.uv[0] / tw, bf.uv[1] / th, bf.uv[2] / tw, bf.uv[3] / th, bf.rotation + extra);
                }
            }
            at.add(s, faces);
        }
        return s;
    }

    private static BModel.BTex tex(BModel bm, int i) {
        if (i < 0) return null;
        if (i >= bm.textures.size()) i = 0;
        if (bm.textures.isEmpty()) return null;
        BModel.BTex t = bm.textures.get(i);
        return t.img == null ? null : t;
    }
}
