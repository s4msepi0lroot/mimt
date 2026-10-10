package mimt.scan;

import mimt.util.I18n;
import mimt.asm.LayerMethods;
import mimt.ir.EMesh;
import mimt.read.BedrockReader;
import mimt.src.Source;
import mimt.util.Log;
import mimt.vanilla.VanillaLayers;
import java.util.*;

public final class Catalog {
    public static List<Entry> scan(Source s, boolean vanillaEntities) {
        List<Entry> out = new ArrayList<>();
        for (String p : s.list()) {
            if (!p.startsWith("assets/") && !p.contains(".geo.json") && !p.endsWith(".bbmodel") && !p.endsWith(".jem")) continue;
            String[] parts = p.split("/");
            try {
                if (parts.length >= 4 && parts[0].equals("assets") && parts[2].equals("models") && p.endsWith(".json")) {
                    String ns = parts[1];
                    String rel = p.substring(("assets/" + ns + "/models/").length(), p.length() - 5);
                    Entry.Type t = rel.startsWith("block/") || rel.startsWith("blocks/") ? Entry.Type.BLOCK
                            : rel.startsWith("item/") || rel.startsWith("items/") ? Entry.Type.ITEM : Entry.Type.JSON;
                    if (s.kind == Source.Kind.PACK && p.contains(".geo")) t = Entry.Type.GEO;
                    if (t != Entry.Type.GEO) {
                        String group = ns + (rel.contains("/") ? "/" + rel.substring(0, rel.lastIndexOf('/')) : "");
                        out.add(new Entry(t, ns + ":" + rel, rel.substring(rel.lastIndexOf('/') + 1), group, s, p));
                        continue;
                    }
                }
                if (p.endsWith(".geo.json") || (p.endsWith(".json") && (p.contains("/geo/") || (p.startsWith("models/") && s.kind == Source.Kind.PACK)))) {
                    byte[] b = s.read(p);
                    if (b == null || b.length > 20_000_000) continue;
                    String txt = new String(b, 0, Math.min(b.length, 4096), java.nio.charset.StandardCharsets.UTF_8);
                    if (!txt.contains("geometry")) continue;
                    String fn = p.substring(p.lastIndexOf('/') + 1).replaceFirst("\\.geo\\.json$|\\.json$", "");
                    List<BedrockReader.Geo> geos;
                    try { geos = BedrockReader.read(b, fn); } catch (Exception e) { continue; }
                    String group = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
                    for (BedrockReader.Geo g : geos) {
                        String nm = geos.size() == 1 ? fn : fn + " (" + g.id + ")";
                        Entry e = new Entry(Entry.Type.GEO, p + "#" + g.id, nm, group, s, p);
                        e.extra = g.id;
                        out.add(e);
                    }
                    continue;
                }
                if (p.endsWith(".bbmodel")) {
                    String fn = p.substring(p.lastIndexOf('/') + 1).replace(".bbmodel", "");
                    out.add(new Entry(Entry.Type.BBMODEL, p, fn, p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "", s, p));
                    continue;
                }
                if (p.endsWith(".jem") && p.contains("/optifine/")) {
                    String fn = p.substring(p.lastIndexOf('/') + 1).replace(".jem", "");
                    out.add(new Entry(Entry.Type.CEM, p, fn, p.substring(0, p.lastIndexOf('/')), s, p));
                }
            } catch (Exception e) {
                Log.debug("scan " + p + ": " + e);
            }
        }
        if (s.kind != Source.Kind.VANILLA) {
            for (LayerMethods.Found f : LayerMethods.scan(s.list(), s::read)) {
                String pkg = f.cls().contains("/") ? f.cls().substring(0, f.cls().lastIndexOf('/')).replace('/', '.') : "";
                Entry e = new Entry(Entry.Type.JAVA, f.cls() + "#" + f.method() + f.desc(), f.display(), pkg, s, f.cls() + ".class");
                e.extra = f;
                out.add(e);
            }
        } else if (vanillaEntities) {
            try {
                Map<String, EMesh> layers = VanillaLayers.get(s);
                for (String id : layers.keySet()) {
                    String model = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
                    String nm = id.substring(id.indexOf(':') + 1);
                    Entry e = new Entry(Entry.Type.ENTITY, id, nm, model.substring(0, model.indexOf(':')), s, null);
                    out.add(e);
                }
            } catch (Throwable ex) {
                Log.warn(I18n.t("Failed to extract entity models from ") + s.displayName + ": " + ex);
            }
        }
        out.sort(Comparator.comparing((Entry e) -> e.type.ordinal()).thenComparing(e -> e.group).thenComparing(e -> e.name));
        return out;
    }
}
