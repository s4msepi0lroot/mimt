package mimt.mi;

import java.util.ArrayList;
import java.util.List;

public class MiPart {
    public String name;
    public double[] position = {0, 0, 0};
    public double[] rotation;
    public double[] scale;
    public String texture;
    public double[] textureSize;
    public final List<MiShape> shapes = new ArrayList<>();
    public final List<MiPart> parts = new ArrayList<>();

    public MiPart(String name) { this.name = name; }

    public int countShapes() {
        int n = shapes.size();
        for (MiPart p : parts) n += p.countShapes();
        return n;
    }
}
