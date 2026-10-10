package mimt.asm;

import mimt.util.Log;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

public final class Interp {
    static final class Abort extends RuntimeException {
        Abort(String m) { super(m, null, false, false); }
    }

    static final class Uninit {
        final String cls;
        Uninit(String c) { cls = c; }
    }

    static final class ClassLit {
        final String name;
        ClassLit(String n) { name = n; }
    }

    private final List<Function<String, byte[]>> sources;
    private final Map<String, ClassNode> classes = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Map<String, Map<String, Object>> statics = new HashMap<>();
    private final Set<String> clinitDone = new HashSet<>();
    long steps;
    long maxSteps = 4_000_000;
    int depth;

    public Interp(List<Function<String, byte[]>> sources) { this.sources = sources; }

    public ClassNode node(String internal) {
        if (internal == null || internal.startsWith("java/") || missing.contains(internal)) return null;
        ClassNode cn = classes.get(internal);
        if (cn != null) return cn;
        for (Function<String, byte[]> s : sources) {
            byte[] b = s.apply(internal + ".class");
            if (b == null) continue;
            try {
                cn = new ClassNode();
                new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES);
                classes.put(internal, cn);
                return cn;
            } catch (Exception e) {
                break;
            }
        }
        missing.add(internal);
        return null;
    }

    public Object runEntry(ClassNode cn, MethodNode mn) {
        steps = 0;
        depth = 0;
        Type[] at = Type.getArgumentTypes(mn.desc);
        boolean st = (mn.access & ACC_STATIC) != 0;
        Object[] args = new Object[at.length + (st ? 0 : 1)];
        int i = 0;
        if (!st) args[i++] = new V.Obj(cn.name);
        int slot = st ? 0 : 1;
        for (Type t : at) {
            Object v = defaultArg(t);
            String pn = paramName(mn, slot);
            if (pn != null && pn.toLowerCase(Locale.ROOT).contains("scale")) {
                if (t.getSort() == Type.FLOAT) v = 1f;
                else if (t.getSort() == Type.DOUBLE) v = 1d;
            }
            args[i++] = v;
            slot += t.getSize();
        }
        try {
            return exec(cn, mn, args);
        } catch (Abort a) {
            Log.debug(cn.name + "." + mn.name + ": " + a.getMessage());
            return V.UNKNOWN;
        }
    }

    private static String paramName(MethodNode mn, int slot) {
        if (mn.localVariables == null) return null;
        for (LocalVariableNode lv : mn.localVariables) if (lv.index == slot) return lv.name;
        return null;
    }

    private Object defaultArg(Type t) {
        switch (t.getSort()) {
            case Type.BOOLEAN: case Type.INT: case Type.BYTE: case Type.SHORT: case Type.CHAR: return 0;
            case Type.LONG: return 0L;
            case Type.FLOAT: return 0f;
            case Type.DOUBLE: return 0d;
            case Type.OBJECT:
                String n = Names.cls(t.getInternalName());
                if (n.endsWith("/CubeDeformation")) return new V.Deform(0, 0, 0);
                if (n.endsWith("/PartPose")) return new V.Pose();
                if (n.endsWith("/MeshDefinition")) return new V.Mesh();
                return V.UNKNOWN;
            default: return V.UNKNOWN;
        }
    }

    static final class Found {
        final ClassNode c;
        final MethodNode m;
        Found(ClassNode c, MethodNode m) { this.c = c; this.m = m; }
    }

    Found find(String cls, String name, String desc) {
        Deque<String> todo = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        todo.add(cls);
        while (!todo.isEmpty()) {
            String c = todo.poll();
            if (c == null || !seen.add(c)) continue;
            ClassNode cn = node(c);
            if (cn == null) continue;
            for (MethodNode m : cn.methods)
                if (m.name.equals(name) && m.desc.equals(desc) && (m.access & ACC_ABSTRACT) == 0 && m.instructions.size() > 0)
                    return new Found(cn, m);
            if (cn.superName != null) todo.add(cn.superName);
            if (cn.interfaces != null) todo.addAll(cn.interfaces);
        }
        return null;
    }

    Object getStatic(String owner, String name, String desc) {
        Object b = Builtins.staticField(Names.cls(owner), Names.mem(name));
        if (b != Builtins.NO) return b;
        String decl = declaringClass(owner, name);
        if (decl == null) {
            String mo = Names.cls(owner), mn = Names.mem(name);
            if (!mo.equals(owner) || !mn.equals(name)) {
                decl = declaringClass(mo, mn);
                name = mn;
            }
        }
        if (decl == null) return V.UNKNOWN;
        clinit(decl);
        Map<String, Object> m = statics.get(decl);
        if (m != null && m.containsKey(name)) return m.get(name);
        ClassNode cn = node(decl);
        if (cn != null) for (FieldNode f : cn.fields) if (f.name.equals(name) && f.value != null) return f.value instanceof Boolean bo ? (bo ? 1 : 0) : f.value;
        return V.defaultFor(desc.charAt(0)) != null ? V.defaultFor(desc.charAt(0)) : V.UNKNOWN;
    }

    void putStatic(String owner, String name, Object v) {
        String decl = declaringClass(owner, name);
        if (decl == null) return;
        statics.computeIfAbsent(decl, k -> new HashMap<>()).put(name, v);
    }

    private String declaringClass(String owner, String name) {
        String c = owner;
        for (int i = 0; i < 32 && c != null; i++) {
            ClassNode cn = node(c);
            if (cn == null) return null;
            for (FieldNode f : cn.fields) if (f.name.equals(name)) return c;
            if (cn.interfaces != null)
                for (String itf : cn.interfaces) {
                    ClassNode in = node(itf);
                    if (in != null) for (FieldNode f : in.fields) if (f.name.equals(name)) return itf;
                }
            c = cn.superName;
        }
        return null;
    }

    private void clinit(String cls) {
        if (!clinitDone.add(cls)) return;
        ClassNode cn = node(cls);
        if (cn == null) return;
        Map<String, Object> m = statics.computeIfAbsent(cls, k -> new HashMap<>());
        for (FieldNode f : cn.fields)
            if ((f.access & ACC_STATIC) != 0) {
                Object v = f.value != null ? (f.value instanceof Boolean bo ? (bo ? 1 : 0) : f.value) : V.defaultFor(f.desc.charAt(0));
                m.putIfAbsent(f.name, v);
            }
        for (MethodNode mn : cn.methods) {
            if (!mn.name.equals("<clinit>")) continue;
            long saved = steps;
            long savedMax = maxSteps;
            maxSteps = Math.min(maxSteps, steps + 400_000);
            try {
                exec(cn, mn, new Object[0]);
            } catch (Abort a) {
                Log.debug("clinit " + cls + ": " + a.getMessage());
            } finally {
                maxSteps = savedMax;
                steps = Math.max(saved, steps);
            }
        }
    }

    Object invoke(int op, String owner, String name, String desc, Object recv, Object[] args) {
        String mo = Names.cls(owner), mn = Names.mem(name), md = Names.desc(desc);
        Object r = Builtins.call(this, mo, mn, md, recv, args, op == INVOKESTATIC);
        if (r != Builtins.NO) return r;
        if (recv instanceof V.Lambda l && op != INVOKESPECIAL) return callLambda(l, args);
        if (recv == V.UNKNOWN && op != INVOKESTATIC) return V.UNKNOWN;
        Found f = null;
        boolean translated = !mo.equals(owner) || !mn.equals(name) || !md.equals(desc);
        if (op == INVOKESTATIC || op == INVOKESPECIAL) {
            f = find(owner, name, desc);
            if (f == null && translated) f = find(mo, mn, md);
        } else {
            if (recv instanceof V.Obj o) {
                f = find(o.cls, name, desc);
                if (f == null && translated) f = find(o.cls, mn, md);
            }
            if (f == null && !isJdk(recv)) {
                f = find(owner, name, desc);
                if (f == null && translated) f = find(mo, mn, md);
            }
        }
        if (f != null) {
            Object[] full;
            if (op == INVOKESTATIC) full = args;
            else {
                full = new Object[args.length + 1];
                full[0] = recv;
                System.arraycopy(args, 0, full, 1, args.length);
            }
            if (op == INVOKESTATIC) clinit(f.c.name);
            return exec(f.c, f.m, full);
        }
        if (owner.startsWith("java/") || isJdk(recv)) return Jdk.call(this, op, owner, name, desc, recv, args);
        return V.UNKNOWN;
    }

    static boolean isJdk(Object o) {
        return o != null && o.getClass().getName().startsWith("java.");
    }

    Object callLambda(V.Lambda l, Object[] args) {
        Object[] all = new Object[l.captured.length + args.length];
        System.arraycopy(l.captured, 0, all, 0, l.captured.length);
        System.arraycopy(args, 0, all, l.captured.length, args.length);
        switch (l.implTag) {
            case H_INVOKESTATIC:
                return invoke(INVOKESTATIC, l.implOwner, l.implName, l.implDesc, null, all);
            case H_NEWINVOKESPECIAL: {
                Object o = construct(l.implOwner, l.implDesc, all);
                return o;
            }
            default: {
                if (all.length == 0) return V.UNKNOWN;
                Object recv = all[0];
                Object[] rest = Arrays.copyOfRange(all, 1, all.length);
                return invoke(l.implTag == H_INVOKESPECIAL ? INVOKESPECIAL : INVOKEVIRTUAL, l.implOwner, l.implName, l.implDesc, recv, rest);
            }
        }
    }

    Object construct(String owner, String desc, Object[] args) {
        String mo = Names.cls(owner);
        Object b = Builtins.construct(mo, Names.desc(desc), args);
        if (b != Builtins.NO) return b;
        ClassNode cn = node(owner);
        if (cn != null) {
            V.Obj o = new V.Obj(owner);
            Found f = find(owner, "<init>", desc);
            if (f != null && f.c == cn) {
                Object[] full = new Object[args.length + 1];
                full[0] = o;
                System.arraycopy(args, 0, full, 1, args.length);
                clinit(owner);
                exec(f.c, f.m, full);
            }
            return o;
        }
        if (owner.startsWith("java/")) return Jdk.construct(owner, desc, args);
        return V.UNKNOWN;
    }

    private static int I(Object o) { return (Integer) o; }

    private static boolean isI(Object o) { return o instanceof Integer; }

    Object exec(ClassNode cn, MethodNode mn, Object[] args) {
        if (++depth > 96) { depth--; throw new Abort("recursion"); }
        try {
            return run(cn, mn, args);
        } catch (Abort a) {
            throw a;
        } catch (RuntimeException e) {
            Log.debug("interp " + cn.name + "." + mn.name + ": " + e);
            return V.UNKNOWN;
        } finally {
            depth--;
        }
    }

    private Object run(ClassNode cn, MethodNode mn, Object[] args) {
        Object[] loc = new Object[Math.max(mn.maxLocals, 1) + 8];
        boolean isStatic = (mn.access & ACC_STATIC) != 0;
        int li = 0, ai = 0;
        if (!isStatic) loc[li++] = args.length > 0 ? args[ai++] : V.UNKNOWN;
        for (Type t : Type.getArgumentTypes(mn.desc)) {
            Object a = ai < args.length ? args[ai++] : V.UNKNOWN;
            if (li < loc.length) loc[li] = a;
            li += t.getSize();
        }
        Object[] st = new Object[mn.maxStack + 8];
        int sp = 0;
        InsnList list = mn.instructions;
        AbstractInsnNode[] code = list.toArray();
        int pc = 0;
        while (pc < code.length) {
            if (++steps > maxSteps) throw new Abort("step limit");
            AbstractInsnNode in = code[pc];
            int op = in.getOpcode();
            if (op < 0) { pc++; continue; }
            int next = pc + 1;
            switch (op) {
                case NOP: break;
                case ACONST_NULL: st[sp++] = null; break;
                case ICONST_M1: case ICONST_0: case ICONST_1: case ICONST_2: case ICONST_3: case ICONST_4: case ICONST_5:
                    st[sp++] = op - ICONST_0; break;
                case LCONST_0: case LCONST_1: st[sp++] = (long) (op - LCONST_0); break;
                case FCONST_0: case FCONST_1: case FCONST_2: st[sp++] = (float) (op - FCONST_0); break;
                case DCONST_0: case DCONST_1: st[sp++] = (double) (op - DCONST_0); break;
                case BIPUSH: case SIPUSH: st[sp++] = ((IntInsnNode) in).operand; break;
                case LDC: {
                    Object c = ((LdcInsnNode) in).cst;
                    if (c instanceof Type t) st[sp++] = new ClassLit(t.getSort() == Type.OBJECT ? Names.cls(t.getInternalName()) : t.getDescriptor());
                    else if (c instanceof Integer || c instanceof Float || c instanceof Long || c instanceof Double || c instanceof String) st[sp++] = c;
                    else st[sp++] = V.UNKNOWN;
                    break;
                }
                case ILOAD: case LLOAD: case FLOAD: case DLOAD: case ALOAD:
                    st[sp++] = loc[((VarInsnNode) in).var]; break;
                case ISTORE: case LSTORE: case FSTORE: case DSTORE: case ASTORE: {
                    int v = ((VarInsnNode) in).var;
                    loc[v] = st[--sp];
                    if (op == LSTORE || op == DSTORE) loc[v + 1] = V.TOP;
                    break;
                }
                case IALOAD: case LALOAD: case FALOAD: case DALOAD: case AALOAD: case BALOAD: case CALOAD: case SALOAD: {
                    Object idx = st[--sp], arr = st[--sp];
                    if (arr instanceof V.Arr a && isI(idx)) {
                        int i = I(idx);
                        if (i < 0 || i >= a.a.length) throw new Abort("array index");
                        st[sp++] = a.a[i];
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case IASTORE: case LASTORE: case FASTORE: case DASTORE: case AASTORE: case BASTORE: case CASTORE: case SASTORE: {
                    Object val = st[--sp], idx = st[--sp], arr = st[--sp];
                    if (arr instanceof V.Arr a && isI(idx)) {
                        int i = I(idx);
                        if (i < 0 || i >= a.a.length) throw new Abort("array index");
                        if (op == BASTORE && isI(val)) val = (int) (byte) I(val);
                        else if (op == CASTORE && isI(val)) val = (int) (char) I(val);
                        else if (op == SASTORE && isI(val)) val = (int) (short) I(val);
                        a.a[i] = val;
                    }
                    break;
                }
                case POP: sp--; break;
                case POP2: if (V.wide(st[sp - 1])) sp--; else sp -= 2; break;
                case DUP: st[sp] = st[sp - 1]; sp++; break;
                case DUP_X1: { Object a = st[sp - 1], b = st[sp - 2]; st[sp - 2] = a; st[sp - 1] = b; st[sp++] = a; break; }
                case DUP_X2: {
                    Object a = st[sp - 1], b = st[sp - 2];
                    if (V.wide(b)) { st[sp - 2] = a; st[sp - 1] = b; st[sp++] = a; }
                    else { Object c = st[sp - 3]; st[sp - 3] = a; st[sp - 2] = c; st[sp - 1] = b; st[sp++] = a; }
                    break;
                }
                case DUP2: {
                    Object a = st[sp - 1];
                    if (V.wide(a)) st[sp++] = a;
                    else { Object b = st[sp - 2]; st[sp++] = b; st[sp++] = a; }
                    break;
                }
                case DUP2_X1: {
                    Object a = st[sp - 1];
                    if (V.wide(a)) { Object b = st[sp - 2]; st[sp - 2] = a; st[sp - 1] = b; st[sp++] = a; }
                    else { Object b = st[sp - 2], c = st[sp - 3]; st[sp - 3] = b; st[sp - 2] = a; st[sp - 1] = c; st[sp++] = b; st[sp++] = a; }
                    break;
                }
                case DUP2_X2: {
                    Object a = st[sp - 1], b = st[sp - 2];
                    if (V.wide(a) && V.wide(b)) { st[sp - 2] = a; st[sp - 1] = b; st[sp++] = a; }
                    else if (V.wide(a)) { Object c = st[sp - 3]; st[sp - 3] = a; st[sp - 2] = c; st[sp - 1] = b; st[sp++] = a; }
                    else { Object c = st[sp - 3], d = st[sp - 4]; st[sp - 4] = b; st[sp - 3] = a; st[sp - 2] = d; st[sp - 1] = c; st[sp++] = b; st[sp++] = a; }
                    break;
                }
                case SWAP: { Object a = st[sp - 1]; st[sp - 1] = st[sp - 2]; st[sp - 2] = a; break; }
                case IADD: case ISUB: case IMUL: case IDIV: case IREM: case ISHL: case ISHR: case IUSHR: case IAND: case IOR: case IXOR: {
                    Object b = st[--sp], a = st[--sp];
                    if (isI(a) && isI(b)) {
                        int x = I(a), y = I(b), r;
                        switch (op) {
                            case IADD: r = x + y; break;
                            case ISUB: r = x - y; break;
                            case IMUL: r = x * y; break;
                            case IDIV: if (y == 0) throw new Abort("div0"); r = x / y; break;
                            case IREM: if (y == 0) throw new Abort("div0"); r = x % y; break;
                            case ISHL: r = x << y; break;
                            case ISHR: r = x >> y; break;
                            case IUSHR: r = x >>> y; break;
                            case IAND: r = x & y; break;
                            case IOR: r = x | y; break;
                            default: r = x ^ y;
                        }
                        st[sp++] = r;
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case LADD: case LSUB: case LMUL: case LDIV: case LREM: case LSHL: case LSHR: case LUSHR: case LAND: case LOR: case LXOR: {
                    Object b = st[--sp], a = st[--sp];
                    if (a instanceof Long x && (b instanceof Long || b instanceof Integer)) {
                        long y = ((Number) b).longValue(), r;
                        switch (op) {
                            case LADD: r = x + y; break;
                            case LSUB: r = x - y; break;
                            case LMUL: r = x * y; break;
                            case LDIV: if (y == 0) throw new Abort("div0"); r = x / y; break;
                            case LREM: if (y == 0) throw new Abort("div0"); r = x % y; break;
                            case LSHL: r = x << y; break;
                            case LSHR: r = x >> y; break;
                            case LUSHR: r = x >>> y; break;
                            case LAND: r = x & y; break;
                            case LOR: r = x | y; break;
                            default: r = x ^ y;
                        }
                        st[sp++] = r;
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case FADD: case FSUB: case FMUL: case FDIV: case FREM: {
                    Object b = st[--sp], a = st[--sp];
                    if (a instanceof Float x && b instanceof Float y) {
                        float r = op == FADD ? x + y : op == FSUB ? x - y : op == FMUL ? x * y : op == FDIV ? x / y : x % y;
                        st[sp++] = r;
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case DADD: case DSUB: case DMUL: case DDIV: case DREM: {
                    Object b = st[--sp], a = st[--sp];
                    if (a instanceof Double x && b instanceof Double y) {
                        double r = op == DADD ? x + y : op == DSUB ? x - y : op == DMUL ? x * y : op == DDIV ? x / y : x % y;
                        st[sp++] = r;
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case INEG: { Object a = st[--sp]; st[sp++] = isI(a) ? -I(a) : V.UNKNOWN; break; }
                case LNEG: { Object a = st[--sp]; st[sp++] = a instanceof Long l ? -l : V.UNKNOWN; break; }
                case FNEG: { Object a = st[--sp]; st[sp++] = a instanceof Float f ? -f : V.UNKNOWN; break; }
                case DNEG: { Object a = st[--sp]; st[sp++] = a instanceof Double d ? -d : V.UNKNOWN; break; }
                case IINC: {
                    IincInsnNode ii = (IincInsnNode) in;
                    loc[ii.var] = isI(loc[ii.var]) ? I(loc[ii.var]) + ii.incr : V.UNKNOWN;
                    break;
                }
                case I2L: case I2F: case I2D: case L2I: case L2F: case L2D: case F2I: case F2L: case F2D: case D2I: case D2L: case D2F: case I2B: case I2C: case I2S: {
                    Object a = st[--sp];
                    if (!(a instanceof Number n)) { st[sp++] = V.UNKNOWN; break; }
                    Object r;
                    switch (op) {
                        case I2L: case F2L: case D2L: r = n.longValue(); break;
                        case I2F: case L2F: case D2F: r = n.floatValue(); break;
                        case I2D: case L2D: case F2D: r = n.doubleValue(); break;
                        case I2B: r = (int) (byte) n.intValue(); break;
                        case I2C: r = (int) (char) n.intValue(); break;
                        case I2S: r = (int) (short) n.intValue(); break;
                        default: r = (op == F2I) ? (int) n.floatValue() : (op == D2I) ? (int) n.doubleValue() : n.intValue();
                    }
                    st[sp++] = r;
                    break;
                }
                case LCMP: case FCMPL: case FCMPG: case DCMPL: case DCMPG: {
                    Object b = st[--sp], a = st[--sp];
                    if (a instanceof Number x && b instanceof Number y && !(a == V.UNKNOWN)) {
                        double dx = x.doubleValue(), dy = y.doubleValue();
                        if (Double.isNaN(dx) || Double.isNaN(dy)) st[sp++] = (op == FCMPG || op == DCMPG) ? 1 : -1;
                        else if (op == LCMP) st[sp++] = Long.compare(x.longValue(), y.longValue());
                        else st[sp++] = dx < dy ? -1 : dx > dy ? 1 : 0;
                    } else st[sp++] = V.UNKNOWN;
                    break;
                }
                case IFEQ: case IFNE: case IFLT: case IFGE: case IFGT: case IFLE: {
                    Object a = st[--sp];
                    if (isI(a)) {
                        int x = I(a);
                        boolean j = op == IFEQ ? x == 0 : op == IFNE ? x != 0 : op == IFLT ? x < 0 : op == IFGE ? x >= 0 : op == IFGT ? x > 0 : x <= 0;
                        if (j) next = list.indexOf(((JumpInsnNode) in).label);
                    }
                    break;
                }
                case IF_ICMPEQ: case IF_ICMPNE: case IF_ICMPLT: case IF_ICMPGE: case IF_ICMPGT: case IF_ICMPLE: {
                    Object b = st[--sp], a = st[--sp];
                    if (isI(a) && isI(b)) {
                        int x = I(a), y = I(b);
                        boolean j = op == IF_ICMPEQ ? x == y : op == IF_ICMPNE ? x != y : op == IF_ICMPLT ? x < y : op == IF_ICMPGE ? x >= y : op == IF_ICMPGT ? x > y : x <= y;
                        if (j) next = list.indexOf(((JumpInsnNode) in).label);
                    }
                    break;
                }
                case IF_ACMPEQ: case IF_ACMPNE: {
                    Object b = st[--sp], a = st[--sp];
                    if (a != V.UNKNOWN && b != V.UNKNOWN) {
                        boolean eq = a == b || (a instanceof V.Dir && a.equals(b));
                        if (eq == (op == IF_ACMPEQ)) next = list.indexOf(((JumpInsnNode) in).label);
                    }
                    break;
                }
                case IFNULL: case IFNONNULL: {
                    Object a = st[--sp];
                    if (a != V.UNKNOWN && ((a == null) == (op == IFNULL))) next = list.indexOf(((JumpInsnNode) in).label);
                    break;
                }
                case GOTO: next = list.indexOf(((JumpInsnNode) in).label); break;
                case TABLESWITCH: {
                    TableSwitchInsnNode ts = (TableSwitchInsnNode) in;
                    Object a = st[--sp];
                    LabelNode target = ts.dflt;
                    if (isI(a)) {
                        int k = I(a);
                        if (k >= ts.min && k <= ts.max) target = ts.labels.get(k - ts.min);
                    }
                    next = list.indexOf(target);
                    break;
                }
                case LOOKUPSWITCH: {
                    LookupSwitchInsnNode ls = (LookupSwitchInsnNode) in;
                    Object a = st[--sp];
                    LabelNode target = ls.dflt;
                    if (isI(a)) {
                        int k = ls.keys.indexOf(I(a));
                        if (k >= 0) target = ls.labels.get(k);
                    }
                    next = list.indexOf(target);
                    break;
                }
                case IRETURN: case LRETURN: case FRETURN: case DRETURN: case ARETURN: return st[--sp];
                case RETURN: return null;
                case GETSTATIC: {
                    FieldInsnNode f = (FieldInsnNode) in;
                    st[sp++] = getStatic(f.owner, f.name, f.desc);
                    break;
                }
                case PUTSTATIC: {
                    FieldInsnNode f = (FieldInsnNode) in;
                    putStatic(f.owner, f.name, st[--sp]);
                    break;
                }
                case GETFIELD: {
                    FieldInsnNode f = (FieldInsnNode) in;
                    Object o = st[--sp];
                    if (o instanceof V.Obj obj) {
                        Object v = obj.fields.get(f.name);
                        if (v == null && !obj.fields.containsKey(f.name)) v = V.defaultFor(f.desc.charAt(0));
                        st[sp++] = v;
                    } else {
                        Object b = Builtins.field(o, Names.mem(f.name));
                        st[sp++] = b == Builtins.NO ? V.UNKNOWN : b;
                    }
                    break;
                }
                case PUTFIELD: {
                    FieldInsnNode f = (FieldInsnNode) in;
                    Object v = st[--sp], o = st[--sp];
                    if (o instanceof V.Obj obj) obj.fields.put(f.name, v);
                    break;
                }
                case INVOKEVIRTUAL: case INVOKESPECIAL: case INVOKESTATIC: case INVOKEINTERFACE: {
                    MethodInsnNode m = (MethodInsnNode) in;
                    Type[] at = Type.getArgumentTypes(m.desc);
                    Object[] a = new Object[at.length];
                    for (int i = at.length - 1; i >= 0; i--) a[i] = st[--sp];
                    Object recv = op == INVOKESTATIC ? null : st[--sp];
                    Object r;
                    if (op == INVOKESPECIAL && m.name.equals("<init>") && recv instanceof Uninit u) {
                        Object o = construct(u.cls, m.desc, a);
                        for (int i = 0; i < sp; i++) if (st[i] == u) st[i] = o;
                        for (int i = 0; i < loc.length; i++) if (loc[i] == u) loc[i] = o;
                        r = null;
                    } else if (op == INVOKESPECIAL && m.name.equals("<init>")) {
                        Found f = recv instanceof V.Obj ? find(m.owner, "<init>", m.desc) : null;
                        if (f != null) {
                            Object[] full = new Object[a.length + 1];
                            full[0] = recv;
                            System.arraycopy(a, 0, full, 1, a.length);
                            exec(f.c, f.m, full);
                        }
                        r = null;
                    } else {
                        r = invoke(op, m.owner, m.name, m.desc, recv, a);
                    }
                    if (Type.getReturnType(m.desc).getSort() != Type.VOID) st[sp++] = r == null && Type.getReturnType(m.desc).getSort() < Type.ARRAY ? V.UNKNOWN : r;
                    break;
                }
                case INVOKEDYNAMIC: {
                    InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) in;
                    Type[] at = Type.getArgumentTypes(d.desc);
                    Object[] a = new Object[at.length];
                    for (int i = at.length - 1; i >= 0; i--) a[i] = st[--sp];
                    st[sp++] = indy(d, at, a);
                    break;
                }
                case NEW: st[sp++] = new Uninit(((TypeInsnNode) in).desc); break;
                case NEWARRAY: {
                    Object n = st[--sp];
                    if (!isI(n) || I(n) < 0 || I(n) > 1_000_000) throw new Abort("array size");
                    int t = ((IntInsnNode) in).operand;
                    char c = t == T_BOOLEAN ? 'Z' : t == T_CHAR ? 'C' : t == T_FLOAT ? 'F' : t == T_DOUBLE ? 'D' : t == T_BYTE ? 'B' : t == T_SHORT ? 'S' : t == T_INT ? 'I' : 'J';
                    st[sp++] = new V.Arr(I(n), String.valueOf(c), V.defaultFor(c));
                    break;
                }
                case ANEWARRAY: {
                    Object n = st[--sp];
                    if (!isI(n) || I(n) < 0 || I(n) > 1_000_000) throw new Abort("array size");
                    st[sp++] = new V.Arr(I(n), ((TypeInsnNode) in).desc, null);
                    break;
                }
                case MULTIANEWARRAY: {
                    MultiANewArrayInsnNode ma = (MultiANewArrayInsnNode) in;
                    int[] dims = new int[ma.dims];
                    for (int i = ma.dims - 1; i >= 0; i--) {
                        Object n = st[--sp];
                        if (!isI(n) || I(n) < 0 || I(n) > 100_000) throw new Abort("array size");
                        dims[i] = I(n);
                    }
                    st[sp++] = multi(dims, 0, ma.desc.substring(1));
                    break;
                }
                case ARRAYLENGTH: { Object a = st[--sp]; st[sp++] = a instanceof V.Arr ar ? ar.a.length : V.UNKNOWN; break; }
                case ATHROW: throw new Abort("throw");
                case CHECKCAST: break;
                case INSTANCEOF: {
                    Object a = st[--sp];
                    st[sp++] = (a != null && a != V.UNKNOWN) ? 1 : 0;
                    break;
                }
                case MONITORENTER: case MONITOREXIT: sp--; break;
                default: throw new Abort("opcode " + op);
            }
            pc = next;
        }
        return null;
    }

    private V.Arr multi(int[] dims, int i, String elem) {
        V.Arr a = new V.Arr(dims[i], elem, i == dims.length - 1 ? V.defaultFor(elem.charAt(0)) : null);
        if (i < dims.length - 1) for (int k = 0; k < a.a.length; k++) a.a[k] = multi(dims, i + 1, elem.substring(1));
        return a;
    }

    private Object indy(InvokeDynamicInsnNode d, Type[] at, Object[] a) {
        String bo = d.bsm.getOwner();
        if (bo.equals("java/lang/invoke/StringConcatFactory")) {
            for (Object o : a) if (o == V.UNKNOWN) return V.UNKNOWN;
            StringBuilder sb = new StringBuilder();
            if (d.bsm.getName().equals("makeConcatWithConstants")) {
                String recipe = (String) d.bsmArgs[0];
                int ai = 0, ci = 1;
                for (int i = 0; i < recipe.length(); i++) {
                    char c = recipe.charAt(i);
                    if (c == '\u0001') { sb.append(str(a[ai], at[ai])); ai++; }
                    else if (c == '\u0002') sb.append(d.bsmArgs[ci++]);
                    else sb.append(c);
                }
            } else for (int i = 0; i < a.length; i++) sb.append(str(a[i], at[i]));
            return sb.toString();
        }
        if (bo.equals("java/lang/invoke/LambdaMetafactory") && d.bsmArgs.length >= 2 && d.bsmArgs[1] instanceof Handle h)
            return new V.Lambda(h.getOwner(), h.getName(), h.getDesc(), h.getTag(), a, d.name);
        return V.UNKNOWN;
    }

    static String str(Object o, Type t) {
        if (o == null) return "null";
        switch (t.getSort()) {
            case Type.BOOLEAN: return o instanceof Integer i ? String.valueOf(i != 0) : String.valueOf(o);
            case Type.CHAR: return o instanceof Integer i ? String.valueOf((char) i.intValue()) : String.valueOf(o);
            default: return String.valueOf(o);
        }
    }
}
