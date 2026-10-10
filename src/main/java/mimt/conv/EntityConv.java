package mimt.conv;

import mimt.util.I18n;
import mimt.ir.EMesh;
import mimt.ir.FaceSrc;
import mimt.mi.*;
import mimt.util.Img;
import mimt.util.Mat3;
import java.awt.image.BufferedImage;
import java.util.Map;

public final class EntityConv {
    public static MiModel convert(String name, EMesh mesh, BufferedImage texture, String texName) {
        MiModel m = new MiModel();
        m.name = name;
        boolean atlas = needsAtlas(mesh.root);
        if (texture == null) {
            texture = Img.placeholder(mesh.texW, mesh.texH);
            m.notes.add(I18n.t("Texture not found — placeholder used"));
        }
        Atlas at = atlas ? new Atlas() : null;
        Ctx ctx = new Ctx(mesh, texture, at);
        EMesh.EPart root = mesh.root;
        boolean rootTrivial = root.cubes.isEmpty() && isZeroPose(root);
        if (rootTrivial) {
            for (EMesh.EPart c : root.children.values()) m.parts.add(part(ctx, c, true));
        } else {
            m.parts.add(part(ctx, root, true));
        }
        if (atlas) {
            at.build(m, texName);
            m.notes.add(I18n.t("UV rebuilt into an atlas (hidden faces / UV scale)"));
        } else {
            BufferedImage sq = Img.square(texture);
            double s = Math.max(mesh.texW, mesh.texH);
            m.textures.put(texName, sq);
            m.texture = texName;
            m.textureSize = new double[]{s, s};
        }
        m.uniquifyNames();
        return m;
    }

    private static boolean isZeroPose(EMesh.EPart p) {
        return p.x == 0 && p.y == 0 && p.z == 0 && p.xRot == 0 && p.yRot == 0 && p.zRot == 0
                && p.xScale == 1 && p.yScale == 1 && p.zScale == 1;
    }

    private static boolean needsAtlas(EMesh.EPart p) {
        for (EMesh.ECube c : p.cubes) {
            if (c.su != 1 || c.sv != 1) return true;
            if (c.faces != null && c.faces.size() < 6) return true;
        }
        for (EMesh.EPart ch : p.children.values()) if (needsAtlas(ch)) return true;
        return false;
    }

    private record Ctx(EMesh mesh, BufferedImage tex, Atlas atlas) { }

    public static double[] rotation(float xr, float yr, float zr) {
        double a = Math.toDegrees(xr), b = Math.toDegrees(yr), c = Math.toDegrees(zr);
        if (a == 0 && b == 0 && c == 0) return null;
        Mat3 r = Mat3.rz(-c).mul(Mat3.ry(-b)).mul(Mat3.rx(a));
        return r.toMiEuler();
    }

    private static MiPart part(Ctx ctx, EMesh.EPart p, boolean top) {
        MiPart mp = new MiPart(p.name);
        mp.position = new double[]{p.x, top ? 24 - p.y : -p.y, -p.z};
        mp.rotation = rotation(p.xRot, p.yRot, p.zRot);
        if (p.xScale != 1 || p.yScale != 1 || p.zScale != 1) mp.scale = new double[]{p.xScale, p.yScale, p.zScale};
        for (EMesh.ECube c : p.cubes) mp.shapes.add(shape(ctx, c));
        for (EMesh.EPart ch : p.children.values()) mp.parts.add(part(ctx, ch, false));
        return mp;
    }

    private static MiShape shape(Ctx ctx, EMesh.ECube c) {
        MiShape s = new MiShape();
        double x1 = c.ox, x2 = c.ox + c.w;
        double y1 = -(c.oy + c.h), y2 = -c.oy;
        double z1 = -(c.oz + c.d), z2 = -c.oz;
        boolean uniform = c.gx == c.gy && c.gy == c.gz;
        if (uniform) {
            s.from = new double[]{x1, y1, z1};
            s.to = new double[]{x2, y2, z2};
            s.inflate = c.gx;
        } else {
            double cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, cz = (z1 + z2) / 2;
            s.position = new double[]{cx, cy, cz};
            s.from = new double[]{x1 - cx, y1 - cy, z1 - cz};
            s.to = new double[]{x2 - cx, y2 - cy, z2 - cz};
            s.scale = new double[]{sc(c.w, c.gx), sc(c.h, c.gy), sc(c.d, c.gz)};
        }
        s.uv = new double[]{c.u + c.d, c.v + c.d};
        s.mirror = c.mirror;
        if (ctx.atlas != null) {
            FaceSrc[] faces = new FaceSrc[6];
            double tw = ctx.mesh.texW * c.su, th = ctx.mesh.texH * c.sv;
            for (int f = 0; f < 6; f++) {
                if (c.faces != null && !c.faces.contains(mcDir(f))) continue;
                double[] r = BoxUv.rect(f, c.u + c.d, c.v + c.d, c.w, c.h, c.d, c.mirror);
                faces[f] = new FaceSrc(ctx.tex, r[0] / tw, r[1] / th, r[2] / tw, r[3] / th, 0);
            }
            ctx.atlas.add(s, faces);
        }
        return s;
    }

    private static double sc(double size, double grow) {
        return size <= 0 ? 1 : (size + 2 * grow) / size;
    }

    private static String mcDir(int f) {
        switch (f) {
            case BoxUv.FRONT: return "north";
            case BoxUv.BACK: return "south";
            case BoxUv.LEFT: return "west";
            case BoxUv.RIGHT: return "east";
            case BoxUv.TOP: return "down";
            default: return "up";
        }
    }

}
