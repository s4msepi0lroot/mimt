package mimt.src;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Sources {
    private final List<Source> list = new CopyOnWriteArrayList<>();

    public void add(Source s) { list.add(s); }

    public void remove(Source s) {
        list.remove(s);
        try { s.close(); } catch (Exception ignored) { }
    }

    public List<Source> all() { return list; }

    public List<Source> visible() {
        List<Source> r = new ArrayList<>();
        for (Source s : list) if (!s.hidden) r.add(s);
        return r;
    }

    public List<Source> ordered() {
        List<Source> r = new ArrayList<>();
        for (Source.Kind k : new Source.Kind[]{Source.Kind.PACK, Source.Kind.OTHER, Source.Kind.MOD, Source.Kind.VANILLA})
            for (Source s : list) if (s.kind == k && !s.hidden) r.add(s);
        for (Source s : list) if (s.hidden) r.add(s);
        return r;
    }

    public byte[] read(String path, Source preferred) {
        if (preferred != null) {
            byte[] b = preferred.read(path);
            if (b != null) return b;
        }
        for (Source s : ordered()) {
            if (s == preferred) continue;
            if (s.exists(path)) {
                byte[] b = s.read(path);
                if (b != null) return b;
            }
        }
        return null;
    }

    public byte[] read(String path) { return read(path, null); }

    public boolean exists(String path) {
        for (Source s : list) if (s.exists(path)) return true;
        return false;
    }

    public Source vanilla() {
        for (Source s : list) if (s.kind == Source.Kind.VANILLA) return s;
        return null;
    }

    public String versionHint() {
        for (Source s : list) if (s.kind == Source.Kind.VANILLA && s.mcVersion != null) return s.mcVersion;
        for (Source s : list) if (s.mcVersion != null) return s.mcVersion;
        return null;
    }
}
