package mimt.util;

import com.google.gson.JsonElement;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class Img {
    public static BufferedImage read(byte[] data) {
        if (data == null) return null;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return null;
            return toArgb(src);
        } catch (Exception e) {
            return null;
        }
    }

    public static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(src, 0, 0, null);
        return img;
    }

    public static BufferedImage firstFrame(BufferedImage img, byte[] mcmeta) {
        if (img == null || mcmeta == null) return img;
        try {
            JsonElement anim = Json.obj(Json.parse(mcmeta), "animation");
            if (anim == null) return img;
            int w = img.getWidth(), h = img.getHeight();
            int fw = (int) Json.num(anim, "width", -1), fh = (int) Json.num(anim, "height", -1);
            if (fw <= 0 && fh <= 0) { fw = Math.min(w, h); fh = fw; }
            else if (fw <= 0) fw = w;
            else if (fh <= 0) fh = h;
            if (fw <= 0 || fh <= 0 || fw > w || fh > h) return img;
            if (fw == w && fh == h) return img;
            return copy(img.getSubimage(0, 0, fw, fh));
        } catch (Exception e) {
            return img;
        }
    }

    public static BufferedImage copy(BufferedImage src) {
        BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(src, 0, 0, null);
        return img;
    }

    public static BufferedImage square(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == h) return src;
        int s = Math.max(w, h);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(src, 0, 0, null);
        return img;
    }

    public static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static BufferedImage placeholder(int w, int h) {
        w = Math.max(1, w); h = Math.max(1, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                img.setRGB(x, y, ((x / 2 + y / 2) % 2 == 0) ? 0xFFFF00FF : 0xFF202020);
        return img;
    }

    public static int multiply(int argb, int rgb) {
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        r = r * ((rgb >> 16) & 255) / 255; g = g * ((rgb >> 8) & 255) / 255; b = b * (rgb & 255) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
