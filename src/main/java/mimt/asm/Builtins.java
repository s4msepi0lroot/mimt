package mimt.asm;

import mimt.ir.EMesh;
import org.objectweb.asm.Type;
import java.util.*;

final class Builtins {
    static final Object NO = new Object();
    private static final String G = "net/minecraft/client/model/geom/";
    private static final String B = G + "builders/";
    static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};

    static float f(Object o) {
        if (o instanceof Number n) return n.floatValue();
        throw new Interp.Abort("non-numeric argument");
    }

    static int i(Object o) {
        if (o instanceof Number n) return n.intValue();
        throw new Interp.Abort("non-numeric argument");
    }

    static Object staticField(String owner, String name) {
        switch (owner) {
            case B + "CubeDeformation":
                if (name.equals("NONE")) return new V.Deform(0, 0, 0);
                break;
            case G + "PartPose":
                if (name.equals("ZERO")) return new V.Pose();
                break;
            case "net/minecraft/core/Direction":
                for (String d : DIRS) if (name.equalsIgnoreCase(d)) return new V.Dir(d);
                break;
            case "net/minecraft/util/Mth":
                switch (name) {
                    case "PI": return (float) Math.PI;
                    case "HALF_PI": return (float) (Math.PI / 2);
                    case "TWO_PI": return (float) (Math.PI * 2);
                    case "DEG_TO_RAD": return (float) (Math.PI / 180);
                    case "RAD_TO_DEG": return (float) (180 / Math.PI);
                    case "EPSILON": return 1.0E-5F;
                    case "SQRT_OF_TWO": return (float) Math.sqrt(2);
                }
                break;
        }
        return NO;
    }

    static Object field(Object o, String name) {
        if (o instanceof V.Pose p) {
            int k = poseIndex(name);
            if (k >= 0) return p.f[k];
        } else if (o instanceof V.Deform d) {
            switch (name) {
                case "growX": return d.x;
                case "growY": return d.y;
                case "growZ": return d.z;
            }
        }
        return NO;
    }

    private static int poseIndex(String n) {
        switch (n) {
            case "x": return 0;
            case "y": return 1;
            case "z": return 2;
            case "xRot": return 3;
            case "yRot": return 4;
            case "zRot": return 5;
            case "xScale": return 6;
            case "yScale": return 7;
            case "zScale": return 8;
            default: return -1;
        }
    }

    static Object construct(String owner, String desc, Object[] a) {
        switch (owner) {
            case B + "CubeListBuilder": return new V.Cubes();
            case B + "MeshDefinition": return new V.Mesh();
            case B + "CubeDeformation":
                if (a.length == 1) return new V.Deform(f(a[0]), f(a[0]), f(a[0]));
                if (a.length == 3) return new V.Deform(f(a[0]), f(a[1]), f(a[2]));
                break;
            case G + "PartPose": {
                float[] v = new float[a.length];
                for (int k = 0; k < a.length; k++) v[k] = f(a[k]);
                return new V.Pose(v);
            }
        }
        return NO;
    }

    static Object call(Interp ip, String owner, String name, String desc, Object recv, Object[] a, boolean isStatic) {
        if (!isStatic && recv != null) {
            if (recv instanceof V.Cubes c) return cubes(c, name, desc, a);
            if (recv instanceof V.Part p) return part(ip, p, name, desc, a);
            if (recv instanceof V.Mesh m) {
                if (name.equals("getRoot")) return new V.Part(m.root);
                if (name.equals("transformed") && a.length == 1 && a[0] instanceof V.Lambda l) {
                    V.Mesh nm = new V.Mesh();
                    copyInto(m.root, nm.root);
                    Object np = ip.callLambda(l, new Object[]{poseOf(m.root)});
                    if (np instanceof V.Pose pp) setPose(nm.root, pp);
                    return nm;
                }
                return NO;
            }
            if (recv instanceof V.Pose p) return pose(p, name, a);
            if (recv instanceof V.Deform d) {
                if (name.equals("extend")) {
                    if (a.length == 1) return new V.Deform(d.x + f(a[0]), d.y + f(a[0]), d.z + f(a[0]));
                    if (a.length == 3) return new V.Deform(d.x + f(a[0]), d.y + f(a[1]), d.z + f(a[2]));
                }
                Object fv = field(d, name);
                return fv;
            }
            if (recv instanceof V.Layer l) {
                if (name.equals("apply") && a.length == 1 && a[0] instanceof V.Transformer t) return new V.Layer(scale(l.mesh, t.scale));
                if (name.equals("apply") && a.length == 1 && a[0] instanceof V.Lambda lam) {
                    V.Mesh in = new V.Mesh();
                    copyInto(l.mesh.root, in.root);
                    Object r = ip.callLambda(lam, new Object[]{in});
                    if (r instanceof V.Mesh rm) {
                        EMesh e = new EMesh();
                        e.root = rm.root;
                        e.texW = l.mesh.texW;
                        e.texH = l.mesh.texH;
                        return new V.Layer(e);
                    }
                    return l;
                }
                return NO;
            }
            if (recv instanceof V.Dir d) {
                if (name.equals("getName") || name.equals("toString") || name.equals("getSerializedName")) return d.name;
                if (name.equals("ordinal")) return Arrays.asList(DIRS).indexOf(d.name);
                return NO;
            }
        }
        if (!isStatic) return NO;
        switch (owner) {
            case B + "CubeListBuilder":
                if (name.equals("create")) return new V.Cubes();
                break;
            case B + "LayerDefinition":
                if (name.equals("create") && a.length == 3 && a[0] instanceof V.Mesh m) {
                    EMesh e = new EMesh();
                    e.root = m.root;
                    e.texW = i(a[1]);
                    e.texH = i(a[2]);
                    return new V.Layer(e);
                }
                if (name.equals("create")) throw new Interp.Abort("LayerDefinition.create with unknown mesh");
                break;
            case B + "MeshTransformer":
                if (name.equals("scaling") && a.length == 1) return new V.Transformer(f(a[0]));
                break;
            case G + "PartPose":
                switch (name) {
                    case "offset": return new V.Pose(f(a[0]), f(a[1]), f(a[2]));
                    case "offsetAndRotation": return new V.Pose(f(a[0]), f(a[1]), f(a[2]), f(a[3]), f(a[4]), f(a[5]));
                    case "rotation": return new V.Pose(0, 0, 0, f(a[0]), f(a[1]), f(a[2]));
                }
                break;
            case "net/minecraft/client/model/HumanoidModel":
                if (ip.find(owner, name, desc) != null) return NO;
                if (name.equals("createMesh") && a.length == 2) return humanoid(deform(a[0]), f(a[1]));
                break;
            case "net/minecraft/client/model/PlayerModel":
                if (ip.find(owner, name, desc) != null) return NO;
                if (name.equals("createMesh") && a.length == 2) return player(deform(a[0]), a[1] instanceof Integer b && b != 0);
                break;
            case "net/minecraft/util/Mth":
                return mth(name, desc, a);
            case "net/minecraft/core/Direction":
                if (name.equals("values")) {
                    V.Arr arr = new V.Arr(6, "Lnet/minecraft/core/Direction;", null);
                    for (int k = 0; k < 6; k++) arr.a[k] = new V.Dir(DIRS[k]);
                    return arr;
                }
                break;
            case "java/util/EnumSet":
                return enumSet(name, a);
        }
        return NO;
    }

    private static V.Deform deform(Object o) {
        return o instanceof V.Deform d ? d : new V.Deform(0, 0, 0);
    }

    private static Object enumSet(String name, Object[] a) {
        Set<Object> s = new LinkedHashSet<>();
        switch (name) {
            case "noneOf": return s;
            case "allOf":
                if (a.length == 1 && a[0] instanceof Interp.ClassLit c && c.name.endsWith("/Direction")) {
                    for (String d : DIRS) s.add(new V.Dir(d));
                    return s;
                }
                return NO;
            case "of":
                for (Object o : a) {
                    if (o instanceof V.Arr arr) Collections.addAll(s, arr.a);
                    else s.add(o);
                }
                return s;
            case "copyOf":
                if (a.length == 1 && a[0] instanceof Collection<?> c) { s.addAll(c); return s; }
                return NO;
            case "complementOf":
                if (a.length == 1 && a[0] instanceof Collection<?> c) {
                    for (String d : DIRS) if (!c.contains(new V.Dir(d))) s.add(new V.Dir(d));
                    return s;
                }
                return NO;
        }
        return NO;
    }

    private static final float[] SIN = new float[65536];
    static {
        for (int k = 0; k < 65536; k++) SIN[k] = (float) Math.sin(k * Math.PI * 2.0 / 65536.0);
    }

    private static float sin(Object v, String desc, int off) {
        if (desc.startsWith("(D")) {
            double d = ((Number) v).doubleValue();
            return SIN[(int) ((long) (d * 10430.378350470453D + off) & 65535L)];
        }
        float x = f(v);
        return SIN[(int) (x * 10430.378F + off) & 65535];
    }

    private static Object mth(String name, String desc, Object[] a) {
        try {
            switch (name) {
                case "sin": return sin(a[0], desc, 0);
                case "cos": return sin(a[0], desc, 16384);
                case "sqrt": return (float) Math.sqrt(f(a[0]));
                case "abs": return a[0] instanceof Integer k ? (Object) Math.abs(k) : (Object) Math.abs(f(a[0]));
                case "floor": return (int) Math.floor(((Number) a[0]).doubleValue());
                case "ceil": return (int) Math.ceil(((Number) a[0]).doubleValue());
                case "square": return a[0] instanceof Integer k ? (Object) (k * k) : (Object) (f(a[0]) * f(a[0]));
                case "lerp": return f(a[1]) + f(a[0]) * (f(a[2]) - f(a[1]));
                case "clamp": {
                    if (a[0] instanceof Integer k) return Math.max(i(a[1]), Math.min(i(a[2]), k));
                    return Math.max(f(a[1]), Math.min(f(a[2]), f(a[0])));
                }
                case "wrapDegrees": { float v = f(a[0]) % 360; if (v >= 180) v -= 360; if (v < -180) v += 360; return v; }
            }
        } catch (RuntimeException e) {
            return V.UNKNOWN;
        }
        return Type.getReturnType(desc).getSort() == Type.VOID ? null : V.UNKNOWN;
    }

    private static Object pose(V.Pose p, String name, Object[] a) {
        int k = poseIndex(name);
        if (k >= 0 && a.length == 0) return p.f[k];
        switch (name) {
            case "translated": {
                V.Pose r = new V.Pose(p.f);
                r.f[0] += f(a[0]); r.f[1] += f(a[1]); r.f[2] += f(a[2]);
                return r;
            }
            case "withScale": {
                V.Pose r = new V.Pose(p.f);
                r.f[6] = r.f[7] = r.f[8] = f(a[0]);
                return r;
            }
            case "scaled": {
                V.Pose r = new V.Pose(p.f);
                float sx = f(a[0]), sy = a.length == 3 ? f(a[1]) : sx, sz = a.length == 3 ? f(a[2]) : sx;
                if (sx == 1 && sy == 1 && sz == 1) return r;
                r.f[0] *= sx; r.f[1] *= sy; r.f[2] *= sz;
                r.f[6] *= sx; r.f[7] *= sy; r.f[8] *= sz;
                return r;
            }
            case "withRotation": case "rotated": break;
        }
        return NO;
    }

    static V.Pose poseOf(EMesh.EPart e) {
        return new V.Pose(e.x, e.y, e.z, e.xRot, e.yRot, e.zRot, e.xScale, e.yScale, e.zScale);
    }

    static void setPose(EMesh.EPart e, V.Pose p) {
        float[] f = p.f;
        e.x = f[0]; e.y = f[1]; e.z = f[2]; e.xRot = f[3]; e.yRot = f[4]; e.zRot = f[5];
        e.xScale = f[6]; e.yScale = f[7]; e.zScale = f[8];
    }

    static void copyInto(EMesh.EPart from, EMesh.EPart to) {
        setPose(to, poseOf(from));
        to.cubes.addAll(from.cubes);
        to.children.putAll(from.children);
    }

    private static Object part(Interp ip, V.Part p, String name, String desc, Object[] a) {
        switch (name) {
            case "addOrReplaceChild": {
                if (a.length == 3 && a[0] instanceof String n && a[1] instanceof V.Cubes c && a[2] instanceof V.Pose pose)
                    return new V.Part(child(p.p, n, c, pose));
                if (a.length == 2 && a[0] instanceof String n && a[1] instanceof V.Part np) {
                    EMesh.EPart old = p.p.children.put(n, np.p);
                    np.p.name = n;
                    if (old != null) for (var e : old.children.entrySet()) np.p.children.putIfAbsent(e.getKey(), e.getValue());
                    return np;
                }
                if (a.length == 3 && a[0] instanceof String n && a[1] instanceof V.Cubes c)
                    return new V.Part(child(p.p, n, c, new V.Pose()));
                throw new Interp.Abort("addOrReplaceChild with unknown arguments");
            }
            case "getChild": {
                if (!(a[0] instanceof String n)) throw new Interp.Abort("getChild unknown");
                EMesh.EPart c = p.p.children.get(n);
                if (c == null) throw new Interp.Abort("getChild missing: " + n);
                return new V.Part(c);
            }
            case "clearChild": {
                if (a[0] instanceof String n) {
                    EMesh.EPart c = p.p.children.get(n);
                    if (c != null) {
                        EMesh.EPart e = new EMesh.EPart(n);
                        e.x = c.x; e.y = c.y; e.z = c.z; e.xRot = c.xRot; e.yRot = c.yRot; e.zRot = c.zRot;
                        e.children.putAll(c.children);
                        p.p.children.put(n, e);
                        return new V.Part(e);
                    }
                }
                return null;
            }
            case "clearRecursively": {
                clear(p.p);
                return p;
            }
            case "retainPartsAndChildren": {
                if (a.length == 1 && a[0] instanceof Collection<?> keep) retain(p.p, keep, false);
                return null;
            }
            case "retainExactParts": {
                if (a.length == 1 && a[0] instanceof Collection<?> keep) retain(p.p, keep, true);
                return null;
            }
            case "getChildren": return new ArrayList<>(p.p.children.values().stream().map(V.Part::new).toList());
            case "transformed": {
                if (a.length == 1 && a[0] instanceof V.Lambda l) {
                    EMesh.EPart np = new EMesh.EPart(p.p.name);
                    copyInto(p.p, np);
                    Object r = ip.callLambda(l, new Object[]{poseOf(p.p)});
                    if (r instanceof V.Pose pp) setPose(np, pp);
                    return new V.Part(np);
                }
                return p;
            }
        }
        return NO;
    }

    private static void retain(EMesh.EPart p, Collection<?> keep, boolean exact) {
        for (var e : p.children.entrySet()) {
            EMesh.EPart c = e.getValue();
            if (keep.contains(e.getKey())) {
                if (exact) clear(c);
            } else {
                c.cubes.clear();
                retain(c, keep, exact);
            }
        }
    }

    private static void clear(EMesh.EPart p) {
        for (EMesh.EPart c : p.children.values()) {
            c.cubes.clear();
            clear(c);
        }
    }

    private static EMesh.EPart child(EMesh.EPart parent, String n, V.Cubes c, V.Pose pose) {
        EMesh.EPart e = new EMesh.EPart(n);
        for (EMesh.ECube cube : c.cubes) e.cubes.add(copy(cube));
        float[] f = pose.f;
        e.x = f[0]; e.y = f[1]; e.z = f[2]; e.xRot = f[3]; e.yRot = f[4]; e.zRot = f[5];
        e.xScale = f[6]; e.yScale = f[7]; e.zScale = f[8];
        EMesh.EPart old = parent.children.put(n, e);
        if (old != null) e.children.putAll(old.children);
        return e;
    }

    private static EMesh.ECube copy(EMesh.ECube c) {
        EMesh.ECube r = new EMesh.ECube();
        r.ox = c.ox; r.oy = c.oy; r.oz = c.oz; r.w = c.w; r.h = c.h; r.d = c.d;
        r.gx = c.gx; r.gy = c.gy; r.gz = c.gz; r.mirror = c.mirror; r.u = c.u; r.v = c.v; r.su = c.su; r.sv = c.sv;
        r.faces = c.faces == null ? null : new TreeSet<>(c.faces);
        return r;
    }

    private static Object cubes(V.Cubes c, String name, String desc, Object[] a) {
        switch (name) {
            case "texOffs":
                c.u = i(a[0]);
                c.v = i(a[1]);
                return c;
            case "mirror":
                c.mirror = a.length == 0 || (a[0] instanceof Integer b && b != 0);
                return c;
            case "addBox":
                addBox(c, desc, a);
                return c;
            case "getCubes": return NO;
        }
        return NO;
    }

    private static void addBox(V.Cubes c, String desc, Object[] a) {
        Type[] t = Type.getArgumentTypes(desc);
        int k = 0;
        EMesh.ECube cube = new EMesh.ECube();
        cube.u = c.u;
        cube.v = c.v;
        cube.mirror = c.mirror;
        boolean named = t.length > 0 && t[0].getSort() == Type.OBJECT && t[0].getInternalName().equals("java/lang/String");
        if (named) k++;
        if (a.length < k + 6) throw new Interp.Abort("addBox args");
        cube.ox = f(a[k]); cube.oy = f(a[k + 1]); cube.oz = f(a[k + 2]);
        cube.w = f(a[k + 3]); cube.h = f(a[k + 4]); cube.d = f(a[k + 5]);
        k += 6;
        List<Integer> ints = new ArrayList<>();
        List<Float> floats = new ArrayList<>();
        for (; k < a.length; k++) {
            int s = t[k].getSort();
            Object v = a[k];
            if (s == Type.BOOLEAN) cube.mirror = v instanceof Integer b && b != 0;
            else if (s == Type.INT) ints.add(i(v));
            else if (s == Type.FLOAT) floats.add(f(v));
            else if (v instanceof V.Deform d) { cube.gx = d.x; cube.gy = d.y; cube.gz = d.z; }
            else if (v instanceof Collection<?> col) {
                cube.faces = new TreeSet<>();
                for (Object o : col) cube.faces.add(String.valueOf(o).toLowerCase(Locale.ROOT));
                if (cube.faces.size() >= 6) cube.faces = null;
            }
        }
        if (named && ints.size() >= 2) {
            cube.u = ints.get(0);
            cube.v = ints.get(1);
            c.u = (int) cube.u;
            c.v = (int) cube.v;
        }
        if (floats.size() >= 2) { cube.su = floats.get(0); cube.sv = floats.get(1); }
        c.cubes.add(cube);
    }

    static EMesh scale(EMesh m, float s) {
        EMesh r = new EMesh();
        r.texW = m.texW;
        r.texH = m.texH;
        r.root = new EMesh.EPart("root");
        copyInto(m.root, r.root);
        float f = 24.016F * (1.0F - s);
        r.root.x *= s; r.root.y = r.root.y * s + f; r.root.z *= s;
        r.root.xScale *= s; r.root.yScale *= s; r.root.zScale *= s;
        return r;
    }

    private static void box(EMesh.EPart p, int u, int v, boolean mirror, float x, float y, float z, float w, float h, float d, V.Deform g) {
        EMesh.ECube c = new EMesh.ECube();
        c.u = u; c.v = v; c.mirror = mirror;
        c.ox = x; c.oy = y; c.oz = z; c.w = w; c.h = h; c.d = d;
        c.gx = g.x; c.gy = g.y; c.gz = g.z;
        p.cubes.add(c);
    }

    private static EMesh.EPart add(EMesh.EPart parent, String n, float x, float y, float z) {
        EMesh.EPart e = new EMesh.EPart(n);
        e.x = x; e.y = y; e.z = z;
        parent.children.put(n, e);
        return e;
    }

    private static V.Deform ext(V.Deform g, float f) { return new V.Deform(g.x + f, g.y + f, g.z + f); }

    static V.Mesh humanoid(V.Deform g, float yo) {
        V.Mesh m = new V.Mesh();
        EMesh.EPart r = m.root;
        box(add(r, "head", 0, yo, 0), 0, 0, false, -4, -8, -4, 8, 8, 8, g);
        box(add(r, "hat", 0, yo, 0), 32, 0, false, -4, -8, -4, 8, 8, 8, ext(g, 0.5f));
        box(add(r, "body", 0, yo, 0), 16, 16, false, -4, 0, -2, 8, 12, 4, g);
        box(add(r, "right_arm", -5, 2 + yo, 0), 40, 16, false, -3, -2, -2, 4, 12, 4, g);
        box(add(r, "left_arm", 5, 2 + yo, 0), 40, 16, true, -1, -2, -2, 4, 12, 4, g);
        box(add(r, "right_leg", -1.9f, 12 + yo, 0), 0, 16, false, -2, 0, -2, 4, 12, 4, g);
        box(add(r, "left_leg", 1.9f, 12 + yo, 0), 0, 16, true, -2, 0, -2, 4, 12, 4, g);
        return m;
    }

    static V.Mesh player(V.Deform g, boolean slim) {
        V.Mesh m = humanoid(g, 0);
        EMesh.EPart r = m.root;
        V.Deform g2 = ext(g, 0.25f);
        r.children.remove("left_arm");
        r.children.remove("right_arm");
        if (slim) {
            box(add(r, "left_arm", 5, 2.5f, 0), 32, 48, false, -1, -2, -2, 3, 12, 4, g);
            box(add(r, "right_arm", -5, 2.5f, 0), 40, 16, false, -2, -2, -2, 3, 12, 4, g);
            box(add(r, "left_sleeve", 5, 2.5f, 0), 48, 48, false, -1, -2, -2, 3, 12, 4, g2);
            box(add(r, "right_sleeve", -5, 2.5f, 0), 40, 32, false, -2, -2, -2, 3, 12, 4, g2);
        } else {
            box(add(r, "left_arm", 5, 2, 0), 32, 48, false, -1, -2, -2, 4, 12, 4, g);
            box(add(r, "right_arm", -5, 2, 0), 40, 16, false, -3, -2, -2, 4, 12, 4, g);
            box(add(r, "left_sleeve", 5, 2, 0), 48, 48, false, -1, -2, -2, 4, 12, 4, g2);
            box(add(r, "right_sleeve", -5, 2, 0), 40, 32, false, -3, -2, -2, 4, 12, 4, g2);
        }
        r.children.remove("left_leg");
        box(add(r, "left_leg", 1.9f, 12, 0), 16, 48, false, -2, 0, -2, 4, 12, 4, g);
        box(add(r, "left_pants", 1.9f, 12, 0), 0, 48, false, -2, 0, -2, 4, 12, 4, g2);
        box(add(r, "right_pants", -1.9f, 12, 0), 0, 32, false, -2, 0, -2, 4, 12, 4, g2);
        box(add(r, "jacket", 0, 0, 0), 16, 32, false, -4, 0, -2, 8, 12, 4, g2);
        return m;
    }
}
