package mimt.util;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static JsonElement parse(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        com.google.gson.stream.JsonReader r = new com.google.gson.stream.JsonReader(new StringReader(s));
        r.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(r);
    }

    public static JsonObject obj(JsonElement e, String k) {
        if (e == null || !e.isJsonObject()) return null;
        JsonElement v = e.getAsJsonObject().get(k);
        return v != null && v.isJsonObject() ? v.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonElement e, String k) {
        if (e == null || !e.isJsonObject()) return null;
        JsonElement v = e.getAsJsonObject().get(k);
        return v != null && v.isJsonArray() ? v.getAsJsonArray() : null;
    }

    public static String str(JsonElement e, String k, String def) {
        if (e == null || !e.isJsonObject()) return def;
        JsonElement v = e.getAsJsonObject().get(k);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : def;
    }

    public static double num(JsonElement e, String k, double def) {
        if (e == null || !e.isJsonObject()) return def;
        JsonElement v = e.getAsJsonObject().get(k);
        if (v != null && v.isJsonPrimitive()) {
            try { return v.getAsDouble(); } catch (Exception ignored) { }
        }
        return def;
    }

    public static boolean bool(JsonElement e, String k, boolean def) {
        if (e == null || !e.isJsonObject()) return def;
        JsonElement v = e.getAsJsonObject().get(k);
        if (v != null && v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble() != 0;
            if (p.isString()) return p.getAsString().equalsIgnoreCase("true");
        }
        return def;
    }

    public static double[] vec(JsonElement e, String k, int n, double[] def) {
        JsonArray a = arr(e, k);
        if (a == null) {
            if (e != null && e.isJsonObject()) {
                JsonElement v = e.getAsJsonObject().get(k);
                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                    double[] r = new double[n];
                    java.util.Arrays.fill(r, v.getAsDouble());
                    return r;
                }
            }
            return def;
        }
        return vec(a, n, def);
    }

    public static double[] vec(JsonArray a, int n, double[] def) {
        if (a == null || a.size() < n) return def;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            try { r[i] = a.get(i).getAsDouble(); } catch (Exception ex) { return def; }
        }
        return r;
    }
}
