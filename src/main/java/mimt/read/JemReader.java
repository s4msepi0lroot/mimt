package mimt.read;

import com.google.gson.*;
import mimt.ir.BModel;
import mimt.util.Json;
import java.util.function.Function;

public final class JemReader {
    public static String texturePath(JsonElement jem) { return Json.str(jem, "texture", null); }

    public static BModel read(byte[] data, String name, Function<String, byte[]> jpmLoader) {
        JsonElement root = Json.parse(data);
        BModel bm = new BModel();
        bm.name = name;
        double[] ts = Json.vec(root, "textureSize", 2, new double[]{64, 32});
        bm.texW = ts[0];
        bm.texH = ts[1];
        JsonArray models = Json.arr(root, "models");
        if (models == null) {
            BModel.BGroup g = new BModel.BGroup(name);
            readContent(root.getAsJsonObject(), g, 0, true, false);
            bm.root.children.add(g);
            return bm;
        }
        int idx = 0;
        for (JsonElement e : models) {
            if (!e.isJsonObject()) continue;
            JsonObject b = e.getAsJsonObject().deepCopy();
            String model = Json.str(b, "model", null);
            if (model != null && jpmLoader != null) {
                byte[] jpm = jpmLoader.apply(model);
                if (jpm != null) {
                    JsonElement j = Json.parse(jpm);
                    if (j.isJsonObject())
                        for (var en : j.getAsJsonObject().entrySet()) if (!b.has(en.getKey())) b.add(en.getKey(), en.getValue());
                }
            }
            String part = Json.str(b, "part", Json.str(b, "id", "part" + idx));
            idx++;
            BModel.BGroup g = new BModel.BGroup(part);
            double[] tr = Json.vec(b, "translate", 3, new double[]{0, 0, 0});
            g.origin = new double[]{-tr[0], -tr[1], -tr[2]};
            g.rotation = Json.vec(b, "rotate", 3, new double[]{0, 0, 0});
            String mt = Json.str(b, "mirrorTexture", "");
            readContent(b, g, 0, true, mt.contains("u"));
            bm.root.children.add(g);
        }
        return bm;
    }

    private static void readContent(JsonObject sub, BModel.BGroup g, int depth, boolean topLevel, boolean mirror) {
        JsonArray boxes = Json.arr(sub, "boxes");
        if (boxes != null) {
            for (JsonElement be : boxes) {
                if (!be.isJsonObject()) continue;
                JsonObject box = be.getAsJsonObject();
                double[] c = Json.vec(box, "coordinates", 6, null);
                if (c == null) continue;
                BModel.BCube cube = new BModel.BCube();
                cube.name = Json.str(box, "name", g.name);
                cube.from = new double[]{c[0], c[1], c[2]};
                cube.to = new double[]{c[0] + c[3], c[1] + c[4], c[2] + c[5]};
                if (!topLevel) {
                    for (int i = 0; i < 3; i++) { cube.from[i] += g.origin[i]; cube.to[i] += g.origin[i]; }
                }
                cube.inflate = Json.num(box, "sizeAdd", 0);
                cube.mirror = mirror;
                double[] to = Json.vec(box, "textureOffset", 2, null);
                if (to != null) {
                    cube.boxUv = true;
                    cube.uvOffset = to;
                } else {
                    cube.boxUv = false;
                    String[] keys = {"uvNorth", "uvSouth", "uvEast", "uvWest", "uvUp", "uvDown"};
                    for (int f = 0; f < 6; f++) {
                        double[] uv = Json.vec(box, keys[f], 4, null);
                        if (uv != null) cube.faces[f] = new BModel.BFace(uv, 0, 0);
                    }
                }
                g.cubes.add(cube);
            }
        }
        JsonArray subs = Json.arr(sub, "submodels");
        if (subs == null) {
            JsonObject one = Json.obj(sub, "submodel");
            if (one != null) { subs = new JsonArray(); subs.add(one); }
        }
        if (subs != null) {
            int n = 0;
            for (JsonElement se : subs) {
                if (!se.isJsonObject()) continue;
                JsonObject ss = se.getAsJsonObject();
                double[] tr = Json.vec(ss, "translate", 3, null);
                if (depth >= 1 && tr != null) {
                    tr = new double[]{tr[0] + g.origin[0], tr[1] + g.origin[1], tr[2] + g.origin[2]};
                    JsonArray a = new JsonArray();
                    for (double d : tr) a.add(d);
                    ss.add("translate", a);
                }
                String nm = Json.str(ss, "id", Json.str(ss, "comment", g.name + "_sub_" + n));
                n++;
                BModel.BGroup cg = new BModel.BGroup(nm);
                double[] parentTr = Json.vec(sub, "translate", 3, null);
                cg.origin = tr != null ? tr : (depth >= 1 && parentTr != null ? parentTr : new double[]{0, 0, 0});
                cg.rotation = Json.vec(ss, "rotate", 3, new double[]{0, 0, 0});
                String mt = Json.str(ss, "mirrorTexture", "");
                readContent(ss, cg, depth + 1, false, mt.contains("u"));
                g.children.add(cg);
            }
        }
    }
}
