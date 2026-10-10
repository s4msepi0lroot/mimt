package mimt.mi;

import java.awt.image.BufferedImage;
import java.util.*;

public class MiModel {
    public String name;
    public String description;
    public String texture = "texture.png";
    public double[] textureSize = {16, 16};
    public final List<MiPart> parts = new ArrayList<>();
    public final Map<String, BufferedImage> textures = new LinkedHashMap<>();
    public final List<String> notes = new ArrayList<>();

    public int countShapes() {
        int n = 0;
        for (MiPart p : parts) n += p.countShapes();
        return n;
    }

    public void uniquifyNames() {
        Set<String> used = new HashSet<>();
        for (MiPart p : parts) uniq(p, used);
    }

    private static void uniq(MiPart p, Set<String> used) {
        String base = p.name == null || p.name.isBlank() ? "part" : p.name;
        String n = base;
        int i = 2;
        while (!used.add(n.toLowerCase(Locale.ROOT))) n = base + "_" + i++;
        p.name = n;
        for (MiPart c : p.parts) uniq(c, used);
    }
}
