package ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * fps 频谱小图：每秒一个柱，颜色按帧率高低变化，看着"在动"。
 * 纯 Java2D 绘制，尺寸任意。
 */
public class FpsSparkline extends JComponent {

    private static final int MAX = 60;             // 保留最近 60 秒

    private final Deque<Double> samples = new ArrayDeque<>();
    private double peak = 30.0;

    private static final Color BG_DARK = new Color(0x12151A);
    private static final Color GRID = new Color(0x242A34);

    public FpsSparkline(int w, int h) {
        setPreferredSize(new Dimension(w, h));
        setMinimumSize(new Dimension(w, h));
        setOpaque(false);
    }

    /** 每秒喂一个样本 */
    public void addSample(double fps) {
        samples.addLast(fps);
        while (samples.size() > MAX) samples.removeFirst();
        peak = Math.max(peak * 0.98, Math.max(fps, 10.0));   // 峰值缓慢回落
        repaint();
    }

    public void clear() {
        samples.clear();
        repaint();
    }

    /** 彩虹色：横向位置决定色相（红→橙→黄→绿→青→蓝→紫），
     *  帧率高低决定亮度（低帧率更暗，高帧率更亮）。 */
    private static Color rainbow(int index, int total, double ratio) {
        float hue = (float) ((index * 0.85) / Math.max(total - 1, 1));   // 0..0.85 色相
        if (hue < 0) hue = 0;
        if (hue > 0.85f) hue = 0.85f;
        float bright = (float) (0.55 + 0.45 * Math.min(1.0, ratio));
        return Color.getHSBColor(hue, 0.85f, Math.min(1.0f, bright));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        g2.setColor(BG_DARK);
        g2.fillRoundRect(0, 0, w, h, 4, 4);

        // 两条参考线（1/3、2/3 高度）
        g2.setColor(GRID);
        g2.drawLine(0, h / 3, w, h / 3);
        g2.drawLine(0, h * 2 / 3, w, h * 2 / 3);

        if (samples.isEmpty()) {
            g2.setColor(new Color(0x6E7684));
            g2.setFont(new Font("Consolas", Font.PLAIN, 10));
            g2.drawString("fps", 4, h - 4);
            g2.dispose();
            return;
        }

        int n = samples.size();
        double barW = Math.max(1.0, (double) w / MAX);
        int i = 0;
        double last = 0;
        for (double v : samples) {
            int bh = (int) Math.round(h * Math.min(1.0, v / Math.max(peak, 1)));
            bh = Math.max(bh, 1);
            int x = (int) Math.round(w - (n - i) * barW);
            g2.setColor(rainbow(i, n, v / Math.max(peak, 1)));
            g2.fillRect(x, h - bh, (int) Math.ceil(barW), bh);
            last = v;
            i++;
        }

        // 当前值
        g2.setColor(new Color(0xE6EAF2));
        g2.setFont(new Font("Consolas", Font.BOLD, 10));
        String txt = String.format("%.0f", last);
        int tw = g2.getFontMetrics().stringWidth(txt);
        g2.setColor(new Color(0x0E1014));
        g2.fillRoundRect(w - tw - 8, 1, tw + 6, 12, 3, 3);
        g2.setColor(rainbow(n - 1, n, 1.0));
        g2.drawString(txt, w - tw - 5, h - 2);
        g2.dispose();
    }
}
