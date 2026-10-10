package mimt.gui;

import mimt.util.I18n;
import mimt.mi.MiMesher;
import mimt.mi.MiModel;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;

public final class PreviewPanel extends JComponent {
    private final Renderer rd = new Renderer();
    private List<MiMesher.Quad> quads;
    private double[] center = {0, 8, 0};
    private double radius = 16;
    private String message = I18n.t("Select a model on the left");
    private BufferedImage cache;
    private boolean dirty = true;
    private int lx, ly;

    public PreviewPanel() {
        setPreferredSize(new Dimension(520, 460));
        setMinimumSize(new Dimension(200, 200));
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { lx = e.getX(); ly = e.getY(); }
            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - lx, dy = e.getY() - ly;
                lx = e.getX(); ly = e.getY();
                if (SwingUtilities.isLeftMouseButton(e)) {
                    rd.yaw += dx * 0.6;
                    rd.pitch = Math.max(-89, Math.min(89, rd.pitch + dy * 0.6));
                } else { rd.panX += dx; rd.panY += dy; }
                refresh();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                rd.zoom *= Math.pow(1.15, -e.getPreciseWheelRotation());
                rd.zoom = Math.max(0.05, Math.min(40, rd.zoom));
                refresh();
            }
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) resetView(); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { refresh(); }
        });
        setToolTipText(I18n.t("LMB — rotate, RMB — pan, wheel — zoom, double click — reset"));
    }

    public void resetView() {
        rd.yaw = -35; rd.pitch = 25; rd.zoom = 1; rd.panX = 0; rd.panY = 0;
        refresh();
    }

    public void setMessage(String msg) {
        quads = null; message = msg; refresh();
    }

    public void setModel(MiModel m) {
        if (m == null) { setMessage(I18n.t("No model")); return; }
        List<MiMesher.Quad> q = MiMesher.build(m);
        if (q.isEmpty()) { setMessage(I18n.t("Model is empty")); return; }
        double[] mn = {1e9, 1e9, 1e9}, mx = {-1e9, -1e9, -1e9};
        for (MiMesher.Quad qq : q)
            for (double[] p : qq.p)
                for (int i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
        center = new double[]{(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2};
        double r = 0;
        for (int i = 0; i < 3; i++) r = Math.max(r, (mx[i] - mn[i]) / 2);
        radius = Math.max(1, r * 1.35);
        quads = q;
        message = null;
        refresh();
    }

    private void refresh() { dirty = true; repaint(); }

    @Override protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (quads == null) {
            g.setColor(new Color(0x2c3038));
            g.fillRect(0, 0, w, h);
            if (message != null) {
                g.setColor(new Color(0xb8bec9));
                FontMetrics fm = g.getFontMetrics();
                int y = h / 2 - fm.getHeight() * message.split("\n").length / 2;
                for (String line : message.split("\n")) {
                    g.drawString(line, (w - fm.stringWidth(line)) / 2, y + fm.getAscent());
                    y += fm.getHeight();
                }
            }
            return;
        }
        if (dirty || cache == null || cache.getWidth() != w || cache.getHeight() != h) {
            cache = rd.render(quads, w, h, center, radius);
            dirty = false;
        }
        g.drawImage(cache, 0, 0, null);
    }
}
