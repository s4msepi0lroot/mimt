package mimt.vanilla;

import mimt.util.I18n;
import mimt.src.Source;
import mimt.util.Log;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import java.util.*;
import java.util.function.Function;

public final class VanillaClasses {
    private static final Map<Source, Function<String, byte[]>> CACHE = new WeakHashMap<>();

    public static synchronized Function<String, byte[]> provider(Source vanilla) {
        Function<String, byte[]> f = CACHE.get(vanilla);
        if (f != null) return f;
        if (vanilla.exists("net/minecraft/client/model/geom/LayerDefinitions.class")) f = vanilla::read;
        else {
            try {
                byte[] m = VanillaMeta.clientMappings(vanilla.mcVersion);
                f = m == null ? p -> null : new Obf(vanilla, ProguardMap.parse(m));
            } catch (Exception e) {
                Log.warn(I18n.t("Mappings ") + vanilla.mcVersion + I18n.t(" unavailable: ") + e.getMessage());
                f = p -> null;
            }
        }
        CACHE.put(vanilla, f);
        return f;
    }

    private static final class Obf extends Remapper implements Function<String, byte[]> {
        final Source jar;
        final Map<String, String> clsObfToNamed = new HashMap<>(), clsNamedToObf = new HashMap<>();
        final Map<String, Map<String, String>> methods = new HashMap<>();
        final Map<String, Map<String, String>> fields = new HashMap<>();
        final Map<String, String[]> supers = new HashMap<>();
        final Map<String, byte[]> out = new HashMap<>();

        Obf(Source jar, ProguardMap map) {
            super(Opcodes.ASM9);
            this.jar = jar;
            for (ProguardMap.Cls c : map.byNamed.values()) {
                String n = c.named.replace('.', '/'), o = c.obf.replace('.', '/');
                clsObfToNamed.put(o, n);
                clsNamedToObf.put(n, o);
            }
            for (ProguardMap.Cls c : map.byNamed.values()) {
                String o = c.obf.replace('.', '/');
                Map<String, String> ms = new HashMap<>();
                for (String[] r : c.rawMethods) {
                    StringBuilder d = new StringBuilder("(");
                    if (!r[2].isEmpty()) for (String a : r[2].split(",")) d.append(desc(a));
                    d.append(')').append(desc(r[1]));
                    ms.put(r[3] + d, r[0]);
                }
                methods.put(o, ms);
                Map<String, String> fs = new HashMap<>();
                for (var e : c.fields.entrySet()) fs.put(e.getValue(), e.getKey());
                fields.put(o, fs);
            }
        }

        private String desc(String t) {
            StringBuilder dims = new StringBuilder();
            while (t.endsWith("[]")) { t = t.substring(0, t.length() - 2); dims.append('['); }
            String p = switch (t) {
                case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
                case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
                default -> {
                    String n = t.replace('.', '/');
                    yield "L" + clsNamedToObf.getOrDefault(n, n) + ";";
                }
            };
            return dims + p;
        }

        private String[] supers(String obf) {
            String[] s = supers.get(obf);
            if (s != null) return s;
            byte[] b = jar.read(obf + ".class");
            if (b == null) s = new String[0];
            else {
                ClassReader cr = new ClassReader(b);
                List<String> l = new ArrayList<>();
                if (cr.getSuperName() != null) l.add(cr.getSuperName());
                Collections.addAll(l, cr.getInterfaces());
                s = l.toArray(new String[0]);
            }
            supers.put(obf, s);
            return s;
        }

        @Override public String map(String internalName) { return clsObfToNamed.getOrDefault(internalName, internalName); }

        @Override public String mapMethodName(String owner, String name, String descriptor) {
            if (name.startsWith("<")) return name;
            String r = findMethod(owner, name + descriptor, new HashSet<>());
            return r != null ? r : name;
        }

        private String findMethod(String owner, String key, Set<String> seen) {
            if (!seen.add(owner)) return null;
            Map<String, String> m = methods.get(owner);
            if (m != null) {
                String r = m.get(key);
                if (r != null) return r;
            }
            for (String s : supers(owner)) {
                String r = findMethod(s, key, seen);
                if (r != null) return r;
            }
            return null;
        }

        @Override public String mapFieldName(String owner, String name, String descriptor) {
            String r = findField(owner, name, new HashSet<>());
            return r != null ? r : name;
        }

        private String findField(String owner, String name, Set<String> seen) {
            if (!seen.add(owner)) return null;
            Map<String, String> m = fields.get(owner);
            if (m != null && m.containsKey(name)) return m.get(name);
            for (String s : supers(owner)) {
                String r = findField(s, name, seen);
                if (r != null) return r;
            }
            return null;
        }

        @Override public synchronized byte[] apply(String path) {
            if (!path.endsWith(".class")) return null;
            String named = path.substring(0, path.length() - 6);
            if (out.containsKey(named)) return out.get(named);
            String obf = clsNamedToObf.get(named);
            byte[] r = null;
            if (obf != null) {
                byte[] b = jar.read(obf + ".class");
                if (b != null) {
                    try {
                        ClassReader cr = new ClassReader(b);
                        ClassWriter cw = new ClassWriter(0);
                        cr.accept(new ClassRemapper(cw, this), ClassReader.SKIP_FRAMES);
                        r = cw.toByteArray();
                    } catch (Exception e) {
                        Log.debug("remap " + named + ": " + e);
                    }
                }
            }
            out.put(named, r);
            return r;
        }
    }
}
