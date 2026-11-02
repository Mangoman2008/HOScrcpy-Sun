package ui;

import javax.swing.*;
import java.awt.*;

/**
 * 手绘设备图标：手机（细长）或平板（宽扁）。
 * 用 Java2D 画，不依赖图片资源，任意尺寸都清晰。
 */
public class DeviceIcon implements Icon {

    public enum Kind { PHONE, PAD, PC, UNKNOWN }

    private final int w;
    private final int h;
    private final Color color;
    private final Kind kind;
    private boolean greenDot = false;   // 反控通道可用时右下角亮绿点

    /** 设置反控可用标记（绿点） */
    public DeviceIcon withGreenDot(boolean on) { this.greenDot = on; return this; }

    public DeviceIcon(int w, int h, Color color, Kind kind) {
        this.w = w;
        this.h = h;
        this.color = color;
        this.kind = kind == null ? Kind.UNKNOWN : kind;
    }

    @Override public int getIconWidth() { return w; }
    @Override public int getIconHeight() { return h; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        if (greenDot) {
            int r = Math.max(4, w / 6);
            int cx = x + w - r - 1, cy = y + h - r - 1;
            g2.setColor(new Color(0x19C37D));
            g2.fillOval(cx, cy, r, r);
            g2.setColor(new Color(0x0B2E20));
            g2.setStroke(new BasicStroke(1f));
            g2.drawOval(cx, cy, r, r);
        }
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(color);

        // 外框：手机细长，平板宽扁，PC 画显示器，未知用中间比例
        if (kind == Kind.PC) {
            int bw = (int) (w * 0.94), bh = (int) (h * 0.66);
            int bx = x + (w - bw) / 2, by = y + 2;
            float sw = 1.6f;
            g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawRoundRect(bx, by, bw, bh, 4, 4);
            // 支架
            int cx = bx + bw / 2;
            g2.drawLine(cx, by + bh, cx, by + bh + 4);
            g2.drawLine(cx - 5, by + bh + 4, cx + 5, by + bh + 4);
            g2.dispose();
            return;
        }
        int bw, bh;
        switch (kind) {
            case PHONE: bw = (int) (w * 0.52); bh = h; break;
            case PAD:   bw = (int) (w * 0.86); bh = (int) (h * 0.92); break;
            default:    bw = (int) (w * 0.64); bh = (int) (h * 0.96); break;
        }
        int bx = x + (w - bw) / 2;
        int by = y + (h - bh) / 2;

        float sw = 1.6f;
        g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawRoundRect(bx, by, bw, bh, 5, 5);

        // 屏幕内区（留一点边距，示意屏占比）
        int inset = 3;
        g2.setStroke(new BasicStroke(1f));
        Color c2 = new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.min(255, (int) (color.getAlpha() * 0.45f)));
        g2.setColor(c2);
        g2.drawRoundRect(bx + inset, by + inset, bw - inset * 2, bh - inset * 2, 3, 3);

        // 手机画听筒线 + 底部 home 条；平板画底部 home 条
        g2.setColor(color);
        if (kind == Kind.PHONE) {
            int cx = bx + bw / 2;
            g2.drawLine(cx - 3, by + 3, cx + 3, by + 3);
        }
        int cy = by + bh - 3;
        g2.drawLine(bx + bw / 2 - 3, cy, bx + bw / 2 + 3, cy);

        g2.dispose();
    }
}
