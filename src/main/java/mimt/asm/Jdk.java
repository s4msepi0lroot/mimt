package mimt.asm;

import org.objectweb.asm.Type;
import java.lang.reflect.*;
import java.util.*;

final class Jdk {
    private static final Set<String> STATIC_OK = Set.of(
            "java/lang/Math", "java/lang/StrictMath", "java/lang/Float", "java/lang/Double", "java/lang/Integer",
            "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Boolean", "java/lang/Character",
            "java/lang/String", "java/util/List", "java/util/Set", "java/util/Map", "java/util/Arrays",
            "java/util/Collections", "java/util/Objects", "java/lang/Object");
    private static final Set<String> NEW_OK = Set.of(
            "java/lang/StringBuilder", "java/lang/StringBuffer", "java/util/ArrayList", "java/util/LinkedList",
            "java/util/HashMap", "java/util/LinkedHashMap", "java/util/TreeMap", "java/util/HashSet",
            "java/util/LinkedHashSet", "java/util/TreeSet", "java/util/ArrayDeque", "java/lang/Object");

    private static boolean allowedInstance(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.startsWith("java.lang.") || n.startsWith("java.util.");
    }

    private static Class<?> cls(Type t) throws ClassNotFoundException {
        switch (t.getSort()) {
            case Type.BOOLEAN: return boolean.class;
            case Type.CHAR: return char.class;
            case Type.BYTE: return byte.class;
            case Type.SHORT: return short.class;
            case Type.INT: return int.class;
            case Type.LONG: return long.class;
            case Type.FLOAT: return float.class;
            case Type.DOUBLE: return double.class;
            case Type.ARRAY: return Class.forName(t.getDescriptor().replace('/', '.'), false, Jdk.class.getClassLoader());
            default:
                String n = t.getClassName();
                if (!(n.startsWith("java.lang.") || n.startsWith("java.util."))) return Object.class;
                return Class.forName(n, false, Jdk.class.getClassLoader());
        }
    }

    private static Object toJava(Object v, Type t) {
        if (v == V.UNKNOWN) throw new Interp.Abort("unknown");
        switch (t.getSort()) {
            case Type.BOOLEAN: return v instanceof Integer i ? i != 0 : v;
            case Type.CHAR: return v instanceof Integer i ? (char) i.intValue() : v;
            case Type.BYTE: return v instanceof Integer i ? (byte) i.intValue() : v;
            case Type.SHORT: return v instanceof Integer i ? (short) i.intValue() : v;
            case Type.LONG: return v instanceof Number n ? n.longValue() : v;
            case Type.FLOAT: return v instanceof Number n ? n.floatValue() : v;
            case Type.DOUBLE: return v instanceof Number n ? n.doubleValue() : v;
            case Type.INT: return v instanceof Number n ? n.intValue() : v;
            case Type.ARRAY:
                if (v instanceof V.Arr a) {
                    Type el = t.getElementType();
                    if (t.getDimensions() == 1 && el.getSort() == Type.OBJECT) return a.a.clone();
                    if (t.getDimensions() == 1 && el.getSort() == Type.FLOAT) {
                        float[] r = new float[a.a.length];
                        for (int i = 0; i < r.length; i++) r[i] = ((Number) a.a[i]).floatValue();
                        return r;
                    }
                    if (t.getDimensions() == 1 && el.getSort() == Type.INT) {
                        int[] r = new int[a.a.length];
                        for (int i = 0; i < r.length; i++) r[i] = ((Number) a.a[i]).intValue();
                        return r;
                    }
                }
                throw new Interp.Abort("array arg");
            default:
                if (v instanceof V.Lambda || v instanceof Interp.Uninit) throw new Interp.Abort("lambda to jdk");
                return v;
        }
    }

    private static Object fromJava(Object r) {
        if (r instanceof Boolean b) return b ? 1 : 0;
        if (r instanceof Character c) return (int) c;
        if (r instanceof Byte b) return (int) b;
        if (r instanceof Short s) return (int) s;
        if (r instanceof Object[] arr) {
            V.Arr a = new V.Arr(arr.length, "Ljava/lang/Object;", null);
            System.arraycopy(arr, 0, a.a, 0, arr.length);
            return a;
        }
        if (r != null && r.getClass().getName().startsWith("java.util.stream")) return V.UNKNOWN;
        return r;
    }

    static Object call(Interp ip, int op, String owner, String name, String desc, Object recv, Object[] args) {
        try {
            Type[] at = Type.getArgumentTypes(desc);
            Class<?>[] pt = new Class<?>[at.length];
            Object[] jv = new Object[at.length];
            for (int i = 0; i < at.length; i++) { pt[i] = cls(at[i]); jv[i] = toJava(args[i], at[i]); }
            Class<?> oc = Class.forName(owner.replace('/', '.'), false, Jdk.class.getClassLoader());
            if (op == org.objectweb.asm.Opcodes.INVOKESTATIC) {
                if (!STATIC_OK.contains(owner)) return V.UNKNOWN;
                Method m = oc.getMethod(name, pt);
                return fromJava(m.invoke(null, jv));
            }
            if (!allowedInstance(recv)) return V.UNKNOWN;
            if (name.equals("forEach") || name.equals("stream") || name.equals("computeIfAbsent") || name.equals("replaceAll")
                    || name.equals("removeIf") || name.equals("sort") || name.equals("wait") || name.equals("notify")) return V.UNKNOWN;
            Method m = oc.getMethod(name, pt);
            return fromJava(m.invoke(recv, jv));
        } catch (Interp.Abort a) {
            return V.UNKNOWN;
        } catch (InvocationTargetException e) {
            throw new Interp.Abort("jdk exception " + e.getCause());
        } catch (Throwable e) {
            return V.UNKNOWN;
        }
    }

    static Object construct(String owner, String desc, Object[] args) {
        if (!NEW_OK.contains(owner)) return V.UNKNOWN;
        try {
            Type[] at = Type.getArgumentTypes(desc);
            Class<?>[] pt = new Class<?>[at.length];
            Object[] jv = new Object[at.length];
            for (int i = 0; i < at.length; i++) { pt[i] = cls(at[i]); jv[i] = toJava(args[i], at[i]); }
            Class<?> oc = Class.forName(owner.replace('/', '.'));
            return oc.getConstructor(pt).newInstance(jv);
        } catch (Throwable e) {
            return V.UNKNOWN;
        }
    }
}
