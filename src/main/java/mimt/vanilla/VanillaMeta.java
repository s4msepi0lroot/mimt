package mimt.vanilla;

import com.google.gson.*;
import mimt.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class VanillaMeta {
    public static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static JsonObject manifest;

    public static synchronized JsonObject manifest() throws IOException {
        if (manifest != null) return manifest;
        Path f = Http.cacheDir().resolve("version_manifest_v2.json");
        try {
            boolean fresh = Files.isRegularFile(f) && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < 6 * 3600_000L;
            if (!fresh) {
                byte[] b = Http.get(MANIFEST);
                Files.write(f, b);
            }
        } catch (IOException e) {
            if (!Files.isRegularFile(f)) throw e;
        }
        manifest = Json.parse(Files.readAllBytes(f)).getAsJsonObject();
        return manifest;
    }

    public static List<String> releases() {
        List<String> r = new ArrayList<>();
        try {
            for (JsonElement v : manifest().getAsJsonArray("versions")) {
                if (!"release".equals(Json.str(v, "type", ""))) continue;
                String id = Json.str(v, "id", "");
                if (id.startsWith("1.") && !id.startsWith("1.20") && !id.startsWith("1.21") && !id.startsWith("1.22")) continue;
                r.add(id);
            }
        } catch (IOException e) {
            Log.warn(I18n.t("Failed to get the version list: ") + e.getMessage());
        }
        return r;
    }

    public static String latestRelease() throws IOException {
        return Json.str(Json.obj(manifest(), "latest"), "release", null);
    }

    public static JsonObject versionJson(String id) throws IOException {
        Path f = Http.cacheDir().resolve("versions").resolve(id + ".json");
        if (!Files.isRegularFile(f)) {
            Path local = minecraftDir().resolve("versions").resolve(id).resolve(id + ".json");
            byte[] b = null;
            if (Files.isRegularFile(local)) {
                b = Files.readAllBytes(local);
                if (Json.obj(Json.parse(b), "downloads") == null) b = null;
            }
            if (b == null) {
                String url = null;
                for (JsonElement v : manifest().getAsJsonArray("versions"))
                    if (id.equals(Json.str(v, "id", null))) url = Json.str(v, "url", null);
                if (url == null) throw new IOException(I18n.t("Unknown Minecraft version: ") + id);
                b = Http.get(url);
            }
            Files.createDirectories(f.getParent());
            Files.write(f, b);
        }
        return Json.parse(Files.readAllBytes(f)).getAsJsonObject();
    }

    public static Path minecraftDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata != null) return Paths.get(appdata, ".minecraft");
        String home = System.getProperty("user.home");
        Path mac = Paths.get(home, "Library", "Application Support", "minecraft");
        if (Files.isDirectory(mac)) return mac;
        return Paths.get(home, ".minecraft");
    }

    public static Path clientJar(String id) throws IOException {
        Path local = minecraftDir().resolve("versions").resolve(id).resolve(id + ".jar");
        JsonObject vj = versionJson(id);
        JsonObject cl = Json.obj(Json.obj(vj, "downloads"), "client");
        String sha1 = Json.str(cl, "sha1", null);
        if (Files.isRegularFile(local) && sha1 != null && Files.size(local) == (long) Json.num(cl, "size", -1)) return local;
        return Http.cached(Json.str(cl, "url", null), "versions/" + id + ".jar", sha1);
    }

    public static byte[] clientMappings(String id) throws IOException {
        JsonObject vj = versionJson(id);
        JsonObject m = Json.obj(Json.obj(vj, "downloads"), "client_mappings");
        if (m == null) return null;
        return Files.readAllBytes(Http.cached(Json.str(m, "url", null), "versions/" + id + "-client.txt", Json.str(m, "sha1", null)));
    }

    public static List<Path> libraries(String id) throws IOException {
        JsonObject vj = versionJson(id);
        List<Path> out = new ArrayList<>();
        Path localLibs = minecraftDir().resolve("libraries");
        for (JsonElement l : vj.getAsJsonArray("libraries")) {
            String name = Json.str(l, "name", "");
            if (name.contains(":natives-") || Json.obj(l, "natives") != null && Json.obj(Json.obj(l, "downloads"), "artifact") == null) continue;
            JsonObject art = Json.obj(Json.obj(l, "downloads"), "artifact");
            if (art == null) continue;
            String path = Json.str(art, "path", null);
            if (path == null) continue;
            Path local = localLibs.resolve(path);
            long size = (long) Json.num(art, "size", -1);
            if (Files.isRegularFile(local) && Files.size(local) == size) { out.add(local); continue; }
            out.add(Http.cached(Json.str(art, "url", null), "libraries/" + path, Json.str(art, "sha1", null)));
        }
        return out;
    }
}
