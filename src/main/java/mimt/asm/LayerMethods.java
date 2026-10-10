package mimt.asm;

import mimt.ir.EMesh;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;

public final class LayerMethods {
    public static final String LAYER = "net/minecraft/client/model/geom/builders/LayerDefinition";

    public record Found(String cls, String method, String desc) {
        public String simpleClass() {
            String n = cls.substring(cls.lastIndexOf('/') + 1);
            return n.replace('$', '.');
        }

        public String display() { return simpleClass() + "." + method + "()"; }
    }

    public static List<Found> scan(Collection<String> entries, Function<String, byte[]> reader) {
        List<Found> out = new ArrayList<>();
        for (String e : entries) {
            if (!e.endsWith(".class") || e.startsWith("META-INF/") || e.endsWith("module-info.class")) continue;
            byte[] b = reader.apply(e);
            if (b == null) continue;
            String cls = e.substring(0, e.length() - 6);
            if (Names.cls(cls).startsWith("net/minecraft/client/model/geom/")) continue;
            try {
                new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
                        if ((access & Opcodes.ACC_ABSTRACT) != 0 || name.startsWith("lambda$") && false) return null;
                        Type r = Type.getReturnType(desc);
                        if (r.getSort() == Type.OBJECT && Names.cls(r.getInternalName()).equals(LAYER)) {
                            if (!name.startsWith("lambda$")) out.add(new Found(cls, name, desc));
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (Exception ignored) { }
        }
        out.sort(Comparator.comparing(Found::display));
        return out;
    }

    public static EMesh run(List<Function<String, byte[]>> sources, Found f) {
        Interp ip = new Interp(sources);
        ClassNode cn = ip.node(f.cls());
        if (cn == null) return null;
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(f.method()) || !m.desc.equals(f.desc())) continue;
            Object r = ip.runEntry(cn, m);
            if (r instanceof V.Layer l && l.mesh.count() > 0) return l.mesh;
            mimt.util.Log.debug(f.display() + " -> " + r);
            return null;
        }
        return null;
    }
}
