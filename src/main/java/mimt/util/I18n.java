package mimt.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

public final class I18n {
    public enum Lang {
        EN("English"), RU("Русский");

        public final String title;

        Lang(String t) { title = t; }

        @Override public String toString() { return title; }
    }

    private static volatile Lang lang = Lang.EN;
    private static Map<String, String> ru;

    private I18n() { }

    public static Lang get() { return lang; }

    public static void set(Lang l) { lang = l == null ? Lang.EN : l; }

    public static Lang parse(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("ru")) return Lang.RU;
        if (s.startsWith("en")) return Lang.EN;
        return null;
    }

    public static Preferences prefs() { return Preferences.userRoot().node("mimodel-tool"); }

    public static Lang saved() {
        Lang l = parse(prefs().get("lang", "en"));
        return l == null ? Lang.EN : l;
    }

    public static void save(Lang l) { prefs().put("lang", l.name().toLowerCase(Locale.ROOT)); }

    public static String t(String en) {
        if (lang == Lang.EN || en == null) return en;
        String r = table().get(en);
        return r == null ? en : r;
    }

    public static String f(String en, Object... args) { return String.format(Locale.ROOT, t(en), args); }

    private static synchronized Map<String, String> table() {
        if (ru != null) return ru;
        Map<String, String> m = new HashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream("/mimt/lang/ru.tsv")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    m.put(unescape(line.substring(0, tab)), unescape(line.substring(tab + 1)));
                }
            }
        } catch (Exception ignored) {
        }
        ru = m;
        return m;
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t");
    }
}
