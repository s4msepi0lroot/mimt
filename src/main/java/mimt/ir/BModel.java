package mimt.ir;

import java.awt.image.BufferedImage;
import java.util.*;

public class BModel {
    public String name;
    public double texW = 64, texH = 64;
    public final List<BTex> textures = new ArrayList<>();
    public final BGroup root = new BGroup("root");

    public static class BTex {
        public String name;
        public BufferedImage img;
        public double uvW, uvH;

        public BTex(String name, BufferedImage img) { this.name = name; this.img = img; }
    }

    public static class BGroup {
        public String name;
        public double[] origin = {0, 0, 0};
        public double[] rotation = {0, 0, 0};
        public final List<BGroup> children = new ArrayList<>();
        public final List<BCube> cubes = new ArrayList<>();
        public boolean visible = true;

        public BGroup(String name) { this.name = name; }

        public int count() {
            int n = cubes.size();
            for (BGroup g : children) n += g.count();
            return n;
        }
    }

    public static final int NORTH = 0, SOUTH = 1, EAST = 2, WEST = 3, UP = 4, DOWN = 5;
    public static final String[] FACE_NAMES = {"north", "south", "east", "west", "up", "down"};

    public static class BCube {
        public String name = "cube";
        public double[] from, to;
        public double[] origin = {0, 0, 0};
        public double[] rotation = {0, 0, 0};
        public double inflate;
        public boolean boxUv;
        public double[] uvOffset = {0, 0};
        public boolean mirror;
        public int boxTexture = 0;
        public final BFace[] faces = new BFace[6];
        public boolean visible = true;
    }

    public static class BFace {
        public double[] uv;
        public int rotation;
        public int texture = 0;

        public BFace(double[] uv, int rotation, int texture) { this.uv = uv; this.rotation = rotation; this.texture = texture; }
    }

    public int count() { return root.count(); }
}
