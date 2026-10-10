package mimt.asm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class Names {
    private static final Map<String, String> CLS = new HashMap<>();
    private static final Map<String, String> MEM = new HashMap<>();
    private static final Map<String, String> REV_CLS = new HashMap<>();
    private static boolean loaded;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = Names.class.getResourceAsStream("/mimt/names.tsv")) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                if (p[0].equals("C")) { CLS.put(p[1], p[2]); REV_CLS.putIfAbsent(p[2], p[1]); }
                else MEM.put(p[1], p[2]);
            }
        } catch (IOException ignored) { }
    }

    private static final Map<String, String> EXTRA = new HashMap<>();

    public static String cls(String internal) {
        if (internal == null) return null;
        load();
        String r = CLS.get(internal);
        if (r != null) return r;
        r = EXTRA.get(internal);
        return r != null ? r : internal;
    }

    private static final Pattern MEMBER = Pattern.compile("method_\\d+|field_\\d+|m_\\d+_|f_\\d+_|comp_\\d+");

    public static String mem(String name) {
        if (name == null) return null;
        load();
        if (!MEMBER.matcher(name).matches()) return name;
        return MEM.getOrDefault(name, name);
    }

    private static final Pattern OBJ = Pattern.compile("L([^;]+);");

    public static String desc(String desc) {
        Matcher m = OBJ.matcher(desc);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("L" + cls(m.group(1)) + ";"));
        m.appendTail(sb);
        return sb.toString();
    }

    public static String simple(String internal) {
        String n = cls(internal);
        int i = n.lastIndexOf('/');
        return i < 0 ? n : n.substring(i + 1);
    }
}
