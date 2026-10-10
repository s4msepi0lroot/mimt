package mimt.scan;

import mimt.src.Source;
import mimt.src.Sources;
import mimt.util.Img;
import java.awt.image.BufferedImage;
import java.util.*;

public final class TextureGuess {
    public record Candidate(Source source, String path, double score) {
        @Override public String toString() { return path; }
    }

    private static final java.util.regex.Pattern BAD_DIR = java.util.regex.Pattern.compile(
            "/textures/(gui|map|item|items|block|blocks|particle|font|painting|environment|colormap|mob_effect|effect|misc|trims)/");

    public static String camelToSnake(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    public static String javaName(String simpleClass, String method) {
        String n = simpleClass;
        int d = n.lastIndexOf('.');
        if (d >= 0) n = n.substring(d + 1);
        for (String suf : new String[]{"Model", "Entity", "Renderer"})
            if (n.endsWith(suf) && n.length() > suf.length()) n = n.substring(0, n.length() - suf.length());
        String r = camelToSnake(n);
        String m = method.replaceFirst("^(create|get)", "").replaceFirst("(Layer|LayerDefinition|Model|Mesh|TexturedModelData|ModelData)$", "");
        m = camelToSnake(m);
        if (!m.isEmpty() && !m.equals("body") && !m.equals("main") && !m.equals("textured") && !m.equals("base")) r = r + "#" + m;
        return r;
    }

    public static List<Candidate> rank(Sources src, Source prefer, String name, int texW, int texH, int max) {
        String base = name, layer = "";
        int h = name.indexOf('#');
        if (h >= 0) { base = name.substring(0, h); layer = name.substring(h + 1); }
        int c = base.indexOf(':');
        if (c >= 0) base = base.substring(c + 1);
        base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>(Arrays.asList(base.split("[_\\-.]")));
        tokens.removeIf(t -> t.length() < 3);
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Source s : src.ordered()) {
            for (String p : s.list()) {
                if (!p.endsWith(".png") || !(p.startsWith("assets/") && p.contains("/textures/") || p.startsWith("textures/"))) continue;
                boolean entityLike = p.startsWith("textures/entity/") || p.contains("/textures/entity/") || p.contains("/textures/entities/") || p.contains("/textures/models/")
                        || p.contains("/textures/mob") || p.contains("/textures/geo/");
                if (!entityLike && s != prefer) continue;
                if (BAD_DIR.matcher(p).find()) continue;
                if (!seen.add(p)) continue;
                String fn = p.substring(p.lastIndexOf('/') + 1, p.length() - 4).toLowerCase(Locale.ROOT);
                String dir = p.substring(0, p.lastIndexOf('/')).toLowerCase(Locale.ROOT);
                double sc = 0;
                if (fn.equals(base)) sc += 100;
                else if (fn.startsWith(base + "_") || fn.endsWith("_" + base)) sc += 60;
                else if (fn.contains(base)) sc += 35;
                if (dir.endsWith("/" + base) || dir.contains("/" + base + "/")) sc += 45;
                for (String t : tokens) {
                    if (fn.contains(t)) sc += 10;
                    if (dir.contains(t)) sc += 4;
                }
                if (!layer.isEmpty() && !layer.equals("main")) {
                    if (fn.contains(layer)) sc += 40;
                    else sc -= 10;
                } else {
                    for (String bad : new String[]{"_eyes", "_overlay", "_glow", "emissive", "_saddle", "_armor", "_collar", "_harness", "_spots"})
                        if (fn.endsWith(bad) || fn.contains(bad + "_")) sc -= 15;
                }
                if (!entityLike) sc -= 20;
                if (tokens.size() > 1 && tokens.stream().allMatch(fn::contains)) sc += 30;
                if (fn.contains("baby") && !base.contains("baby") && !layer.contains("baby")) sc -= 20;
                if (base.contains("baby") && !fn.contains("baby")) sc -= 5;
                for (String good : new String[]{"temperate", "default", "normal", "regular", "base", "steve"})
                    if (fn.contains(good)) sc += 12;
                if (base.equals("player") && fn.equals("steve") && dir.endsWith("/wide")) sc += 150;
                if (base.equals("player") && p.contains("/player/") && !fn.equals("steve")) sc -= 5;
                if (sc <= 0) continue;
                if (s == prefer) sc += 20;
                out.add(new Candidate(s, p, sc));
            }
        }
        out.sort((a, b) -> Double.compare(b.score, a.score));
        List<Candidate> top = new ArrayList<>(out.subList(0, Math.min(out.size(), Math.max(max * 2, 20))));
        List<Candidate> fixed = new ArrayList<>();
        for (Candidate cd : top) {
            double sc = cd.score;
            if (texW > 0 && texH > 0) {
                BufferedImage img = Img.read(cd.source.read(cd.path));
                if (img != null) {
                    double a1 = (double) img.getWidth() / img.getHeight(), a2 = (double) texW / texH;
                    if (Math.abs(a1 - a2) < 1e-3) sc += 30;
                    else sc -= 40;
                }
            }
            fixed.add(new Candidate(cd.source, cd.path, sc));
        }
        fixed.sort((a, b) -> Double.compare(b.score, a.score));
        return new ArrayList<>(fixed.subList(0, Math.min(max, fixed.size())));
    }
}
