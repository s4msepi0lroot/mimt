package mimt.ir;

import java.awt.image.BufferedImage;

public class FaceSrc {
    public BufferedImage img;
    public double u1, v1, u2, v2;
    public int rotation;
    public int tint = -1;

    public FaceSrc(BufferedImage img, double u1, double v1, double u2, double v2, int rotation) {
        this.img = img; this.u1 = u1; this.v1 = v1; this.u2 = u2; this.v2 = v2; this.rotation = rotation;
    }

    public int sample(double s, double t) {
        double[] st = mimt.mi.BoxUv.rotateST(s, t, rotation);
        double u = u1 + st[0] * (u2 - u1), v = v1 + st[1] * (v2 - v1);
        int w = img.getWidth(), h = img.getHeight();
        int x = (int) Math.floor(u * w), y = (int) Math.floor(v * h);
        int xmin = (int) Math.floor(Math.min(u1, u2) * w + 1e-6), xmax = (int) Math.ceil(Math.max(u1, u2) * w - 1e-6) - 1;
        int ymin = (int) Math.floor(Math.min(v1, v2) * h + 1e-6), ymax = (int) Math.ceil(Math.max(v1, v2) * h - 1e-6) - 1;
        if (xmax < xmin) xmax = xmin;
        if (ymax < ymin) ymax = ymin;
        x = Math.max(xmin, Math.min(xmax, x));
        y = Math.max(ymin, Math.min(ymax, y));
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        int c = img.getRGB(x, y);
        if (tint != -1) c = mimt.util.Img.multiply(c, tint);
        return c;
    }
}
