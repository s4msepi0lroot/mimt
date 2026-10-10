package mimt.asm;

import mimt.ir.EMesh;
import java.util.*;

final class V {
    static final Object UNKNOWN = new Object() { public String toString() { return "<unknown>"; } };
    static final Object TOP = new Object() { public String toString() { return "<top>"; } };

    static final class Mesh {
        final EMesh.EPart root = new EMesh.EPart("root");
    }

    static final class Part {
        final EMesh.EPart p;
        Part(EMesh.EPart p) { this.p = p; }
    }

    static final class Cubes {
        final List<EMesh.ECube> cubes = new ArrayList<>();
        int u, v;
        boolean mirror;
    }

    static final class Pose {
        final float[] f;
        Pose(float... f) {
            this.f = new float[]{0, 0, 0, 0, 0, 0, 1, 1, 1};
            System.arraycopy(f, 0, this.f, 0, Math.min(9, f.length));
        }
    }

    static final class Deform {
        final float x, y, z;
        Deform(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    }

    static final class Layer {
        final EMesh mesh;
        Layer(EMesh mesh) { this.mesh = mesh; }
    }

    static final class Transformer {
        final float scale;
        Transformer(float s) { scale = s; }
    }

    static final class Dir {
        final String name;
        Dir(String n) { name = n; }
        public String toString() { return name; }
        public boolean equals(Object o) { return o instanceof Dir d && d.name.equals(name); }
        public int hashCode() { return name.hashCode(); }
    }

    static final class Obj {
        final String cls;
        final Map<String, Object> fields = new HashMap<>();
        Obj(String cls) { this.cls = cls; }
    }

    static final class Arr {
        final Object[] a;
        final String elem;
        Arr(int n, String elem, Object def) {
            a = new Object[Math.max(0, n)];
            Arrays.fill(a, def);
            this.elem = elem;
        }
    }

    static final class Lambda {
        final String implOwner, implName, implDesc;
        final int implTag;
        final Object[] captured;
        final String samName;
        Lambda(String o, String n, String d, int tag, Object[] cap, String sam) {
            implOwner = o; implName = n; implDesc = d; implTag = tag; captured = cap; samName = sam;
        }
    }

    static Object defaultFor(char t) {
        switch (t) {
            case 'Z': case 'B': case 'C': case 'S': case 'I': return 0;
            case 'J': return 0L;
            case 'F': return 0f;
            case 'D': return 0d;
            default: return null;
        }
    }

    static boolean wide(Object o) { return o instanceof Long || o instanceof Double; }
}
