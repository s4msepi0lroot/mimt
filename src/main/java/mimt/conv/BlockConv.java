package mimt.conv;

import com.google.gson.*;
import mimt.ir.FaceSrc;
import mimt.mi.*;
import mimt.src.Source;
import mimt.src.Sources;
import mimt.util.*;
import java.awt.image.BufferedImage;
import java.util.*;

public final class BlockConv {
    public static class Options {
        public boolean tint = true;
        public int[] tints;
    }

    public static class Resolved {
        public String id;
        public JsonArray elements;
        public final Map<String, String> textures = new LinkedHashMap<>();
        public String builtin;
        public List<String> chain = new ArrayList<>();
    }

    public static String norm(String id) {
        if (id == null) return null;
        id = id.trim();
        if (!id.contains(":")) id = "minecraft:" + id;
        return id;
    }

    public static String modelPath(String id) {
        String n = norm(id);
        int c = n.indexOf(':');
        return "assets/" + n.substring(0, c) + "/models/" + n.substring(c + 1) + ".json";
    }

    public static String texturePath(String id) {
        String n = norm(id);
        int c = n.indexOf(':');
        String p = n.substring(c + 1);
        if (p.endsWith(".png")) p = p.substring(0, p.length() - 4);
        return "assets/" + n.substring(0, c) + "/textures/" + p + ".png";
    }

    public static Resolved resolve(Sources src, String id, Source preferred) {
        Resolved r = new Resolved();
        r.id = norm(id);
        String cur = r.id;
        List<Map<String, JsonElement>> texLayers = new ArrayList<>();
        for (int depth = 0; depth < 32 && cur != null; depth++) {
            String n = norm(cur);
            if (n.equals("minecraft:builtin/generated")) { r.builtin = "generated"; break; }
            if (n.equals("minecraft:builtin/entity")) { r.builtin = "entity"; break; }
            r.chain.add(n);
            byte[] b = src.read(modelPath(n), preferred);
            if (b == null) {
                r.chain.add("?" + n);
                break;
            }
            JsonElement j;
            try { j = Json.parse(b); } catch (Exception e) { break; }
            JsonObject tex = Json.obj(j, "textures");
            if (tex != null) {
                Map<String, JsonElement> m = new LinkedHashMap<>();
                for (var e : tex.entrySet()) m.put(e.getKey(), e.getValue());
                texLayers.add(m);
            }
            if (r.elements == null) r.elements = Json.arr(j, "elements");
            cur = Json.str(j, "parent", null);
        }
        for (int i = texLayers.size() - 1; i >= 0; i--)
            for (var e : texLayers.get(i).entrySet()) {
                JsonElement v = e.getValue();
                String s = null;
                if (v.isJsonPrimitive()) s = v.getAsString();
                else if (v.isJsonObject()) s = Json.str(v, "sprite", null);
                if (s != null) r.textures.put(e.getKey(), s);
            }
        return r;
    }

    public static String resolveTex(Resolved r, String ref) {
        String v = ref;
        for (int i = 0; i < 16 && v != null && v.startsWith("#"); i++) v = r.textures.get(v.substring(1));
        if (v != null && v.startsWith("#")) return null;
        return v;
    }

    public static BufferedImage loadTexture(Sources src, String texId, Source preferred) {
        if (texId == null) return null;
        String p = texturePath(texId);
        BufferedImage img = Img.read(src.read(p, preferred));
        if (img == null) return null;
        return Img.firstFrame(img, src.read(p + ".mcmeta", preferred));
    }

