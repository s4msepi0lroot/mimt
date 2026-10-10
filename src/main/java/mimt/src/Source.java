package mimt.src;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

public abstract class Source implements Closeable {
    public enum Kind { VANILLA, MOD, PACK, OTHER }

    public final Path path;
    public Kind kind = Kind.OTHER;
    public String displayName;
    public String mcVersion;
    public boolean hidden;

    protected Source(Path path) {
        this.path = path;
        this.displayName = path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    public abstract Collection<String> list();

    public abstract byte[] read(String p);

    public boolean exists(String p) { return list().contains(p); }

    public static Source open(Path p) throws IOException {
        Source s = Files.isDirectory(p) ? new DirSource(p) : new ZipSource(p);
        s.detect();
        return s;
    }

    private void detect() {
        Collection<String> l = list();
        if (l.contains("version.json") && (l.contains("net/minecraft/client/main/Main.class") || l.contains("assets/minecraft/lang/en_us.json") || l.contains("assets/minecraft/models/block/stone.json"))
                && !l.contains("fabric.mod.json") && !l.contains("META-INF/mods.toml")) {
            kind = Kind.VANILLA;
            try {
                String js = new String(read("version.json"), "UTF-8");
                Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(js);
                if (m.find()) mcVersion = m.group(1);
            } catch (Exception ignored) { }
            displayName = "Minecraft " + (mcVersion == null ? "?" : mcVersion) + " (" + displayName + ")";
        } else if (l.contains("fabric.mod.json") || l.contains("quilt.mod.json") || l.contains("META-INF/mods.toml") || l.contains("META-INF/neoforge.mods.toml")) {
            kind = Kind.MOD;
            mcVersion = modMcVersion();
        } else if (l.contains("pack.mcmeta") || l.contains("manifest.json") || anyStartsWith(l, "assets/")) {
            kind = Kind.PACK;
        }
    }

    private static boolean anyStartsWith(Collection<String> l, String p) {
        for (String s : l) if (s.startsWith(p)) return true;
        return false;
    }

    private String modMcVersion() {
        Pattern ver = Pattern.compile("(?<![\\w.])((?:1\\.\\d+(?:\\.\\d+)?)|(?:2\\d\\.\\d+(?:\\.\\d+)?))(?![\\w])");
        try {
            for (String f : new String[]{"fabric.mod.json", "quilt.mod.json"}) {
                byte[] b = read(f);
                if (b == null) continue;
                com.google.gson.JsonElement dep = mimt.util.Json.obj(mimt.util.Json.parse(b), "depends");
                if (dep == null) dep = mimt.util.Json.obj(mimt.util.Json.obj(mimt.util.Json.parse(b), "quilt_loader"), "depends");
                if (dep != null && dep.getAsJsonObject().has("minecraft")) {
                    Matcher v = ver.matcher(dep.getAsJsonObject().get("minecraft").toString());
                    if (v.find()) return v.group(1);
                }
            }
            for (String f : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                byte[] b = read(f);
                if (b == null) continue;
                String s = new String(b, "UTF-8");
                Matcher m = Pattern.compile("modId\\s*=\\s*\"minecraft\"[\\s\\S]*?versionRange\\s*=\\s*\"([^\"]*)\"").matcher(s);
                if (m.find()) {
                    Matcher v = ver.matcher(m.group(1));
                    if (v.find()) return v.group(1);
                }
            }
        } catch (Exception ignored) { }
        int inter = 0, srg = 0, n = 0;
        for (String e : list()) {
            if (!e.endsWith(".class") || n++ > 400) continue;
            byte[] b = read(e);
            if (b == null) continue;
            String t = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (t.contains("net/minecraft/class_")) inter++;
            if (t.matches("(?s).*\\bm_\\d+_.*")) srg++;
        }
        if (srg > 0) return "1.20.1";
        if (inter > 0) return "1.21.1";
        return null;
    }

    @Override
    public String toString() { return displayName; }

    public static class ZipSource extends Source {
        private final ZipFile zip;
        private final LinkedHashSet<String> names = new LinkedHashSet<>();

        public ZipSource(Path p) throws IOException {
            super(p);
            zip = new ZipFile(p.toFile());
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory()) names.add(e.getName().replace('\\', '/'));
            }
        }

        @Override public Collection<String> list() { return names; }

        @Override public boolean exists(String p) { return names.contains(p); }

        @Override public synchronized byte[] read(String p) {
            ZipEntry e = zip.getEntry(p);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            } catch (IOException ex) {
                return null;
            }
        }

        @Override public void close() throws IOException { zip.close(); }
    }

    public static class DirSource extends Source {
        private final LinkedHashSet<String> names = new LinkedHashSet<>();

        public DirSource(Path p) throws IOException {
            super(p);
            try (var st = Files.walk(p)) {
                st.filter(Files::isRegularFile).forEach(f -> names.add(p.relativize(f).toString().replace('\\', '/')));
            }
        }

        @Override public Collection<String> list() { return names; }

        @Override public boolean exists(String p) { return names.contains(p); }

        @Override public byte[] read(String p) {
            if (!names.contains(p)) return null;
            try {
                return Files.readAllBytes(path.resolve(p));
            } catch (IOException e) {
                return null;
            }
        }

        @Override public void close() { }
    }
}
