package mimt.vanilla;

import com.google.gson.*;
import mimt.ir.EMesh;
import mimt.src.Source;
import mimt.util.*;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class VanillaLayers {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Map<String, Map<String, EMesh>> MEMO = new HashMap<>();
    private static final int CACHE_FORMAT = 1;

    public static synchronized Map<String, EMesh> get(Source vanilla) throws Exception {
        String ver = vanilla.mcVersion == null ? "unknown" : vanilla.mcVersion;
        Map<String, EMesh> m = MEMO.get(ver);
        if (m != null) return m;
        Path cache = Http.cacheDir().resolve("layers").resolve(ver + "-v" + CACHE_FORMAT + ".json");
        if (Files.isRegularFile(cache)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)).getAsJsonObject();
                m = new LinkedHashMap<>();
                for (var e : o.entrySet()) m.put(e.getKey(), GSON.fromJson(e.getValue(), EMesh.class));
                MEMO.put(ver, m);
                return m;
            } catch (Exception e) {
                Log.warn(I18n.t("Model cache is corrupted, extracting again"));
            }
        }
        m = extract(vanilla, ver);
        JsonObject o = new JsonObject();
        for (var e : m.entrySet()) o.add(e.getKey(), GSON.toJsonTree(e.getValue()));
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, GSON.toJson(o), StandardCharsets.UTF_8);
        MEMO.put(ver, m);
        return m;
    }

    private static final String LD = "net/minecraft/client/model/geom/LayerDefinitions";

    private static Map<String, EMesh> extract(Source vanilla, String ver) throws Exception {
        boolean named = vanilla.exists(LD + ".class");
        ProguardMap map = null;
        if (!named) {
            Log.info(I18n.t("Downloading Mojang mappings for ") + ver + "…");
            byte[] mp = VanillaMeta.clientMappings(ver);
            if (mp == null) throw new IllegalStateException(I18n.t("No mappings for version ") + ver);
            map = ProguardMap.parse(mp);
        }
        Log.info(I18n.t("Downloading Minecraft libraries ") + ver + "…");
        List<Path> libs = VanillaMeta.libraries(ver);
        List<URL> urls = new ArrayList<>();
        urls.add(vanilla.path.toUri().toURL());
        for (Path p : libs) urls.add(p.toUri().toURL());
        Log.info(I18n.t("Extracting entity models from Minecraft ") + ver + "…");
        final ProguardMap fm = map;
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Thread t = Thread.currentThread();
            ClassLoader old = t.getContextClassLoader();
            java.io.PrintStream out = System.out, err = System.err;
            Thread.UncaughtExceptionHandler ueh = Thread.getDefaultUncaughtExceptionHandler();
            t.setContextClassLoader(cl);
            try {
                return new Walker(fm, cl).run();
            } finally {
                System.setOut(out);
                System.setErr(err);
                Thread.setDefaultUncaughtExceptionHandler(ueh);
                t.setContextClassLoader(old);
            }
        }
    }

    private static final class Walker {
        final ProguardMap map;
        final ClassLoader cl;

        Walker(ProguardMap map, ClassLoader cl) { this.map = map; this.cl = cl; }

        String cls(String named) { return (map == null ? named : map.cls(named.replace('/', '.'))).replace('/', '.'); }

        String fld(String cls, String f) { return map == null ? f : map.field(cls.replace('/', '.'), f); }

        Object get(Object o, String namedCls, String f) throws Exception {
            String n = fld(namedCls, f);
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field fl = c.getDeclaredField(n);
                    fl.setAccessible(true);
                    return fl.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(namedCls + "." + f);
        }

        Object opt(Object o, String namedCls, String f) {
            try { return get(o, namedCls, f); } catch (Exception e) { return null; }
        }

        float fl(Object o, String namedCls, String f, float def) {
            Object v = opt(o, namedCls, f);
            return v instanceof Number ? ((Number) v).floatValue() : def;
        }

        Map<String, EMesh> run() throws Exception {
            try {
                Class<?> sc = Class.forName(cls("net/minecraft/SharedConstants"), true, cl);
                Method m = null;
                String name = map == null ? "tryDetectVersion" : map.method("net.minecraft.SharedConstants", "tryDetectVersion()");
                try { m = sc.getMethod(name); } catch (NoSuchMethodException ignored) { }
                if (m != null) m.invoke(null);
            } catch (Throwable ignored) { }
            try {
                Class<?> bs = Class.forName(cls("net/minecraft/server/Bootstrap"), true, cl);
                String bn = map == null ? "bootStrap" : map.method("net.minecraft.server.Bootstrap", "bootStrap()");
                bs.getMethod(bn).invoke(null);
            } catch (Throwable e) {
                Log.warn("Bootstrap: " + (e instanceof InvocationTargetException ite ? ite.getCause() : e));
            }
            Class<?> ld = Class.forName(cls(LD), true, cl);
            String mname = map == null ? "createRoots" : map.method("net.minecraft.client.model.geom.LayerDefinitions", "createRoots()");
            Method create = ld.getDeclaredMethod(mname);
            create.setAccessible(true);
            Map<?, ?> roots = (Map<?, ?>) create.invoke(null);
            Map<String, EMesh> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : roots.entrySet()) {
                String id = e.getKey().toString();
                try {
                    out.put(id, layer(e.getValue()));
                } catch (Exception ex) {
                    Log.warn(I18n.t("Layer ") + id + ": " + ex);
                }
            }
            Log.info(I18n.t("Model layers extracted: ") + out.size());
            return out;
        }

        static final String P = "net/minecraft/client/model/geom/";
        static final String B = P + "builders/";

        EMesh layer(Object def) throws Exception {
            EMesh m = new EMesh();
            Object mat = get(def, B + "LayerDefinition", "material");
            m.texW = ((Number) get(mat, B + "MaterialDefinition", "xTexSize")).intValue();
            m.texH = ((Number) get(mat, B + "MaterialDefinition", "yTexSize")).intValue();
            Object mesh = get(def, B + "LayerDefinition", "mesh");
            m.root = part("root", get(mesh, B + "MeshDefinition", "root"));
            return m;
        }

        EMesh.EPart part(String name, Object p) throws Exception {
            EMesh.EPart ep = new EMesh.EPart(name);
            Object pose = get(p, B + "PartDefinition", "partPose");
            String pp = P + "PartPose";
            ep.x = fl(pose, pp, "x", 0); ep.y = fl(pose, pp, "y", 0); ep.z = fl(pose, pp, "z", 0);
            ep.xRot = fl(pose, pp, "xRot", 0); ep.yRot = fl(pose, pp, "yRot", 0); ep.zRot = fl(pose, pp, "zRot", 0);
            ep.xScale = fl(pose, pp, "xScale", 1); ep.yScale = fl(pose, pp, "yScale", 1); ep.zScale = fl(pose, pp, "zScale", 1);
            for (Object c : (List<?>) get(p, B + "PartDefinition", "cubes")) ep.cubes.add(cube(c));
            for (Map.Entry<?, ?> e : ((Map<?, ?>) get(p, B + "PartDefinition", "children")).entrySet())
                ep.children.put((String) e.getKey(), part((String) e.getKey(), e.getValue()));
            return ep;
        }

        float vec(Object v, String f) throws Exception {
            Field fl = v.getClass().getField(f);
            return fl.getFloat(v);
        }

        EMesh.ECube cube(Object c) throws Exception {
            String cd = B + "CubeDefinition";
            EMesh.ECube ec = new EMesh.ECube();
            Object o = get(c, cd, "origin"), d = get(c, cd, "dimensions");
            ec.ox = vec(o, "x"); ec.oy = vec(o, "y"); ec.oz = vec(o, "z");
            ec.w = vec(d, "x"); ec.h = vec(d, "y"); ec.d = vec(d, "z");
            Object g = get(c, cd, "grow");
            String cdf = B + "CubeDeformation";
            ec.gx = fl(g, cdf, "growX", 0); ec.gy = fl(g, cdf, "growY", 0); ec.gz = fl(g, cdf, "growZ", 0);
            ec.mirror = (Boolean) get(c, cd, "mirror");
            Object tc = get(c, cd, "texCoord"), ts = get(c, cd, "texScale");
            String uv = B + "UVPair";
            ec.u = fl(tc, uv, "u", 0); ec.v = fl(tc, uv, "v", 0);
            ec.su = fl(ts, uv, "u", 1); ec.sv = fl(ts, uv, "v", 1);
            Object vf = opt(c, cd, "visibleFaces");
            if (vf instanceof Set<?> s && s.size() < 6) {
                ec.faces = new TreeSet<>();
                for (Object dir : s) ec.faces.add(dir.toString().toLowerCase(Locale.ROOT));
            }
            return ec;
        }
    }
}