    public static MiModel convert(Sources src, String id, Source preferred, Options opt) {
        Resolved r = resolve(src, id, preferred);
        String name = r.id.substring(r.id.lastIndexOf('/') + 1);
        if ("entity".equals(r.builtin) && r.elements == null)
            throw new IllegalStateException(I18n.t("Model is rendered by code (builtin/entity) — cannot be exported"));
        if (r.elements == null && "generated".equals(r.builtin)) return item(src, r, name, preferred, opt);
        if (r.elements == null || r.elements.isEmpty()) {
            for (String c : r.chain) if (c.startsWith("?")) throw new IllegalStateException(I18n.t("Parent model not found: ") + c.substring(1) + I18n.t(" (add the Minecraft jar or enable downloading)"));
            throw new IllegalStateException(I18n.t("Model has no elements"));
        }
        MiModel m = new MiModel();
        m.name = name;
        MiPart part = new MiPart(name);
        part.position = new double[]{0, 0, 0};
        m.parts.add(part);
        Atlas atlas = new Atlas();
        Map<String, BufferedImage> cache = new HashMap<>();
        BufferedImage missing = Img.placeholder(16, 16);
        int idx = 0;
        boolean missingTex = false;
        for (JsonElement el : r.elements) {
            idx++;
            double[] from = Json.vec(el, "from", 3, null), to = Json.vec(el, "to", 3, null);
            if (from == null || to == null) continue;
            double[] a = {Math.min(from[0], to[0]), Math.min(from[1], to[1]), Math.min(from[2], to[2])};
            double[] b = {Math.max(from[0], to[0]), Math.max(from[1], to[1]), Math.max(from[2], to[2])};
            MiShape s = new MiShape();
            s.description = Json.str(el, "name", null);
            JsonObject rot = Json.obj(el, "rotation");
            double[] base = {8, 0, 8};
            if (rot != null) {
                double[] origin = Json.vec(rot, "origin", 3, new double[]{8, 8, 8});
                Mat3 R;
                double[] scale = null;
                boolean rescale = Json.bool(rot, "rescale", false);
                if (rot.has("axis")) {
                    String axis = Json.str(rot, "axis", "y");
                    double ang = Json.num(rot, "angle", 0);
                    R = axis.equals("x") ? Mat3.rx(ang) : axis.equals("z") ? Mat3.rz(ang) : Mat3.ry(ang);
                    if (rescale && ang != 0) {
                        double f = 1 / Math.cos(Math.toRadians(ang));
                        scale = axis.equals("x") ? new double[]{1, f, f} : axis.equals("z") ? new double[]{f, f, 1} : new double[]{f, 1, f};
                    }
                } else {
                    double x = Json.num(rot, "x", 0), y = Json.num(rot, "y", 0), z = Json.num(rot, "z", 0);
                    R = Mat3.rz(z).mul(Mat3.ry(y)).mul(Mat3.rx(x));
                }
                if (!R.isIdentity() || scale != null) {
                    s.position = new double[]{origin[0] - 8, origin[1], origin[2] - 8};
                    s.rotation = R.isIdentity() ? null : R.toMiEuler();
                    s.scale = scale;
                    base = origin;
                }
            }
            s.from = new double[]{a[0] - base[0], a[1] - base[1], a[2] - base[2]};
            s.to = new double[]{b[0] - base[0], b[1] - base[1], b[2] - base[2]};
            FaceSrc[] faces = new FaceSrc[6];
            JsonObject fo = Json.obj(el, "faces");
            if (fo != null) {
                for (var fe : fo.entrySet()) {
                    int f = BoxUv.fromBlockName(fe.getKey());
                    if (f < 0 || !fe.getValue().isJsonObject()) continue;
                    JsonObject face = fe.getValue().getAsJsonObject();
                    String texId = resolveTex(r, Json.str(face, "texture", null));
                    BufferedImage img = null;
                    if (texId != null) img = cache.computeIfAbsent(texId, t -> loadTexture(src, t, preferred));
                    if (img == null) { img = missing; missingTex = true; }
                    double[] uv = Json.vec(face, "uv", 4, null);
                    if (uv == null) uv = defaultUv(fe.getKey(), a, b);
                    FaceSrc fs = new FaceSrc(img, uv[0] / 16, uv[1] / 16, uv[2] / 16, uv[3] / 16, (int) Json.num(face, "rotation", 0));
                    int ti = (int) Json.num(face, "tintindex", -1);
                    if (ti >= 0 && opt.tint) fs.tint = tintColor(opt, ti, r.id);
                    faces[f] = fs;
                }
            }
            part.shapes.add(s);
            atlas.add(s, faces);
        }
        atlas.build(m, safe(name) + ".png");
        if (missingTex) m.notes.add(I18n.t("Some textures not found — replaced with a placeholder"));
        m.uniquifyNames();
        return m;
    }

    static String safe(String n) { return n.replaceAll("[^A-Za-z0-9_.-]", "_"); }

    static double[] defaultUv(String face, double[] a, double[] b) {
        switch (face) {
            case "down": return new double[]{a[0], 16 - b[2], b[0], 16 - a[2]};
            case "up": return new double[]{a[0], a[2], b[0], b[2]};
            case "north": return new double[]{16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]};
            case "south": return new double[]{a[0], 16 - b[1], b[0], 16 - a[1]};
            case "west": return new double[]{a[2], 16 - b[1], b[2], 16 - a[1]};
            default: return new double[]{16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]};
        }
    }

    static int tintColor(Options opt, int index, String id) {
        if (opt.tints != null && index < opt.tints.length && opt.tints[index] != -1) return opt.tints[index] & 0xFFFFFF;
        String n = id.toLowerCase(Locale.ROOT);
        if (n.contains("birch_leaves")) return 0x80A755;
        if (n.contains("spruce_leaves")) return 0x619961;
        if (n.contains("mangrove_leaves")) return 0x92C648;
        if (n.contains("water")) return 0x3F76E4;
        if (n.contains("leaves") || n.contains("vine")) return 0x48B518;
        if (n.contains("stem")) return 0xE0C71C;
        if (n.contains("redstone")) return 0xFF0000;
        return 0x91BD59;
    }

    private static MiModel item(Sources src, Resolved r, String name, Source preferred, Options opt) {
        MiModel m = new MiModel();
        m.name = name;
        MiPart part = new MiPart(name);
        part.position = new double[]{0, 0, 0};
        m.parts.add(part);
        int layers = 0;
        for (int i = 0; i < 16; i++) {
            String t = resolveTex(r, "#layer" + i);
            if (t == null) continue;
            BufferedImage img = loadTexture(src, t, preferred);
            if (img == null) { m.notes.add(I18n.t("Texture not found: ") + t); continue; }
            if (opt.tint && opt.tints != null && i < opt.tints.length && opt.tints[i] != -1) {
                img = Img.copy(img);
                int c = opt.tints[i] & 0xFFFFFF;
                for (int y = 0; y < img.getHeight(); y++)
                    for (int x = 0; x < img.getWidth(); x++) img.setRGB(x, y, Img.multiply(img.getRGB(x, y), c));
            }
            String file = safe(name) + (i == 0 ? "" : "_layer" + i) + ".png";
            m.textures.put(file, Img.square(img));
            double sz = 16.0 * Math.max(img.getWidth(), img.getHeight()) / img.getWidth();
            MiShape s = new MiShape();
            s.type = "plane";
            s.is3d = true;
            double off = layers * 0.01;
            s.from = new double[]{-8, 0, -0.5 - off};
            s.to = new double[]{8, 16, -0.5 - off};
            s.uv = new double[]{0, 0};
            s.texture = file;
            s.textureSize = new double[]{sz, sz};
            part.shapes.add(s);
            if (layers == 0) { m.texture = file; m.textureSize = new double[]{sz, sz}; }
            layers++;
        }
        if (layers == 0) throw new IllegalStateException(I18n.t("Item has no layer textures (layer0)"));
        return m;
    }
}
