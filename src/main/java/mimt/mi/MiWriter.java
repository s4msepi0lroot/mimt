package mimt.mi;

import com.google.gson.*;
import mimt.util.Img;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

public final class MiWriter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static JsonObject toJson(MiModel m) {
        JsonObject o = new JsonObject();
        o.addProperty("name", m.name);
        if (m.description != null) o.addProperty("description", m.description);
        o.addProperty("texture", m.texture);
        o.add("texture_size", arr(m.textureSize));
        JsonArray parts = new JsonArray();
        for (MiPart p : m.parts) parts.add(part(p));
        o.add("parts", parts);
        return o;
    }

    private static JsonObject part(MiPart p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.name);
        if (p.texture != null) {
            o.addProperty("texture", p.texture);
            o.add("texture_size", arr(p.textureSize));
        }
        o.add("position", arr(p.position));
        if (nonZero(p.rotation)) o.add("rotation", arr(p.rotation));
        if (p.scale != null && !(p.scale[0] == 1 && p.scale[1] == 1 && p.scale[2] == 1)) o.add("scale", arr(p.scale));
        if (!p.shapes.isEmpty()) {
            JsonArray a = new JsonArray();
            for (MiShape s : p.shapes) a.add(shape(s));
            o.add("shapes", a);
        }
        if (!p.parts.isEmpty()) {
            JsonArray a = new JsonArray();
            for (MiPart c : p.parts) a.add(part(c));
            o.add("parts", a);
        }
        return o;
    }

    private static JsonObject shape(MiShape s) {
        JsonObject o = new JsonObject();
        o.addProperty("type", s.type);
        if (s.description != null && !s.description.isEmpty()) o.addProperty("description", s.description);
        if (s.texture != null) {
            o.addProperty("texture", s.texture);
            o.add("texture_size", arr(s.textureSize));
        }
        o.add("from", arr(s.from));
        o.add("to", arr(s.to));
        o.add("uv", arr(s.uv));
        if (s.inflate != 0) o.addProperty("inflate", num(s.inflate));
        if (s.mirror) o.addProperty("texture_mirror", true);
        if (s.is3d) o.addProperty("3d", true);
        if (s.hideBack) o.addProperty("hide_back", true);
        if (nonZero(s.position)) o.add("position", arr(s.position));
        if (nonZero(s.rotation)) o.add("rotation", arr(s.rotation));
        if (s.scale != null && !(s.scale[0] == 1 && s.scale[1] == 1 && s.scale[2] == 1)) o.add("scale", arr(s.scale));
        return o;
    }

    private static boolean nonZero(double[] v) {
        if (v == null) return false;
        for (double d : v) if (Math.abs(d) > 1e-9) return true;
        return false;
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(num(d));
        return a;
    }

    static Number num(double d) {
        double r = Math.round(d * 10000.0) / 10000.0;
        if (r == 0) return 0;
        if (r == Math.rint(r) && Math.abs(r) < 1e9) return (long) r;
        return r;
    }

    public static String toString(MiModel m) { return GSON.toJson(toJson(m)); }

    public static Path write(MiModel m, Path dir, String fileBase) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve(fileBase + ".mimodel");
        Files.writeString(f, toString(m), StandardCharsets.UTF_8);
        for (Map.Entry<String, java.awt.image.BufferedImage> e : m.textures.entrySet())
            Files.write(dir.resolve(e.getKey()), Img.png(e.getValue()));
        return f;
    }
}
