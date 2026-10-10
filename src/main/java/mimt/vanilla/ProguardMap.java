package mimt.vanilla;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class ProguardMap {
    public static final class Cls {
        public final String named, obf;
        public final Map<String, String> fields = new HashMap<>();
        public final Map<String, String> methods = new HashMap<>();
        public final List<String[]> rawMethods = new ArrayList<>();

        Cls(String named, String obf) { this.named = named; this.obf = obf; }
    }

    public final Map<String, Cls> byNamed = new HashMap<>();

    private static final Pattern CLS = Pattern.compile("^(\\S+) -> (\\S+):$");
    private static final Pattern MEM = Pattern.compile("^\\s+(?:\\d+:\\d+:)?(\\S+) ([^\\s(]+)(\\(([^)]*)\\))?(?::\\d+:\\d+)? -> (\\S+)$");

    public static ProguardMap parse(byte[] data) throws IOException {
        ProguardMap m = new ProguardMap();
        BufferedReader r = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        String line;
        Cls cur = null;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("#") || line.isEmpty()) continue;
            if (!line.startsWith(" ")) {
                Matcher c = CLS.matcher(line);
                if (c.matches()) {
                    cur = new Cls(c.group(1), c.group(2));
                    m.byNamed.put(cur.named, cur);
                }
                continue;
            }
            if (cur == null) continue;
            Matcher mm = MEM.matcher(line);
            if (!mm.matches()) continue;
            if (mm.group(3) == null) cur.fields.put(mm.group(2), mm.group(5));
            else {
                cur.rawMethods.add(new String[]{mm.group(2), mm.group(1), mm.group(4), mm.group(5)});
                cur.methods.put(mm.group(2) + "(" + mm.group(4) + ")", mm.group(5));
                cur.methods.putIfAbsent(mm.group(2), mm.group(5));
            }
        }
        return m;
    }

    public String cls(String named) {
        Cls c = byNamed.get(named);
        return c == null ? named : c.obf;
    }

    public String field(String cls, String f) {
        Cls c = byNamed.get(cls);
        if (c == null) return f;
        return c.fields.getOrDefault(f, f);
    }

    public String method(String cls, String sig) {
        Cls c = byNamed.get(cls);
        if (c == null) return sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig;
        String o = c.methods.get(sig);
        if (o == null && sig.contains("(")) o = c.methods.get(sig.substring(0, sig.indexOf('(')));
        return o == null ? (sig.contains("(") ? sig.substring(0, sig.indexOf('(')) : sig) : o;
    }
}
