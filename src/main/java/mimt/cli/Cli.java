package mimt.cli;

import mimt.mi.MiModel;
import mimt.mi.MiWriter;
import mimt.scan.*;
import mimt.util.*;
import mimt.vanilla.VanillaMeta;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

public final class Cli {
    static final String HELP = String.join("\n",
            "mimodel-tool — export Minecraft models to the Mine-imator format (.mimodel)",
            "",
            "Usage:",
            "  mimodel-tool                                 start the graphical interface",
            "  mimodel-tool list <sources...> [--type T] [--filter TEXT]",
            "  mimodel-tool export <sources...> --model ID [--texture FILE.png] --out FOLDER",
            "  mimodel-tool export-all <sources...> --out FOLDER [--type T] [--filter TEXT]",
            "  mimodel-tool textures <sources...> --model ID   texture candidates for a model",
            "  mimodel-tool versions                        list Minecraft versions",
            "",
            "Sources: mod jar, Minecraft client jar, resource pack (.zip or folder), folder with .bbmodel/.geo.json/.jem files.",
            "Types (--type): block, item, json, entity, java, geo, bbmodel, cem (comma separated).",
            "",
            "Options:",
            "  --mc VERSION     Minecraft version for missing vanilla resources (default: from the mod or the latest)",
            "  --offline        do not download anything",
            "  --no-tint        do not tint grass/foliage",
            "  --no-entities    do not extract entity models from the Minecraft client jar",
            "  --lang en|ru     interface language (default: en)",
            "  -v               verbose log");

    public static int run(String[] args) {
        List<String> rest = new ArrayList<>();
        I18n.Lang lang = I18n.parse(System.getenv("MIMT_LANG"));
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lang") && i + 1 < args.length) lang = I18n.parse(args[++i]);
            else if (args[i].startsWith("--lang=")) lang = I18n.parse(args[i].substring(7));
            else rest.add(args[i]);
        }
        if (lang == null) lang = I18n.saved();
        I18n.set(lang);
        args = rest.toArray(new String[0]);
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help") || args[0].equals("help")) {
            System.out.println(I18n.t(HELP));
            return 0;
        }
        String cmd = args[0];
        List<String> srcs = new ArrayList<>();
        Map<String, String> opt = new HashMap<>();
        Set<String> flags = new HashSet<>();
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--type": case "--filter": case "--model": case "--texture": case "--out": case "--mc":
                    if (i + 1 >= args.length) { System.err.println(I18n.t("Missing value for ") + a); return 2; }
                    opt.put(a.substring(2), args[++i]);
                    break;
                case "--offline": case "--no-tint": case "--no-entities": case "-v":
                    flags.add(a);
                    break;
                default:
                    if (a.startsWith("--")) { System.err.println(I18n.t("Unknown option: ") + a); return 2; }
                    srcs.add(a);
            }
        }
        if (flags.contains("-v")) Log.verbose = true;
        Http.offline = flags.contains("--offline");
        Workspace ws = new Workspace();
        ws.tint = !flags.contains("--no-tint");
        ws.vanillaEntities = !flags.contains("--no-entities");
        ws.autoVanilla = !Http.offline;
        ws.mcVersion = opt.get("mc");
        if (cmd.equals("versions")) {
            for (String v : VanillaMeta.releases()) System.out.println(v);
            return 0;
        }
        if (srcs.isEmpty()) { System.err.println(I18n.t("No sources given. See mimodel-tool --help")); return 2; }
        for (String s : srcs) {
            Path p = Paths.get(s);
            if (!Files.exists(p)) { System.err.println(I18n.t("File not found: ") + s); return 2; }
            try {
                ws.addPath(p);
            } catch (Exception e) {
                System.err.println(I18n.t("Failed to open ") + s + ": " + e);
                return 1;
            }
        }
        List<Entry> list = filter(ws.entries, opt.get("type"), opt.get("filter"));
        try {
            switch (cmd) {
                case "list":
                    for (Entry e : list) System.out.println(e.fullId() + "\t" + e.name);
                    System.out.println(I18n.t("Total: ") + list.size());
                    return 0;
                case "textures": {
                    Entry e = need(ws, opt);
                    if (e == null) return 2;
                    for (TextureGuess.Candidate c : ws.textureCandidates(e))
                        System.out.println(String.format(Locale.ROOT, "%6.0f  %s  [%s]", c.score(), c.path(), c.source().displayName));
                    return 0;
                }
                case "export": {
                    Entry e = need(ws, opt);
                    if (e == null) return 2;
                    Path out = Paths.get(opt.getOrDefault("out", "."));
                    BufferedImage tex = null;
                    if (opt.containsKey("texture")) {
                        tex = Img.read(Files.readAllBytes(Paths.get(opt.get("texture"))));
                        if (tex == null) { System.err.println(I18n.t("Failed to read the texture")); return 2; }
                    }
                    MiModel m = ws.export(e, tex);
                    Path f = MiWriter.write(m, out.resolve(m.name), m.name);
                    for (String n : m.notes) System.out.println("  · " + n);
                    System.out.println(I18n.f("Saved: %s (shapes: %d)", f, m.countShapes()));
                    return 0;
                }
                case "export-all": {
                    Path out = Paths.get(opt.getOrDefault("out", "mimodels"));
                    int ok = 0, fail = 0;
                    for (Entry e : list) {
                        try {
                            MiModel m = ws.export(e, null);
                            Path dir = out.resolve(e.type.key()).resolve(Workspace.safe(e.group.replace('/', '.'))).resolve(m.name);
                            MiWriter.write(m, dir, m.name);
                            ok++;
                            System.out.println("[ok] " + e.fullId());
                        } catch (Exception ex) {
                            fail++;
                            System.out.println("[--] " + e.fullId() + ": " + ex.getMessage());
                        }
                    }
                    System.out.println(I18n.f("Done: exported %d, skipped %d → %s", ok, fail, out.toAbsolutePath()));
                    return fail > 0 && ok == 0 ? 1 : 0;
                }
                default:
                    System.err.println(I18n.f("Unknown command: %s. See mimodel-tool --help", cmd));
                    return 2;
            }
        } catch (Exception e) {
            System.err.println(I18n.t("Error: ") + e.getMessage());
            if (Log.verbose) e.printStackTrace();
            return 1;
        }
    }

    private static Entry need(Workspace ws, Map<String, String> opt) {
        String id = opt.get("model");
        if (id == null) { System.err.println(I18n.t("Specify --model ID (see the list command)")); return null; }
        Entry e = ws.find(id);
        if (e == null) {
            List<Entry> c = filter(ws.entries, null, id);
            if (c.size() == 1) return c.get(0);
            System.err.println(c.isEmpty() ? I18n.t("Model not found: ") + id : I18n.t("Ambiguous ID, candidates:"));
            for (int i = 0; i < Math.min(20, c.size()); i++) System.err.println("  " + c.get(i).fullId());
            return null;
        }
        return e;
    }

    static List<Entry> filter(List<Entry> all, String types, String text) {
        Set<String> ts = new HashSet<>();
        if (types != null) for (String t : types.split(",")) ts.add(t.trim().toLowerCase(Locale.ROOT));
        String f = text == null ? null : text.toLowerCase(Locale.ROOT);
        List<Entry> r = new ArrayList<>();
        for (Entry e : all) {
            if (!ts.isEmpty() && !ts.contains(e.type.key())) continue;
            if (f != null && !e.fullId().toLowerCase(Locale.ROOT).contains(f) && !e.name.toLowerCase(Locale.ROOT).contains(f)) continue;
            r.add(e);
        }
        return r;
    }
}
