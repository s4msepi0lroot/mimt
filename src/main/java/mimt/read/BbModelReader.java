package mimt.read;

import com.google.gson.*;
import mimt.ir.BModel;
import mimt.util.Img;
import mimt.util.Json;
import java.util.*;
import java.util.function.Function;

public final class BbModelReader {
    public static BModel read(byte[] data, String fallbackName, Function<String, byte[]> fileLoader) {
        JsonObject root = Json.parse(data).getAsJsonObject();
        BModel bm = new BModel();
        bm.name = Json.str(root, "name", fallbackName);
        if (bm.name == null || bm.name.isBlank()) bm.name = fallbackName;
        JsonObject res = Json.obj(root, "resolution");
        bm.texW = Json.num(res, "width", 16);
        bm.texH = Json.num(res, "height", 16);
        String format = Json.str(Json.obj(root, "meta"), "model_format", "");
        boolean blockSpace = format.equals("java_block");
        double[] off = blockSpace ? new double[]{-8, 0, -8} : new double[]{0, 0, 0};
        JsonArray texs = Json.arr(root, "textures");
        Map<String, Integer> texByUuid = new HashMap<>();
        if (texs != null) {
            int i = 0;
            for (JsonElement t : texs) {
                String src = Json.str(t, "source", null);
                byte[] bytes = null;
                if (src != null && src.startsWith("data:")) {
                    int comma = src.indexOf(',');
                    try { bytes = Base64.getDecoder().decode(src.substring(comma + 1).trim()); } catch (Exception ignored) { }
                }
                if (bytes == null && fileLoader != null) {
                    String rel = Json.str(t, "relative_path", Json.str(t, "name", null));
                    if (rel != null) bytes = fileLoader.apply(rel);
                }
                BModel.BTex bt = new BModel.BTex(Json.str(t, "name", "texture" + i), Img.read(bytes));
                bt.uvW = Json.num(t, "uv_width", 0);
                bt.uvH = Json.num(t, "uv_height", 0);
                bm.textures.add(bt);
                String uuid = Json.str(t, "uuid", null);
                if (uuid != null) texByUuid.put(uuid, i);
                i++;
            }
        }
        Map<String, BModel.BCube> cubes = new LinkedHashMap<>();
        JsonArray els = Json.arr(root, "elements");
        boolean projectBox = Json.bool(Json.obj(root, "meta"), "box_uv", false);
        if (els != null) {
            for (JsonElement e : els) {
                String type = Json.str(e, "type", "cube");
                if (!type.equals("cube")) continue;
                BModel.BCube c = new BModel.BCube();
                c.name = Json.str(e, "name", "cube");
                c.from = add(Json.vec(e, "from", 3, new double[]{0, 0, 0}), off);
                c.to = add(Json.vec(e, "to", 3, new double[]{0, 0, 0}), off);
                c.origin = add(Json.vec(e, "origin", 3, new double[]{0, 0, 0}), off);
                c.rotation = Json.vec(e, "rotation", 3, new double[]{0, 0, 0});
                c.inflate = Json.num(e, "inflate", 0);
                c.boxUv = Json.bool(e, "box_uv", projectBox);
                c.uvOffset = Json.vec(e, "uv_offset", 2, new double[]{0, 0});
                c.mirror = Json.bool(e, "mirror_uv", false);
                c.visible = Json.bool(e, "visibility", true) && Json.bool(e, "export", true);
                JsonObject faces = Json.obj(e, "faces");
                int boxTex = -1;
                for (int f = 0; f < 6; f++) {
                    JsonObject fo = Json.obj(faces, BModel.FACE_NAMES[f]);
                    if (fo == null) continue;
                    double[] uv = Json.vec(fo, "uv", 4, null);
                    int tex = texIndex(fo.get("texture"), texByUuid);
                    if (uv == null) continue;
                    c.faces[f] = new BModel.BFace(uv, (int) Json.num(fo, "rotation", 0), tex);
                    if (tex >= 0 && boxTex < 0) boxTex = tex;
                }
                c.boxTexture = Math.max(0, boxTex);
                String uuid = Json.str(e, "uuid", UUID.randomUUID().toString());
                cubes.put(uuid, c);
            }
        }
        Map<String, JsonObject> groupDefs = new HashMap<>();
        JsonArray groups = Json.arr(root, "groups");
        if (groups != null) for (JsonElement g : groups) if (g.isJsonObject()) groupDefs.put(Json.str(g, "uuid", ""), g.getAsJsonObject());
        JsonArray outliner = Json.arr(root, "outliner");
        Set<String> used = new HashSet<>();
        if (outliner != null) {
            for (JsonElement n : outliner) node(n, bm.root, cubes, groupDefs, used, off);
        }
        for (Map.Entry<String, BModel.BCube> en : cubes.entrySet())
            if (!used.contains(en.getKey())) bm.root.cubes.add(en.getValue());
        if (bm.textures.isEmpty()) bm.textures.add(new BModel.BTex("texture", null));
        return bm;
    }

    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }

    private static int texIndex(JsonElement t, Map<String, Integer> byUuid) {
        if (t == null || t.isJsonNull()) return -1;
        if (t.isJsonPrimitive()) {
            JsonPrimitive p = t.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsInt();
            if (p.isBoolean()) return p.getAsBoolean() ? 0 : -1;
            Integer i = byUuid.get(p.getAsString());
            return i == null ? 0 : i;
        }
        return 0;
    }

    private static void node(JsonElement n, BModel.BGroup parent, Map<String, BModel.BCube> cubes,
                             Map<String, JsonObject> groupDefs, Set<String> used, double[] off) {
        if (n.isJsonPrimitive()) {
            String uuid = n.getAsString();
            BModel.BCube c = cubes.get(uuid);
            if (c != null && used.add(uuid)) parent.cubes.add(c);
            return;
        }
        if (!n.isJsonObject()) return;
        JsonObject o = n.getAsJsonObject();
        JsonObject def = groupDefs.getOrDefault(Json.str(o, "uuid", ""), o);
        BModel.BGroup g = new BModel.BGroup(Json.str(def, "name", Json.str(o, "name", "group")));
        g.origin = add(Json.vec(def, "origin", 3, new double[]{0, 0, 0}), off);
        g.rotation = Json.vec(def, "rotation", 3, new double[]{0, 0, 0});
        g.visible = Json.bool(def, "visibility", true) && Json.bool(def, "export", true);
        JsonArray ch = Json.arr(o, "children");
        if (ch != null) for (JsonElement c : ch) node(c, g, cubes, groupDefs, used, off);
        parent.children.add(g);
    }
}
