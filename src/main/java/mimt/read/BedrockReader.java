package mimt.read;

import com.google.gson.*;
import mimt.ir.BModel;
import mimt.util.Json;
import java.util.*;

public final class BedrockReader {
    public static class Geo {
        public String id;
        public BModel model;
    }

    public static List<Geo> read(byte[] data, String fallbackName) {
        JsonElement root = Json.parse(data);
        List<Geo> out = new ArrayList<>();
        if (!root.isJsonObject()) return out;
        JsonObject o = root.getAsJsonObject();
        JsonArray geos = Json.arr(o, "minecraft:geometry");
        if (geos != null) {
            for (JsonElement g : geos) {
                if (!g.isJsonObject()) continue;
                JsonObject desc = Json.obj(g, "description");
                String id = Json.str(desc, "identifier", fallbackName);
                Geo geo = new Geo();
                geo.id = id;
                geo.model = model(g.getAsJsonObject(), id, Json.num(desc, "texture_width", 64), Json.num(desc, "texture_height", 64));
                out.add(geo);
            }
        } else {
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (!e.getKey().startsWith("geometry.") || !e.getValue().isJsonObject()) continue;
                JsonObject g = e.getValue().getAsJsonObject();
                String id = e.getKey().split(":")[0];
                Geo geo = new Geo();
                geo.id = id;
                geo.model = model(g, id, Json.num(g, "texturewidth", 64), Json.num(g, "textureheight", 64));
                out.add(geo);
            }
        }
        return out;
    }

    static String shortName(String id) {
        String n = id.startsWith("geometry.") ? id.substring(9) : id;
        return n.isEmpty() ? "model" : n;
    }

    private static BModel model(JsonObject g, String id, double tw, double th) {
        BModel bm = new BModel();
        bm.name = shortName(id);
        bm.texW = tw;
        bm.texH = th;
        JsonArray bones = Json.arr(g, "bones");
        Map<String, BModel.BGroup> byName = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        Map<String, Boolean> mirrors = new HashMap<>();
        List<String> order = new ArrayList<>();
        if (bones != null) {
            for (JsonElement b : bones) {
                if (!b.isJsonObject()) continue;
                String name = Json.str(b, "name", "bone");
                BModel.BGroup grp = new BModel.BGroup(name);
                double[] pivot = Json.vec(b, "pivot", 3, new double[]{0, 0, 0});
                grp.origin = new double[]{-pivot[0], pivot[1], pivot[2]};
                double[] rot = Json.vec(b, "rotation", 3, new double[]{0, 0, 0});
                grp.rotation = new double[]{-rot[0], -rot[1], rot[2]};
                if (Json.bool(b, "neverRender", false)) grp.visible = false;
                boolean boneMirror = Json.bool(b, "mirror", false);
                double boneInflate = Json.num(b, "inflate", 0);
                JsonArray cubes = Json.arr(b, "cubes");
                if (cubes != null) {
                    int i = 0;
                    for (JsonElement c : cubes) {
                        if (!c.isJsonObject()) continue;
                        BModel.BCube cube = cube(c.getAsJsonObject(), boneMirror, boneInflate);
                        if (cube != null) {
                            cube.name = name + (cubes.size() > 1 ? "_" + (i + 1) : "");
                            grp.cubes.add(cube);
                        }
                        i++;
                    }
                }
                String key = name;
                int n = 2;
                while (byName.containsKey(key)) key = name + "_" + n++;
                byName.put(key, grp);
                order.add(key);
                String par = Json.str(b, "parent", null);
                if (par != null) parents.put(key, par);
                mirrors.put(key, boneMirror);
            }
        }
        for (String k : order) {
            BModel.BGroup grp = byName.get(k);
            String par = parents.get(k);
            BModel.BGroup pg = par != null ? byName.get(par) : null;
            if (pg == null && par != null) {
                for (Map.Entry<String, BModel.BGroup> e : byName.entrySet())
                    if (e.getKey().equalsIgnoreCase(par)) { pg = e.getValue(); break; }
            }
            if (pg != null && pg != grp) pg.children.add(grp);
            else bm.root.children.add(grp);
        }
        return bm;
    }

    private static BModel.BCube cube(JsonObject c, boolean boneMirror, double boneInflate) {
        double[] origin = Json.vec(c, "origin", 3, null);
        double[] size = Json.vec(c, "size", 3, null);
        if (origin == null || size == null) return null;
        BModel.BCube cube = new BModel.BCube();
        cube.from = new double[]{-(origin[0] + size[0]), origin[1], origin[2]};
        cube.to = new double[]{cube.from[0] + size[0], origin[1] + size[1], origin[2] + size[2]};
        double[] pivot = Json.vec(c, "pivot", 3, null);
        double[] rot = Json.vec(c, "rotation", 3, null);
        if (rot != null) {
            cube.rotation = new double[]{-rot[0], -rot[1], rot[2]};
            if (pivot == null) pivot = new double[]{0, 0, 0};
            cube.origin = new double[]{-pivot[0], pivot[1], pivot[2]};
        }
        cube.inflate = Json.num(c, "inflate", boneInflate);
        cube.mirror = Json.bool(c, "mirror", boneMirror);
        JsonElement uv = c.get("uv");
        if (uv == null || uv.isJsonArray()) {
            cube.boxUv = true;
            double[] o = uv == null ? new double[]{0, 0} : Json.vec(uv.getAsJsonArray(), 2, new double[]{0, 0});
            cube.uvOffset = o;
        } else if (uv.isJsonObject()) {
            cube.boxUv = false;
            for (int f = 0; f < 6; f++) {
                JsonObject fo = Json.obj(uv, BModel.FACE_NAMES[f]);
                if (fo == null) continue;
                double[] p = Json.vec(fo, "uv", 2, null);
                if (p == null) continue;
                double[] s = Json.vec(fo, "uv_size", 2, new double[]{0, 0});
                double[] r = (f == BModel.UP || f == BModel.DOWN)
                        ? new double[]{p[0] + s[0], p[1] + s[1], p[0], p[1]}
                        : new double[]{p[0], p[1], p[0] + s[0], p[1] + s[1]};
                int rotation = (int) Json.num(fo, "uv_rotation", 0);
                cube.faces[f] = new BModel.BFace(r, rotation, 0);
            }
        }
        return cube;
    }
}
