package mimt.scan;

import com.google.gson.*;
import mimt.asm.LayerMethods;
import mimt.conv.*;
import mimt.ir.BModel;
import mimt.ir.EMesh;
import mimt.mi.MiModel;
import mimt.read.*;
import mimt.src.Source;
import mimt.src.Sources;
import mimt.util.*;
import java.nio.file.Files;
import mimt.vanilla.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public class Workspace {
    public final Sources sources = new Sources();
    public final List<Entry> entries = new CopyOnWriteArrayList<>();
    public volatile boolean autoVanilla = true;
    public volatile boolean vanillaEntities = true;
    public volatile boolean tint = true;
    public volatile String mcVersion;
    private Map<String, int[]> itemTints;

    public synchronized Source addSource(Path p) throws Exception {
        Source s = Source.open(p.toAbsolutePath());
        sources.add(s);
        itemTints = null;
        Log.info(I18n.t("Source added: ") + s.displayName + " [" + kindName(s.kind) + (s.mcVersion != null ? ", MC " + s.mcVersion : "") + "]");
        List<Entry> found = Catalog.scan(s, vanillaEntities);
        entries.addAll(found);
        Log.info(I18n.t("  models found: ") + found.size());
        return s;
    }

    public List<Source> addPath(Path p) throws Exception {
        List<Source> r = new ArrayList<>();
        if (Files.isDirectory(p) && !Files.exists(p.resolve("assets")) && !Files.exists(p.resolve("pack.mcmeta"))
                && !Files.exists(p.resolve("models")) && !Files.exists(p.resolve("textures"))) {
            try (var st = Files.list(p)) {
                for (Path f : st.sorted().toList()) {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".jar") || n.endsWith(".zip")) {
                        try { r.add(addSource(f)); } catch (Exception e) { Log.warn(I18n.t("Failed to open ") + f.getFileName() + ": " + e.getMessage()); }
                    }
                }
            }
            if (!r.isEmpty()) return r;
        }
        r.add(addSource(p));
        return r;
    }

    public static String kindName(Source.Kind k) {
        switch (k) {
            case VANILLA: return "Minecraft";
            case MOD: return I18n.t("mod");
            case PACK: return I18n.t("resource pack");
            default: return I18n.t("archive/folder");
        }
    }

    public synchronized void removeSource(Source s) {
        entries.removeIf(e -> e.source == s);
        sources.remove(s);
        itemTints = null;
    }

    public List<Entry> entriesOf(Source s) {
        List<Entry> r = new ArrayList<>();
        for (Entry e : entries) if (e.source == s) r.add(e);
        return r;
    }

    public Entry find(String fullId) {
        for (Entry e : entries) if (e.fullId().equals(fullId) || e.id.equals(fullId)) return e;
        return null;
    }

    public String effectiveVersion(Source prefer) {
        if (mcVersion != null && !mcVersion.isBlank()) return mcVersion;
        if (prefer != null && prefer.mcVersion != null) return prefer.mcVersion;
        String h = sources.versionHint();
        if (h != null) return h;
        try { return VanillaMeta.latestRelease(); } catch (Exception e) { return null; }
    }

    public synchronized Source vanilla(Source prefer) {
        String ver = effectiveVersion(prefer);
        Source any = null;
        for (Source s : sources.all()) {
            if (s.kind != Source.Kind.VANILLA) continue;
            if (ver == null || ver.equals(s.mcVersion)) return s;
            if (any == null) any = s;
        }
        if (!autoVanilla || ver == null) return any;
        try {
            Log.info(I18n.t("Minecraft files needed: ") + ver + I18n.t(" — downloading the client (once, cached)…"));
            Path jar = VanillaMeta.clientJar(ver);
            Source s = Source.open(jar);
            s.hidden = true;
            if (s.mcVersion == null) s.mcVersion = ver;
            sources.add(s);
            itemTints = null;
            return s;
        } catch (Exception e) {
            Log.warn(I18n.t("Failed to get Minecraft ") + ver + ": " + e.getMessage());
            return any;
        }
    }

    public List<TextureGuess.Candidate> textureCandidates(Entry e) {
        String name;
        int w = 0, h = 0;
        switch (e.type) {
            case ENTITY: {
                name = e.id;
                EMesh m = mesh(e);
                if (m != null) { w = m.texW; h = m.texH; }
                break;
            }
            case JAVA: {
                LayerMethods.Found f = (LayerMethods.Found) e.extra;
                name = TextureGuess.javaName(f.simpleClass(), f.method());
                break;
            }
            case GEO: name = geoBaseName(e); break;
            default: name = e.name;
        }
        if (e.type == Entry.Type.CEM || e.type == Entry.Type.ENTITY) vanilla(e.source);
        return TextureGuess.rank(sources, e.source, name, w, h, 30);
    }

    private static String geoBaseName(Entry e) {
        String n = e.name;
        int sp = n.indexOf(" (");
        if (sp > 0) n = n.substring(0, sp);
        return n;
    }

    private BufferedImage loadTex(Source s, String path) {
        BufferedImage img = Img.read(sources.read(path, s));
        if (img == null) return null;
        return Img.firstFrame(img, sources.read(path + ".mcmeta", s));
    }

    private BufferedImage bestTexture(Entry e, MiModel note) {
        List<TextureGuess.Candidate> c = textureCandidates(e);
        if (c.isEmpty()) return null;
        TextureGuess.Candidate best = c.get(0);
        note.notes.add(I18n.t("Texture picked automatically: ") + best.path());
        return loadTex(best.source(), best.path());
    }

    private EMesh mesh(Entry e) {
        if (e.type == Entry.Type.ENTITY) {
            try { return VanillaLayers.get(e.source).get(e.id); } catch (Exception ex) { return null; }
        }
        if (e.type == Entry.Type.JAVA) {
            List<Function<String, byte[]>> src = new ArrayList<>();
            src.add(e.source::read);
            for (Source s : sources.ordered()) if (s != e.source && s.kind == Source.Kind.MOD) src.add(s::read);
            EMesh r = LayerMethods.run(src, (LayerMethods.Found) e.extra);
            if (r != null) return r;
            Source v = vanilla(e.source);
            if (v == null) return null;
            src.add(VanillaClasses.provider(v));
            return LayerMethods.run(src, (LayerMethods.Found) e.extra);
        }
        return null;
    }

    public static String safe(String n) {
        String s = n.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        return s.isEmpty() ? "model" : s;
    }

    public MiModel export(Entry e, BufferedImage texOverride) throws Exception {
        MiModel m;
        String base = safe(e.name.replace("()", "").replace(' ', '_'));
        String texFile = base + ".png";
        switch (e.type) {
            case BLOCK: case ITEM: case JSON: {
                BlockConv.Options o = new BlockConv.Options();
                o.tint = tint;
                o.tints = itemTints().get(e.id);
                try {
                    m = BlockConv.convert(sources, e.id, e.source, o);
                } catch (IllegalStateException ex) {
                    if (ex.getMessage() != null && ex.getMessage().startsWith(I18n.t("Parent model not found")) && vanilla(e.source) != null)
                        m = BlockConv.convert(sources, e.id, e.source, o);
                    else throw ex;
                }
                if (needsVanillaTextures(m) && vanilla(e.source) != null) m = BlockConv.convert(sources, e.id, e.source, o);
                break;
            }
            case ENTITY: case JAVA: {
                EMesh mesh = mesh(e);
                if (mesh == null) throw new IllegalStateException(I18n.t("Could not evaluate the model geometry (code is too complex or depends on the running game)"));
                MiModel tmp = new MiModel();
                BufferedImage tex = texOverride != null ? texOverride : bestTexture(e, tmp);
                m = EntityConv.convert(base, mesh, tex, texFile);
                m.notes.addAll(0, tmp.notes);
                break;
            }
            case GEO: {
                byte[] b = e.source.read(e.path);
                BModel bm = null;
                for (BedrockReader.Geo g : BedrockReader.read(b, e.name)) if (g.id.equals(e.extra)) bm = g.model;
                if (bm == null) throw new IllegalStateException(I18n.t("Geometry not found"));
                MiModel tmp = new MiModel();
                BufferedImage tex = texOverride;
                if (tex == null) tex = bedrockEntityTexture(e, (String) e.extra, tmp);
                if (tex == null) tex = bestTexture(e, tmp);
                bm.name = base;
                m = BbConv.convert(bm, texFile, tex);
                m.notes.addAll(0, tmp.notes);
                break;
            }
            case BBMODEL: {
                String dir = e.path.contains("/") ? e.path.substring(0, e.path.lastIndexOf('/') + 1) : "";
                BModel bm = BbModelReader.read(e.source.read(e.path), base, rel -> e.source.read(dir + rel));
                bm.name = base;
                m = BbConv.convert(bm, texFile, texOverride);
                break;
            }
            case CEM: {
                String dir = e.path.substring(0, e.path.lastIndexOf('/') + 1);
                byte[] b = e.source.read(e.path);
                Function<String, byte[]> jpm = rel -> {
                    String r = rel.endsWith(".jpm") ? rel : rel + ".jpm";
                    byte[] x = sources.read(dir + r, e.source);
                    if (x == null) x = sources.read(resolveOptifine(r, dir), e.source);
                    return x;
                };
                BModel bm = JemReader.read(b, base, jpm);
                MiModel tmp = new MiModel();
                BufferedImage tex = texOverride;
                String tp = JemReader.texturePath(Json.parse(b));
                if (tex == null && tp != null) {
                    tex = loadTex(e.source, resolveOptifine(tp.endsWith(".png") ? tp : tp + ".png", dir));
                    if (tex == null) { vanilla(e.source); tex = loadTex(e.source, resolveOptifine(tp.endsWith(".png") ? tp : tp + ".png", dir)); }
                }
                if (tex == null) tex = bestTexture(e, tmp);
                m = BbConv.convert(bm, texFile, tex);
                m.notes.addAll(0, tmp.notes);
                break;
            }
            default: throw new IllegalStateException(I18n.t("Unsupported type"));
        }
        m.name = base;
        m.description = I18n.t("Exported by mimodel-tool: ") + e.fullId() + I18n.t(" of ") + e.source.displayName;
        return m;
    }

    private static boolean needsVanillaTextures(MiModel m) {
        for (String n : m.notes) if (n.startsWith(I18n.t("Some textures not found"))) return true;
        return false;
    }

    static String resolveOptifine(String tp, String jemDir) {
        tp = tp.replace('\\', '/');
        if (tp.contains(":")) {
            String ns = tp.substring(0, tp.indexOf(':')), p = tp.substring(tp.indexOf(':') + 1);
            return "assets/" + ns + "/" + p;
        }
        if (tp.startsWith("textures/") || tp.startsWith("optifine/")) return "assets/minecraft/" + tp;
        if (tp.startsWith("./")) tp = tp.substring(2);
        return jemDir + tp;
    }

    private BufferedImage bedrockEntityTexture(Entry e, String geoId, MiModel note) {
        for (String p : e.source.list()) {
            if (!(p.startsWith("entity/") && p.endsWith(".json"))) continue;
            try {
                JsonElement j = Json.parse(e.source.read(p));
                JsonObject desc = Json.obj(Json.obj(j, "minecraft:client_entity"), "description");
                JsonObject geo = Json.obj(desc, "geometry"), tex = Json.obj(desc, "textures");
                if (geo == null || tex == null) continue;
                for (var en : geo.entrySet()) {
                    if (!en.getValue().isJsonPrimitive() || !en.getValue().getAsString().equals(geoId)) continue;
                    String t = Json.str(tex, en.getKey(), null);
                    if (t == null) t = tex.entrySet().isEmpty() ? null : tex.entrySet().iterator().next().getValue().getAsString();
                    if (t == null) continue;
                    for (String ext : new String[]{".png", ".tga", ""}) {
                        BufferedImage img = Img.read(e.source.read(t + ext));
                        if (img != null) { note.notes.add(I18n.t("Texture from ") + p + ": " + t); return img; }
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private synchronized Map<String, int[]> itemTints() {
        if (itemTints != null) return itemTints;
        Map<String, int[]> m = new HashMap<>();
        for (Source s : sources.ordered()) {
            for (String p : s.list()) {
                if (!p.startsWith("assets/") || !p.endsWith(".json")) continue;
                String[] parts = p.split("/");
                if (parts.length < 4 || !parts[2].equals("items")) continue;
                try {
                    JsonElement j = Json.parse(s.read(p));
                    collectTints(Json.obj(j, "model"), m);
                } catch (Exception ignored) { }
            }
        }
        itemTints = m;
        return m;
    }

    private static void collectTints(JsonElement node, Map<String, int[]> out) {
        if (node == null || !node.isJsonObject()) return;
        String type = Json.str(node, "type", "");
        if (type.endsWith("model")) {
            String model = BlockConv.norm(Json.str(node, "model", null));
            JsonArray tints = Json.arr(node, "tints");
            if (model != null && tints != null && !out.containsKey(model)) {
                int[] t = new int[tints.size()];
                for (int i = 0; i < t.length; i++) {
                    JsonElement ti = tints.get(i);
                    String tt = Json.str(ti, "type", "");
                    if (tt.endsWith("constant")) t[i] = (int) Json.num(ti, "value", -1);
                    else if (tt.endsWith("grass")) t[i] = 0x91BD59;
                    else if (Json.obj(ti, "default") == null && ti.isJsonObject() && ti.getAsJsonObject().has("default")) t[i] = (int) Json.num(ti, "default", -1);
                    else t[i] = -1;
                }
                out.put(model, t);
            }
            return;
        }
        for (var en : node.getAsJsonObject().entrySet()) {
            JsonElement v = en.getValue();
            if (v.isJsonObject()) collectTints(v, out);
            else if (v.isJsonArray()) for (JsonElement x : v.getAsJsonArray()) {
                if (x.isJsonObject()) {
                    collectTints(x, out);
                    collectTints(Json.obj(x, "model"), out);
                }
            }
        }
    }
}
