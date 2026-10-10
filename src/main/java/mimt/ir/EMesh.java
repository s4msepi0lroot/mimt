package mimt.ir;

import java.util.*;

public class EMesh {
    public EPart root = new EPart("root");
    public int texW = 64, texH = 32;

    public static class EPart {
        public String name;
        public float x, y, z, xRot, yRot, zRot;
        public float xScale = 1, yScale = 1, zScale = 1;
        public final List<ECube> cubes = new ArrayList<>();
        public final LinkedHashMap<String, EPart> children = new LinkedHashMap<>();

        public EPart(String name) { this.name = name; }

        public int count() {
            int n = cubes.size();
            for (EPart c : children.values()) n += c.count();
            return n;
        }
    }

    public static class ECube {
        public float ox, oy, oz, w, h, d;
        public float gx, gy, gz;
        public boolean mirror;
        public float u, v;
        public float su = 1, sv = 1;
        public Set<String> faces;
    }

    public int count() { return root.count(); }
}
