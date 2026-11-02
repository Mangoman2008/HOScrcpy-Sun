package ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Supplier;
import utils.Log;

/**
 * 纯享浮窗：无边框 + 逐像素透明（背景能透出桌面）+ 可拖动 + 滚轮缩放。
 * 双击或 Esc 关闭，回到主窗口。
 */
public class PhoneWindow extends JFrame {

    private final Supplier<java.awt.image.BufferedImage> imageSupplier;
    private final Runnable onClose;

    private int phoneH = 780;          // 手机高度（滚轮可调）
    private static final int MIN_H = 420;
    private static final int MAX_H = 1400;
    /** 纯享模式照系统"多屏协同"窗口画：507 宽时标题栏实测 40px（物理像素） */
    private static final int PLAIN_TITLE_H = 40;
    /** 纯享外阴影留白（物理 8px）：窗口比可见外框大一圈，用来画柔和阴影 */
    private static boolean HOVER_ON = false;
    /** 悬停效果开关（由主窗口传入）：关的时候连 Windows 光标也不隐藏 */
    public static void setHoverEnabled(boolean on) { HOVER_ON = on; }

    private static java.awt.Cursor blankCursor;
    static java.awt.Cursor blankCursor() {
        if (blankCursor == null) {
            try {
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                blankCursor = java.awt.Toolkit.getDefaultToolkit()
                        .createCustomCursor(img, new java.awt.Point(0, 0), "blank");
            } catch (Throwable t) {
                blankCursor = java.awt.Cursor.getDefaultCursor();
            }
        }
        return blankCursor;
    }

    private static java.awt.event.KeyListener SHARED_KEYS;
    /** 主窗口把键盘处理器放进来，纯享/迷你窗口统一使用 */
    public static void setPcKeyHandler(java.awt.event.KeyListener l) { SHARED_KEYS = l; }

    private int pad = 0;
    /** Java 坐标 → 物理像素的缩放（Windows 125% 时 = 1.25）。外框尺寸都按物理像素写，再除回来。 */
    private double uiScale = 1.0;
    private int sp(int physicalPx) {
        return Math.max(1, (int) Math.round(physicalPx / uiScale));
    }

    private Point dragStart;
    private Point winStart;

    /** 点击/拖动回调：坐标是"镜像帧像素" */
    public interface TouchTap {
        void onTap(int imgX, int imgY);
        default void onRightClick(int imgX, int imgY) { }   // 右键（同步主画面的右键注入）
        default void onMiddleClick() { }                    // 中键
        default void onNavBack() { }                        // 三大金刚：返回
        default void onNavHome() { }                        // 三大金刚：桌面
        default void onNavRecents() { }                     // 三大金刚：最近任务
        default void onPress(int imgX, int imgY) { }        // 按下（浮窗拖动用）
        default void onDrag(int imgX, int imgY) { }         // 拖动中
        default void onRelease(int imgX, int imgY) { }      // 抬起
        default void onWheel(int imgX, int imgY, boolean up) { }   // 滚轮（画面内滚动设备）
        void onSwipe(int x1, int y1, int x2, int y2);
        default void onHover(int imgX, int imgY) { }        // 鼠标悬停（无按键移动）
    }

    private TouchTap touchTap;
    public void setTouchTap(TouchTap t) { this.touchTap = t; }

    private final boolean plain;
    private volatile String hudText;         // 性能 HUD 文本（null=无数据）
    private volatile boolean hudEnabled;     // 纯享窗口里是否显示性能 HUD（☰ 菜单开关）
    private volatile String toastText;       // 短暂提示（切换输入模式等）
    private volatile long toastUntil = 0;
    private volatile boolean localHover;     // 鼠标模式：隐藏本机指针 + 发设备 hover（预览/预选中）
    private int lastSrcW = 0, lastSrcH = 0;   // 上一帧尺寸（检测换向）
    private PhonePanel panel;   // 显示面板（成员，供坐标换算等复用）
    private int navBarH = 36;                  // 底部三大金刚键高度（0=隐藏，120%）
    private final java.awt.Point[] navCenters = new java.awt.Point[3];
    private int navPressed = -1;
    private final java.awt.Point[] btnCenters = new java.awt.Point[4];
    private int titleH = 34;          // 标题栏高度（实测 ≈ 窗口高 8%）
    private int hoverNav = -1;             // 悬停的三大金刚（-1=无）
    // ---- 窗口拉伸（和系统窗口一样拖边框/四角）----
    private static final int EDGE = 6;                  // 物理像素
    private int resizeDir = 0;                          // 1=N 2=S 4=W 8=E
    private volatile boolean imageButtonDown = false;   // 画面里按着时不发 hover
    private long lastHoverSentAt = 0;
    private java.awt.Point resizeStartScreen;
    private java.awt.Rectangle resizeStartBounds;
    private int lastCursorType = -1;

    /** 命中窗口边缘/四角（返回方向位） */
    /** 只在四个角缩放（边不做缩放）：边上拖动容易误触，标题栏/阴影区留给移动 */
    private int hitEdge(java.awt.Point p) {
        int e = Math.max(3, sp(EDGE));
        int w = panel != null ? panel.getWidth() : getWidth();
        int h = panel != null ? panel.getHeight() : getHeight();
        boolean left = p.x <= e, right = p.x >= w - e;
        boolean top = p.y <= e, bottom = p.y >= h - e;
        if (top && left) return 5;
        if (top && right) return 9;
        if (bottom && left) return 6;
        if (bottom && right) return 10;
        return 0;
    }

    private void applyResizeCursor(int d) {
        if (d == 0) return;                 // 不在角上：光标交给 hover/默认逻辑，别覆盖成箭头
        int type = Cursor.DEFAULT_CURSOR;
        if (d == 1 || d == 2) type = Cursor.N_RESIZE_CURSOR;
        else if (d == 4 || d == 8) type = Cursor.E_RESIZE_CURSOR;
        else if (d == 5 || d == 10) type = Cursor.NW_RESIZE_CURSOR;
        else if (d == 9 || d == 6) type = Cursor.NE_RESIZE_CURSOR;
        if (type != lastCursorType) {
            lastCursorType = type;
            setCursor(Cursor.getPredefinedCursor(type));
        }
    }

    /** 等比缩放：以内容区（去掉标题栏/导航条）的宽高比为基准，四个方向都按比例走 */
    private void resizeTo(java.awt.Point now) {
        if (resizeStartBounds == null || resizeStartScreen == null) return;
        int dx = now.x - resizeStartScreen.x, dy = now.y - resizeStartScreen.y;
        int chromeH = titleH + navBarH;
        int startCW = Math.max(1, resizeStartBounds.width - pad * 2);
        int startCH = Math.max(1, resizeStartBounds.height - pad * 2 - chromeH);
        double aspect = (double) startCW / startCH;
        double sx = ((resizeDir & 8) != 0) ? (startCW + dx) / (double) startCW
                 : ((resizeDir & 4) != 0 ? (startCW - dx) / (double) startCW : 1.0);
        double sy = ((resizeDir & 2) != 0) ? (startCH + dy) / (double) startCH
                 : ((resizeDir & 1) != 0 ? (startCH - dy) / (double) startCH : 1.0);
        double s;
        if ((resizeDir & 12) != 0 && (resizeDir & 3) != 0) {
            s = Math.abs(sx - 1) >= Math.abs(sy - 1) ? sx : sy;   // 四角：取变化大的轴
        } else if ((resizeDir & 12) != 0) {
            s = sx;
        } else {
            s = sy;
        }
        s = Math.max(0.15, Math.min(6.0, s));
        int newCW = Math.max(sp(200), (int) Math.round(startCW * s));
        int newCH = Math.max(sp(240), (int) Math.round(newCW / aspect));
        newCW = Math.max(sp(200), (int) Math.round(newCH * aspect));
        int newW = newCW + pad * 2, newH = newCH + chromeH + pad * 2;
        int x = resizeStartBounds.x + (((resizeDir & 4) != 0) ? (startCW + pad * 2 - newW) : 0);
        int y = resizeStartBounds.y + (((resizeDir & 1) != 0) ? (startCH + chromeH + pad * 2 - newH) : 0);
        setBounds(x, y, newW, newH);
        phoneH = newCH;
    }
    private final float[] navAlpha = new float[3];   // 悬停/按下的透明度（0..1，动画过渡）
    private javax.swing.Timer navAnim;

    /** 悬停/按下的平滑过渡（16ms 一帧，约 150ms 到位） */
    private void startNavAnim() {
        if (navAnim == null) {
            navAnim = new javax.swing.Timer(16, e -> {
                boolean busy = false;
                for (int i = 0; i < 3; i++) {
                    float target = (navPressed == i) ? 1f : (hoverNav == i ? 0.55f : 0f);
                    float cur = navAlpha[i];
                    if (Math.abs(target - cur) > 0.02f) {
                        navAlpha[i] = cur + (target - cur) * 0.35f;
                        busy = true;
                    } else {
                        navAlpha[i] = target;
                    }
                }
                panel.repaint();
                if (!busy) navAnim.stop();
            });
        }
        navAnim.start();
    }

    private int calcTitleH(int h) {
        if (plain) return sp(PLAIN_TITLE_H);      // 纯享：和系统多屏协同一样，物理 40px
        return Math.max(26, Math.min(64, (int) (h * 0.08)));
    }
    private java.awt.Rectangle savedBounds;   // □ 最大化前的窗口位置（再点一次还原）
    private int hoverBtn = -1;        // 悬停的按钮序号（-1=无）
    private int pressBtn = -1;        // 按下的按钮序号（按下反馈）
    // 缩放缓存：只在源帧变化或窗口尺寸变化时重算，paint 只做 1:1 贴图（EDT 不再做重缩放）
    private BufferedImage scaledCache;
    private Object cachedSrcRef;
    private int cachedW = 0, cachedH = 0;
    private int maxSidePx = 0;                // 窗口最长边（换向时保持不变）

    public PhoneWindow(Supplier<BufferedImage> imageSupplier, Runnable onClose) {
        this(imageSupplier, onClose, false);
    }

    public PhoneWindow(Supplier<BufferedImage> imageSupplier, Runnable onClose, boolean plain) {
        super("纯享");
        this.plain = plain;
        this.imageSupplier = imageSupplier;
        this.onClose = onClose;
        setTitle(plain ? "多屏协同" : "小窗");   // 纯享窗口对用户就是"多屏协同"
        if (plain) {
            // 纯享 = 照系统多屏协同窗口：白色标题栏 + 画面，不要手机外壳、不要再叠一条导航条
            // （画面本身就是整块手机屏幕，底部自带 ◁ ○ □）
            try {
                java.awt.geom.AffineTransform t = GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getDefaultScreenDevice().getDefaultConfiguration().getDefaultTransform();
                if (t.getScaleX() > 0) uiScale = t.getScaleX();
            } catch (Throwable ignored) { }
            titleH = PLAIN_TITLE_H;
            navBarH = sp(50);   // 底部三大金刚条：系统窗口实测物理 50px 白底
            pad = sp(8);        // 外阴影留白
        }

        setUndecorated(true);
        setAlwaysOnTop(false);   // 原版是普通窗口，不置顶（菜单里可手动开）
        try {
            setBackground(new Color(0, 0, 0, 0));   // 逐像素透明
        } catch (Throwable ignored) {
        }

        panel = new PhonePanel();
        if (SHARED_KEYS != null) panel.addKeyListener(SHARED_KEYS);
        if (plain) {
            // 根面板只负责画外阴影；PhonePanel 内缩 pad，坐标体系不变（鼠标/触摸都不用改）
            JPanel root = new JPanel(null) {
                @Override public void doLayout() {
                    panel.setBounds(pad, pad, Math.max(1, getWidth() - pad * 2), Math.max(1, getHeight() - pad * 2));
                }
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    if (pad <= 0) return;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int w = getWidth(), h = getHeight();
                    int r = sp(8);
                    int layers = Math.max(3, pad);
                    // 由外向内叠加半透明圆角层，近似系统阴影
                    for (int i = layers; i >= 1; i--) {
                        int alpha = Math.max(1, (int) (22.0 * (1.0 - (double) i / layers)));
                        g2.setColor(new Color(0, 0, 0, alpha));
                        g2.fillRoundRect(pad - i, pad - i, w - 2 * (pad - i), h - 2 * (pad - i), r + i, r + i);
                    }
                    g2.dispose();
                }
            };
            root.setOpaque(false);
            root.add(panel);
            java.awt.event.MouseAdapter rootDrag = new java.awt.event.MouseAdapter() {
                private java.awt.Point rs, rw;
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    rs = e.getLocationOnScreen();
                    rw = getLocation();
                }
                @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                    if (rs == null) return;
                    java.awt.Point now = e.getLocationOnScreen();
                    setLocation(rw.x + now.x - rs.x, rw.y + now.y - rs.y);
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) { rs = null; }
            };
            root.addMouseListener(rootDrag);
            root.addMouseMotionListener(rootDrag);
            setContentPane(root);
        } else {
            setContentPane(panel);
        }

        // 让浮窗也能反控：把窗口内坐标映射成帧坐标后回调出去
        java.awt.event.MouseAdapter ma = new java.awt.event.MouseAdapter() {
            private int px, py;
            private java.awt.Point toImg(java.awt.Point p) {
                return toImgPoint(p);   // 统一走成员方法，避免两处坐标逻辑分叉
            }
            private boolean pressedInImage = false;
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                // 顶部按钮：⤢放大 / —缩小 / ✕关闭（和系统多屏协同一致的排列）
                if (e.getY() <= titleH) {
                    for (int i = 0; i < 4; i++) {
                        java.awt.Point pc = btnCenters[i];
                        if (pc != null && Math.abs(e.getX() - pc.x) <= Math.max(14, (int) (panel.getWidth() * 0.022))) {
                            pressBtn = i;
                            panel.repaint();
                            new javax.swing.Timer(130, ev -> { pressBtn = -1; panel.repaint(); }).start();
                        }
                    }
                    for (int i = 0; i < 4; i++) {
                        java.awt.Point c = btnCenters[i];
                        int hitW2 = Math.max(14, (int) (panel.getWidth() * 0.022));
                        if (c != null && Math.abs(e.getX() - c.x) <= hitW2) {
                            if (plain) {
                                // 纯享：和系统多屏协同一致的四个键（☰ — □ ✕）
                                if (i == 3) close();                                  // ✕ 关闭
                                else if (i == 2) toggleMaximize();                    // □ 最大化/还原
                                else if (i == 1) setState(java.awt.Frame.ICONIFIED);  // — 最小化
                                else showPlainMenu(c);                                // ☰ 菜单
                            } else if (i == 3) close();
                            else if (i == 2) { phoneH = Math.min(MAX_H, phoneH + 80); packWindow(); }
                            else if (i == 1) { phoneH = Math.max(MIN_H, phoneH - 80); packWindow(); }
                            else if (touchTap != null) touchTap.onNavHome();
                            return;
                        }
                    }
                }
                // 先判断三大金刚键（顺序按实测：◁ 返回 / ○ 桌面 / □ 多任务）
                if (navBarH > 0 && touchTap != null) {
                    for (int i = 0; i < navCenters.length; i++) {
                        java.awt.Point c = navCenters[i];
                        if (c != null && Math.abs(e.getX() - c.x) < 34
                                && Math.abs(e.getY() - c.y) < navBarH / 2 + 8) {
                            navPressed = i;          // 先高亮，松手才执行（和系统一致）
                            startNavAnim();
                            return;
                        }
                    }
                }
                java.awt.Point ip = toImg(e.getPoint());
                if (ip != null) {
                    px = ip.x; py = ip.y;
                    pressedInImage = true;
                    imageButtonDown = true;
                    if (touchTap != null) touchTap.onPress(ip.x, ip.y);   // ★ 真·按下
                } else {
                    pressedInImage = false;
                }
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (!pressedInImage || touchTap == null) return;
                java.awt.Point ip = toImg(e.getPoint());
                if (ip != null) touchTap.onDrag(ip.x, ip.y);              // ★ 拖动中持续发
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                imageButtonDown = false;
                if (navPressed >= 0) {
                    int idx = navPressed;
                    navPressed = -1;
                    java.awt.Point c = idx < navCenters.length ? navCenters[idx] : null;
                    boolean hit = c != null && Math.abs(e.getX() - c.x) < 34
                            && Math.abs(e.getY() - c.y) < navBarH / 2 + 8;
                    startNavAnim();                       // 松手：高亮淡出
                    if (hit && touchTap != null) {
                        if (idx == 0) touchTap.onNavBack();
                        else if (idx == 1) touchTap.onNavHome();
                        else touchTap.onNavRecents();
                    }
                    return;
                }
                if (touchTap == null) return;
                java.awt.Point ip = toImg(e.getPoint());
                if (ip == null) {
                    if (pressedInImage) { pressedInImage = false; touchTap.onRelease(px, py); }
                    return;
                }
                if (pressedInImage) {
                    pressedInImage = false;
                    touchTap.onRelease(ip.x, ip.y);                       // ★ 抬起
                    return;
                }
                if (javax.swing.SwingUtilities.isMiddleMouseButton(e)) {
                    if (touchTap != null) touchTap.onMiddleClick();   // 中键=桌面（和原版一致）
                    return;
                }
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    touchTap.onRightClick(ip.x, ip.y);      // ★ 右键：浮窗也能用
                    return;
                }
                if (Math.abs(ip.x - px) > 20 || Math.abs(ip.y - py) > 20) {
                    touchTap.onSwipe(px, py, ip.x, ip.y);
                } else {
                    touchTap.onTap(ip.x, ip.y);
                }
            }
        };
        panel.addMouseListener(ma);
        panel.addMouseMotionListener(ma);
        panel.addMouseMotionListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int hit = -1;
                if (e.getY() <= titleH) {
                    for (int i = 0; i < 4; i++) {
                        java.awt.Point c = btnCenters[i];
                        int hitW = Math.max(14, (int) (panel.getWidth() * 0.022));
                        if (c != null && Math.abs(e.getX() - c.x) <= hitW) { hit = i; break; }
                    }
                }
                if (hit != hoverBtn) { hoverBtn = hit; panel.repaint(); }
                // 三大金刚悬停：浅灰圆角底（和系统一样有反馈）
                int navHit = -1;
                if (navBarH > 0 && e.getY() >= panel.getHeight() - navBarH) {
                    for (int i = 0; i < 3; i++) {
                        java.awt.Point c = navCenters[i];
                        if (c != null && Math.abs(e.getX() - c.x) < 34
                                && Math.abs(e.getY() - c.y) < navBarH / 2 + 8) { navHit = i; break; }
                    }
                }
                if (navHit != hoverNav) { hoverNav = navHit; startNavAnim(); }
                // 画面区域：鼠标移动 → 设备 hover（原版那种图标放大/预选中）
                if (!imageButtonDown && touchTap != null && (HOVER_ON || localHover)) {
                    java.awt.Point ip = toImgPoint(e.getPoint());
                    if (ip != null) {
                        setCursor(blankCursor());   // 隐藏 Windows 指针，只保留设备 hover
                        long now = System.currentTimeMillis();
                        if (now - lastHoverSentAt >= 35) {
                            lastHoverSentAt = now;
                            touchTap.onHover(ip.x, ip.y);
                        }
                    } else if (resizeDir == 0) {
                        setCursor(Cursor.getDefaultCursor());
                    }
                } else if (!HOVER_ON && !localHover) {
                    // 不开 hover：鼠标指针正常显示（否则会看不见指针、没法点）
                    if (toImgPoint(e.getPoint()) != null && resizeDir == 0) {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
            }
            @Override public void mouseExited(java.awt.event.MouseEvent e) {
                if (hoverNav != -1) { hoverNav = -1; startNavAnim(); }
            }
        });

        // 关键：外部只有"新帧到达"才更新 supplier，本窗口必须自己定时重绘，
        // 否则会一直停在第一帧（甚至空白）——纯享/小窗模式"没有画面"的根因。
        javax.swing.Timer repaintTimer = new javax.swing.Timer(33, e -> {
            BufferedImage im = imageSupplier.get();
            if (im == null) return;
            if (im.getWidth() != lastSrcW || im.getHeight() != lastSrcH) {
                lastSrcW = im.getWidth();
                lastSrcH = im.getHeight();
                onOrientationChanged();
            }
            // 只有"画面引用变了"或"缓存尺寸变了"才重绘：不做无意义的重绘（省 EDT，触摸更跟手）
            if (im != cachedSrcRef || panel.getWidth() != cachedW || panel.getHeight() != cachedH) {
                panel.repaint();
            }
        });
        repaintTimer.setCoalesce(true);
        repaintTimer.start();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                repaintTimer.stop();
            }
            @Override public void windowClosing(WindowEvent e) {
                repaintTimer.stop();
            }
        });

        // 拖动窗口
        MouseAdapter drag = new MouseAdapter() {
            /** 是否按在"可拖动区域"（外框/边缘），画面区域内不拖，留给触控 */
            private boolean onBezel(Point p) {
                int ph = panel.getHeight();
                if (p.y >= ph - navBarH) return plain;      // 纯享：底部条空白处也可拖（三大金刚已在 mousePressed 里拦掉）
                if (plain && p.y <= titleH) return true;     // 小窗标题栏：拖动
                // 其它区域：落在画面外就是外框（用同一套坐标换算，避免和绘制不一致）
                return toImgPoint(p) == null;
            }
            @Override public void mousePressed(MouseEvent e) {
                panel.requestFocusInWindow();   // 点画面后键盘直接打到设备
                int d = hitEdge(e.getPoint());
                if (d != 0) {                                   // 边框/角：拉伸窗口
                    Log.info("PhoneWindow", "resize start dir=" + d + " p=" + e.getPoint() + " panel=" + panel.getSize());
                    resizeDir = d;
                    resizeStartScreen = e.getLocationOnScreen();
                    resizeStartBounds = getBounds();
                    return;
                }
                if (!onBezel(e.getPoint())) { dragStart = null; return; }   // 画面内不拖
                dragStart = e.getLocationOnScreen();
                winStart = getLocation();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (resizeDir != 0) { resizeTo(e.getLocationOnScreen()); return; }
                if (dragStart == null) return;
                Point now = e.getLocationOnScreen();
                setLocation(winStart.x + now.x - dragStart.x, winStart.y + now.y - dragStart.y);
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (resizeDir != 0) {
                    resizeDir = 0;
                    resizeStartScreen = null;
                    resizeStartBounds = null;
                    return;
                }
                dragStart = null;
            }
            @Override public void mouseMoved(MouseEvent e) {
                applyResizeCursor(hitEdge(e.getPoint()));
            }
            @Override public void mouseExited(MouseEvent e) {
                if (resizeDir == 0) {
                    lastCursorType = Cursor.DEFAULT_CURSOR;
                    setCursor(Cursor.getDefaultCursor());
                }
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 2 && e.getPoint().y <= titleH) { toggleMaximize(); return; }   // 双击标题栏=最大化/还原（原版行为）
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isControlDown()) {                    // Ctrl+滚轮 = 等比缩放窗口
                    phoneH -= e.getWheelRotation() * 40;
                    phoneH = Math.max(MIN_H, Math.min(MAX_H, phoneH));
                    packWindow();
                    return;
                }
                // ★ 画面区域内的滚轮 = 滚动设备内容（和系统多屏协同一致）
                if (!onBezel(e.getPoint())) {
                    java.awt.Point ip = toImgPoint(e.getPoint());
                    if (ip != null && touchTap != null) {
                        touchTap.onWheel(ip.x, ip.y, e.getWheelRotation() < 0);
                    }
                    return;
                }
                // 标题栏/外框上的滚轮 = 缩放窗口
                phoneH -= e.getWheelRotation() * 60;
                phoneH = Math.max(MIN_H, Math.min(MAX_H, phoneH));
                packWindow();
            }
        };
        panel.addMouseListener(drag);
        panel.addMouseMotionListener(drag);
        panel.addMouseWheelListener(drag);

        // Esc 关闭
        panel.setFocusable(true);
        panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "close");
        panel.getActionMap().put("close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { close(); }
        });

        packWindow();
        // 放到屏幕右侧 1/3 处，方便直接拖
        Dimension sc = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(sc.width / 2 - getWidth() / 2 + 160, Math.max(20, (sc.height - getHeight()) / 2));
        setVisible(true);
        panel.requestFocusInWindow();
    }

    private void close() {
        dispose();
        if (onClose != null) onClose.run();
    }

    /** □：最大化到屏幕高度（再点还原） */
    private void toggleMaximize() {
        if (savedBounds != null) {
            setBounds(savedBounds);
            phoneH = Math.max(MIN_H, savedBounds.height - titleH - navBarH - pad * 2);
            savedBounds = null;
            maxSidePx = Math.max(getWidth(), getHeight());
            invalidate();
            validate();
            repaint();
            return;
        }
        savedBounds = getBounds();
        java.awt.Dimension sc = Toolkit.getDefaultToolkit().getScreenSize();
        int target = Math.max(MIN_H, sc.height - 120) - titleH - navBarH;
        int w = calcW(target, Math.max(1, lastSrcW), Math.max(1, lastSrcH));
        phoneH = target;
        setSize(w + pad * 2, target + titleH + navBarH + pad * 2);
        setLocation((sc.width - w) / 2, 60);
        maxSidePx = Math.max(getWidth(), getHeight());
        invalidate();
        validate();
        repaint();
    }

    /** ☰：小菜单（回主窗口 / 置顶） */
    public interface MenuActions {
        void onQuality(String q);
        void onDisconnect();
        default String currentQuality() { return null; }
        default void onHudChanged(boolean on) { }
        default void onInputMode(String m) { }
        default String currentInputMode() { return null; }
        default void onNavBarChanged(boolean on) { }
        default void onHdcKill() { }
    }
    private MenuActions menuActions;
    public void setMenuActions(MenuActions a) { menuActions = a; }

    /** 性能 HUD：由 SunFrame 每秒推一次文本；开关状态由 ☰ 菜单控制 */
    public void setHudText(String t) {
        hudText = t;
        if (hudEnabled) repaint();
    }

    public void setHudEnabled(boolean on) {
        hudEnabled = on;
        repaint();
    }

    public boolean isHudEnabled() { return hudEnabled; }

    /** 平板/PC 形态：系统本身没有三大金刚（用底部任务栏），可以把整条隐藏。
     *  隐藏时把腾出的高度还给画面，窗口总高不变。 */
    public void setNavBarVisible(boolean on) {
        if (!plain) return;
        int want = on ? sp(50) : 0;
        if (navBarH == want) return;
        phoneH = Math.max(MIN_H, phoneH + (navBarH - want));
        navBarH = want;
        for (int i = 0; i < 3; i++) { navCenters[i] = null; navAlpha[i] = 0f; }
        chromeCache = null;
        packWindow();
        repaint();
    }

    public boolean isNavBarVisible() { return navBarH > 0; }

    /** 鼠标模式：隐藏本机指针，改用设备指针 + 发 hover（PC 应用的悬停预览/预选中） */
    public void setLocalHover(boolean on) {
        localHover = on;
        repaint();
    }

    /** 短暂提示：1.8 秒后自动消失（纯享窗口没有状态栏，切换模式时用它给反馈） */
    public void showToast(String t) {
        toastText = t;
        toastUntil = System.currentTimeMillis() + 1800;
        repaint();
        javax.swing.Timer tm = new javax.swing.Timer(300, e -> {
            if (System.currentTimeMillis() > toastUntil) {
                toastText = null;
                ((javax.swing.Timer) e.getSource()).stop();
            }
            repaint();
        });
        tm.setRepeats(true);
        tm.start();
    }

    public void closeWindow() { close(); }

    /** 恢复上次关闭时的位置/大小（纯享窗口的记忆） */
    public void restoreBounds(int x, int y, int w, int h) {
        // ★ 上次位置可能已经在屏幕外（拔掉副屏/改缩放/负坐标），必须夹回可见区域，
        //   否则标题栏跑到屏幕外面，用户根本抓不到窗口（实测 y=-217 就是这么来的）
        try {
            java.awt.Rectangle sb = getGraphicsConfiguration().getBounds();
            x = Math.max(sb.x - w + 120, Math.min(x, sb.x + sb.width - 120));
            y = Math.max(sb.y, Math.min(y, sb.y + sb.height - 120));
        } catch (Throwable ignored) { }
        setBounds(x, y, w, h);
        phoneH = Math.max(MIN_H, h - titleH - navBarH - pad * 2);
        invalidate();
        validate();
        repaint();
    }

    private void showPlainMenu(java.awt.Point c) {
        JPopupMenu pm = new JPopupMenu();
        JMenu quality = new JMenu("画质");
        String curQ = menuActions != null ? menuActions.currentQuality() : null;
        for (String q : new String[]{"原始", "50%", "33%"}) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(q, q.equals(curQ));
            item.addActionListener(a -> { if (menuActions != null) menuActions.onQuality(q); });
            quality.add(item);
        }
        pm.add(quality);
        JCheckBoxMenuItem hud = new JCheckBoxMenuItem("性能信息", hudEnabled);
        hud.addActionListener(a -> {
            setHudEnabled(hud.isSelected());
            if (menuActions != null) menuActions.onHudChanged(hud.isSelected());
        });
        pm.add(hud);
        // 输入模式（纯享窗口没有顶栏 🖱，用这里切）
        JMenu input = new JMenu("输入模式");
        String curMode = menuActions != null ? menuActions.currentInputMode() : null;
        String[][] modes = {{"touch", "触控（无指针）"}, {"mouse", "鼠标（指针跟随）"}, {"pc", "PC点击（不跟随）"}};
        for (String[] m : modes) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(m[1], m[0].equals(curMode));
            item.addActionListener(a -> { if (menuActions != null) menuActions.onInputMode(m[0]); });
            input.add(item);
        }
        pm.add(input);
        JCheckBoxMenuItem navItem = new JCheckBoxMenuItem("显示三大金刚", isNavBarVisible());
        navItem.addActionListener(a -> {
            setNavBarVisible(navItem.isSelected());
            if (menuActions != null) menuActions.onNavBarChanged(navItem.isSelected());
        });
        pm.add(navItem);
        JMenuItem dis = new JMenuItem("断开镜像");
        dis.addActionListener(a -> { if (menuActions != null) menuActions.onDisconnect(); });
        pm.add(dis);
        JMenuItem hdc = new JMenuItem("断开 hdc（停服务/释放端口）");
        hdc.addActionListener(a -> { if (menuActions != null) menuActions.onHdcKill(); });
        pm.add(hdc);
        pm.addSeparator();
        JMenuItem back = new JMenuItem("回到主窗口");
        back.addActionListener(a -> close());
        JCheckBoxMenuItem top = new JCheckBoxMenuItem("窗口置顶");
        top.setSelected(isAlwaysOnTop());
        top.addActionListener(a -> setAlwaysOnTop(top.isSelected()));
        pm.add(back);
        pm.add(top);
        pm.show(panel, Math.max(0, c.x - 40), titleH - 6);
    }

    /** 套用系统"多屏协同"窗口的位置和尺寸（外框一模一样）。
     *  ★ 脚本返回的是物理像素，而 Java 在 125% 缩放下用的是逻辑坐标（1.25 倍），
     *    所以要先除回来，否则窗口会大 25%、位置也偏。 */
    public void adoptMirrorBounds(java.awt.Rectangle r) {
        if (r == null || r.width < 120 || r.height < 200) return;
        int lx = (int) Math.round(r.x / uiScale);
        int ly = (int) Math.round(r.y / uiScale);
        int lw = (int) Math.round(r.width / uiScale);
        int lh = (int) Math.round(r.height / uiScale);
        titleH = calcTitleH(lh);
        phoneH = Math.max(MIN_H, lh - titleH - navBarH);
        setBounds(lx - pad, ly - pad, lw + pad * 2, lh + pad * 2);
        maxSidePx = Math.max(lw, lh);
        chromeCache = null;
        invalidate();
        validate();
        repaint();
        Log.info("PhoneWindow", "adopt mirror bounds " + r.width + "x" + r.height + " @ " + r.x + "," + r.y
                + " -> java " + lw + "x" + lh + " @ " + lx + "," + ly + " (scale=" + uiScale + ")");
    }

    private int calcW(int h, int srcW, int srcH) {
        double ar = srcH <= 0 ? 0.5 : (double) srcW / srcH;
        return (int) (h * ar);
    }

    /** 画面横竖切换：窗口按新比例重算（保持最长边不变，画面不被拉扁） */
    private void onOrientationChanged() {
        try {
            java.awt.Rectangle b = getBounds();
            int srcW = Math.max(1, lastSrcW), srcH = Math.max(1, lastSrcH);
            if (maxSidePx <= 0) maxSidePx = Math.max(b.width, b.height);
            int side = maxSidePx;
            int newW, newH;
            if (srcW > srcH) {           // 横屏：宽 = 最长边
                newW = side;
                newH = (int) ((long) side * srcH / srcW);
            } else {                      // 竖屏：高 = 最长边
                newH = side;
                newW = (int) ((long) side * srcW / srcH);
            }
            if (plain) {
                newH += titleH + navBarH;    // 纯享：标题栏 + 底部三大金刚
            } else {
                int bezel = Math.max(6, Math.min(newW, newH) / 45);
                newW += bezel * 2 + 12;
                newH += bezel * 2 + 12 + navBarH;
            }
            int cx = b.x + b.width / 2, cy = b.y + b.height / 2;
            int winW = newW + pad * 2, winH = newH + pad * 2;
            setBounds(cx - winW / 2, cy - winH / 2, winW, winH);
            invalidate();
            validate();
            repaint();
            Log.info("PhoneWindow", "orientation changed " + srcW + "x" + srcH
                    + "  " + b.width + "x" + b.height + " -> " + newW + "x" + newH);
        } catch (Throwable t) {
            Log.info("PhoneWindow", "orientation change failed: " + t);
        }
    }

    private void packWindow() {
        BufferedImage img = imageSupplier.get();
        int srcW = img == null ? 1316 : img.getWidth();
        int srcH = img == null ? 2832 : img.getHeight();
        int w = calcW(phoneH, srcW, srcH);
        if (plain) {
            // 纯享：标题栏 + 画面 + 底部三大金刚，没有手机外壳
            setSize(Math.max(200, w) + pad * 2, phoneH + titleH + navBarH + pad * 2);
            maxSidePx = Math.max(getWidth(), getHeight());
            repaint();
            return;
        }
        int bezel = Math.max(6, w / 45);
        setSize(w + bezel * 2 + 12, phoneH + bezel * 2 + 12 + navBarH);
        maxSidePx = Math.max(getWidth(), getHeight());
        repaint();
    }

    /** 纯绘制：手机外壳 + 画面 */
    private java.awt.image.BufferedImage chromeCache;
    private int chromeW = 0, chromeH = 0;

    /** 画"外框 + 三大金刚键 + 拖动条"到一张缓存图；每帧只贴，不重画（几乎零开销） */
    private java.awt.image.BufferedImage buildChrome(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = im.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (plain) {
            drawMirrorChrome(g2, w);
            drawMirrorNav(g2, w, h);
            g2.dispose();
            return im;
        }
        // 实测占比：投屏 81.3% / 缩放 86.2% / 最小化 91.1% / 关闭 95.6%
        double[] fx = {0.813, 0.862, 0.911, 0.956};
        int iconSz = Math.max(14, Math.min(26, titleH / 2));       // 随窗口放大
        int stroke = Math.max(1, iconSz / 9);
        g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 4; i++) {
            int cx = (int) (w * fx[i]);
            int cy = titleH / 2;
            btnCenters[i] = new java.awt.Point(cx, cy);
            // 悬停底色
            if (hoverBtn == i) {
                g2.setColor(new Color(255, 255, 255, 46));
                g2.fillRoundRect(cx - iconSz, cy - iconSz / 2, iconSz * 2, iconSz, iconSz / 2, iconSz / 2);
            }
            g2.setColor(hoverBtn == i ? Color.WHITE : new Color(0xE8EDF7));
            int r = iconSz / 2;
            switch (i) {
                case 0:   // 投屏：向上箭头 + 底座
                    g2.drawLine(cx, cy + r, cx, cy - r);
                    g2.drawLine(cx, cy - r, cx - r / 2, cy - r + r / 2);
                    g2.drawLine(cx, cy - r, cx + r / 2, cy - r + r / 2);
                    g2.drawLine(cx - r, cy + r, cx + r, cy + r);
                    break;
                case 1:   // 缩放：对角两个 L
                    g2.drawLine(cx - r, cy - r + r / 2, cx - r, cy - r);
                    g2.drawLine(cx - r, cy - r, cx - r + r / 2, cy - r);
                    g2.drawLine(cx + r - r / 2, cy + r, cx + r, cy + r);
                    g2.drawLine(cx + r, cy + r, cx + r, cy + r - r / 2);
                    break;
                case 2:   // 最小化：一条横线
                    g2.drawLine(cx - r, cy, cx + r, cy);
                    break;
                default:  // 关闭：X
                    g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                    g2.drawLine(cx + r, cy - r, cx - r, cy + r);
                    break;
            }
        }
        if (plain) {
        } else {
            int bezel = Math.max(6, w / 45);
            int ox = bezel + 6, oy = bezel + 6;
            int sw = w - (bezel + 6) * 2, sh = h - (bezel + 6) * 2 - navBarH - 8;
            int radius = Math.max(12, (int) (w * 0.019));   // 截图实测 ≈12-14px @718宽
            g2.setColor(new Color(0x0A0C11));
            g2.fillRoundRect(ox - bezel, oy - bezel, sw + bezel * 2, sh + bezel * 2,
                    radius + bezel, radius + bezel);
            g2.setColor(new Color(0x3A4152));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(ox - bezel, oy - bezel, sw + bezel * 2, sh + bezel * 2,
                    radius + bezel, radius + bezel);
        }
        // 底部导航条：照抄横屏截图（浅色渐变底 #E9E8E6→#E6E2D8 + 深色线框图标 □ ○ ◁）
        //   实测占比：□ 40.7% / ○ 49.8% / ◁ 59.1%
        if (navBarH > 0) {
            int navY = h - navBarH;                     // 贴底
            int navX = plain ? 0 : 2;                   // 纯享：几乎占满宽度
            int navW = plain ? w : w - 4;
            java.awt.GradientPaint navGp = new java.awt.GradientPaint(
                    navX, 0, new Color(0xE9E8E6), navX + Math.max(1, navW), 0, new Color(0xE6E2D8));
            g2.setPaint(navGp);
            g2.fillRect(navX, navY, navW, navBarH);
            double[] nfx = {0.22, 0.50, 0.78};   // 拉开间距，避免误触
            g2.setStroke(new BasicStroke(1.6f));
            for (int i = 0; i < 3; i++) {
                int cx = navX + (int) (navW * nfx[i]);
                int cy = navY + navBarH / 2;
                navCenters[i] = new java.awt.Point(cx, cy);
                int r = Math.max(6, navBarH / 4);
                g2.setColor(new Color(0x1A1A1A));
                if (i == 0) {
                    g2.drawRect(cx - r, cy - r, r * 2, r * 2);            // □ 多任务
                } else if (i == 1) {
                    g2.drawOval(cx - r, cy - r, r * 2, r * 2);            // ○ 桌面
                } else {
                    int[] xs = {cx - r + 2, cx + r - 2, cx + r - 2};      // ◁ 返回
                    int[] ys = {cy, cy - r + 2, cy + r - 2};
                    g2.drawPolygon(xs, ys, 3);
                }
            }
        }
        g2.dispose();
        return im;
    }

    /** 照 2026-10-29 实测的"多屏协同"窗口画标题栏：
     *  白色 40px；左 (20,8) 24px 蓝色 #0098FE 圆角图标 + "多屏协同"(YaHei 15, #333, x=61, baseline=28)；
     *  右 ☰ — □ ✕，中心距右边缘 168/120/72/24，图标 12px #333；无分隔线、无边框、圆角 2px。 */
    private void drawMirrorChrome(Graphics2D g2, int w) {
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, titleH);

        int isz = sp(24), ix = sp(20), iy = (titleH - isz) / 2 + 1;
        // 蓝色圆角底 + 竖向渐变（照系统图标实测：上 #00ACFD → 下 #0088FF）
        java.awt.GradientPaint iconBg = new java.awt.GradientPaint(
                0, iy, new Color(0x00ACFD), 0, iy + isz, new Color(0x0088FF));
        g2.setPaint(iconBg);
        g2.fillRoundRect(ix, iy, isz, isz, sp(5), sp(5));
        // 白色"显示器 + 手机"图形（24×24 网格实测：显示器 4,7→17,16；手机 15,11→21,20）
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(Math.max(1f, (float) (1.6 / uiScale)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new RoundRectangle2D.Float(ix + sp(4), iy + sp(7), sp(13), sp(9), sp(2), sp(2)));
        g2.setPaint(iconBg);   // 手机内部填蓝，盖住显示器右下角
        g2.fillRoundRect(ix + sp(15), iy + sp(11), sp(6), sp(9), sp(2), sp(2));
        g2.setColor(Color.WHITE);
        g2.draw(new RoundRectangle2D.Float(ix + sp(15), iy + sp(11), sp(6), sp(9), sp(2), sp(2)));

        // 字号/起点按系统实测校正：原版字墨 61..122（宽 61）、高 15；我们 15px 时只有 53 宽且偏右 5px
        int fs = Math.max(9, sp(17));
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, fs));
        g2.setColor(new Color(0x333333));
        g2.drawString("多屏协同", sp(56), sp(28));

        int[] dx = {sp(168), sp(120), sp(72), sp(24)};
        int ic = Math.max(8, sp(12)), r = ic / 2;
        for (int i = 0; i < 4; i++) {
            int cx = Math.max(r + sp(4), w - dx[i]);
            int cy = titleH / 2;
            btnCenters[i] = new java.awt.Point(cx, cy);
            if (pressBtn == i || hoverBtn == i) {
                g2.setColor(pressBtn == i ? new Color(0xE0E0E0) : new Color(0xF2F2F2));
                g2.fillRoundRect(cx - sp(18), cy - sp(14), sp(36), sp(28), sp(6), sp(6));
            }
            g2.setColor(new Color(0x333333));
            g2.setStroke(new BasicStroke(Math.max(1f, (float) (1.4 / uiScale)),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (i) {
                case 0:   // ☰
                    for (int k = -1; k <= 1; k++) g2.drawLine(cx - r, cy + k * sp(4), cx + r, cy + k * sp(4));
                    break;
                case 1:   // —
                    g2.drawLine(cx - r, cy, cx + r, cy);
                    break;
                case 2:   // □
                    g2.drawRect(cx - r, cy - r, ic, ic);
                    break;
                default:  // ✕
                    g2.drawLine(cx - r, cy - r, cx + r, cy + r);
                    g2.drawLine(cx + r, cy - r, cx - r, cy + r);
                    break;
            }
        }
    }

    /** 底部三大金刚（照系统多屏协同：白底 + ◁ ○ □，中心在 35.1% / 49.8% / 64.6%，物理高 50px） */
    private void drawMirrorNav(Graphics2D g2, int w, int h) {
        if (navBarH <= 0) return;
        int navY = h - navBarH;
        g2.setColor(Color.WHITE);
        g2.fillRect(0, navY, w, navBarH);
        int cy = navY + navBarH / 2;
        int mid = w / 2, gap = sp(75);   // 实测：三键以窗口中心对称，间距 75px
        for (int i = 0; i < 3; i++) navCenters[i] = new java.awt.Point(mid + (i - 1) * gap, cy);
        paintNavIcons(g2);
    }

    /** 三大金刚图标：2px 物理线宽、#1A1A1A、方框带圆角（照系统截图，比 1.6px 更清晰） */
    private void paintNavIcons(Graphics2D g2) {
        if (navCenters[0] == null) return;
        g2.setColor(new Color(0x1A1A1A));
        // 实测原版 dark 像素只有 ~2px；2.0 物理线宽会渲染成 3-4px（用户反馈"太粗"），调回 1.5
        g2.setStroke(new BasicStroke(Math.max(1f, (float) (1.0 / uiScale)),
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            java.awt.Point c = navCenters[i];
            if (c == null) continue;
            if (i == 0) {          // ◁ 返回：宽 12、高 15（物理）
                int rx = sp(6), ry = sp(8);
                int[] xs = {c.x - rx + sp(1), c.x + rx, c.x + rx};
                int[] ys = {c.y, c.y - ry, c.y + ry};
                g2.drawPolygon(xs, ys, 3);
            } else if (i == 1) {   // ○ 桌面：直径 15
                int r = sp(7);
                g2.drawOval(c.x - r, c.y - r, r * 2, r * 2);
            } else {               // □ 多任务：15×15，圆角 2
                int r = sp(7);
                g2.draw(new RoundRectangle2D.Float(c.x - r, c.y - r, r * 2, r * 2, sp(2), sp(2)));
            }
        }
    }

    /** 窗口坐标 → 画面像素坐标（供各处监听器共用） */
    private java.awt.Point toImgPoint(java.awt.Point p) {
        BufferedImage img = imageSupplier.get();
        if (img == null) return null;
        int w = panel != null ? panel.getWidth() : getWidth();
        int h = panel != null ? panel.getHeight() : getHeight();
        if (w <= 0 || h <= 0) return null;
        int bx, by, sw, sh;
        if (plain) {
            bx = 0; by = titleH; sw = w; sh = h - titleH - navBarH;
        } else {
            int bezel = Math.max(6, w / 45);
            bx = bezel + 6; by = bezel + 6;
            sw = w - bx * 2; sh = h - by * 2 - navBarH - 8;
        }
        if (sw <= 0 || sh <= 0) return null;
        if (p.y <= titleH || p.y >= h - navBarH) return null;
        if (p.x < bx || p.x > bx + sw || p.y < by || p.y > by + sh) return null;
        int ix = (int) ((p.x - bx) * (double) img.getWidth() / sw);
        int iy = (int) ((p.y - by) * (double) img.getHeight() / sh);
        return new java.awt.Point(
                Math.max(0, Math.min(img.getWidth() - 1, ix)),
                Math.max(0, Math.min(img.getHeight() - 1, iy)));
    }

    private class PhonePanel extends JPanel {
        PhonePanel() {
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            BufferedImage img = imageSupplier.get();
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int w = getWidth(), h = getHeight();
            // ===== 外框/导航栏 = 缓存图（只在尺寸/模式变化时重画，每帧只贴图）=====
            int wantTitle = plain ? calcTitleH(h) : 0;   // 纯享模式不占标题栏高度
            if (wantTitle != titleH) { titleH = wantTitle; chromeCache = null; }
            if (chromeCache == null || chromeW != w || chromeH != h) {
                chromeCache = buildChrome(w, h);
                chromeW = w; chromeH = h;
            }
            if (chromeCache != null) g2.drawImage(chromeCache, 0, 0, null);
            if (plain) {
                // 悬停/按下：浅灰圆角底 + 重画图标（图标要盖在高亮上，保持清晰）
                if (navBarH > 0) {
                    for (int i = 0; i < 3; i++) {
                        if (navAlpha[i] <= 0.02f || navCenters[i] == null) continue;
                        java.awt.Point c = navCenters[i];
                        g2.setColor(new Color(0, 0, 0, (int) (navAlpha[i] * 30)));
                        g2.fillRoundRect(c.x - sp(22), c.y - sp(15), sp(44), sp(30), sp(10), sp(10));
                    }
                    paintNavIcons(g2);
                }
            } else if (navPressed >= 0 && navCenters[navPressed] != null) {
                java.awt.Point c = navCenters[navPressed];
                g2.setColor(new Color(0x34, 0x5A, 0x9F, 160));
                g2.fillRoundRect(c.x - 31, c.y - 16, 62, 32, 14, 14);
                g2.setColor(new Color(0xFFFFFFFF));
                g2.setFont(new Font("Dialog", Font.BOLD, 18));
                int rr = Math.max(6, navBarH / 4);
                g2.setStroke(new BasicStroke(2.2f));
                if (navPressed == 0) {
                    int[] hx = {c.x - rr + 2, c.x + rr - 2, c.x + rr - 2};
                    int[] hy = {c.y, c.y - rr + 2, c.y + rr - 2};
                    g2.drawPolygon(hx, hy, 3);
                } else if (navPressed == 1) g2.drawOval(c.x - rr, c.y - rr, rr * 2, rr * 2);
                else g2.drawRect(c.x - rr, c.y - rr, rr * 2, rr * 2);
            }
            if (plain) {
                // 纯享：白色标题栏 + 整块手机画面；窗口 2px 圆角 + 1px 极淡描边
                // （系统多屏协同窗口有 DWM 阴影，我们只能用一条淡边代替）
                Shape oldClip = g2.getClip();
                g2.setClip(new RoundRectangle2D.Float(0, 0, w, h, sp(3), sp(3)));
                if (img != null) {
                    g2.drawImage(img, 0, titleH, w, h - titleH - navBarH, null);
                } else {
                    g2.setColor(new Color(0xF5F5F5));
                    g2.fillRect(0, titleH, w, h - titleH - navBarH);
                    g2.setColor(new Color(0x8B93A3));
                    g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                    String msg = "等待画面…";
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, titleH + (h - titleH - navBarH) / 2);
                }
                g2.setClip(oldClip);
                // 性能 HUD：右上角半透明小条（☰ → 性能信息 打开）
                if (hudEnabled && hudText != null) {
                    g2.setFont(new Font("Consolas", Font.PLAIN, Math.max(10, sp(11))));
                    FontMetrics fm = g2.getFontMetrics();
                    int tw = fm.stringWidth(hudText);
                    int padX = sp(8);
                    int bw = tw + padX * 2;
                    int bh = fm.getHeight() + sp(4);
                    int bx = w - bw - sp(10);
                    int by = titleH + sp(10);
                    if (bx > sp(10)) {
                        g2.setColor(new Color(0, 0, 0, 118));
                        g2.fillRoundRect(bx, by, bw, bh, sp(10), sp(10));
                        g2.setColor(new Color(0xFFFFFF));
                        g2.drawString(hudText, bx + padX, by + sp(2) + fm.getAscent());
                    }
                }
                // 切换输入模式等操作的短暂提示（没有状态栏，用浮动条给反馈）
                if (toastText != null && System.currentTimeMillis() < toastUntil) {
                    g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, Math.max(11, sp(13))));
                    FontMetrics fm2 = g2.getFontMetrics();
                    int tw2 = fm2.stringWidth(toastText);
                    int bw2 = tw2 + sp(24);
                    int bh2 = fm2.getHeight() + sp(10);
                    int bx2 = (w - bw2) / 2;
                    int by2 = h - navBarH - bh2 - sp(16);
                    if (bx2 > 0) {
                        g2.setColor(new Color(0, 0, 0, 150));
                        g2.fillRoundRect(bx2, by2, bw2, bh2, sp(12), sp(12));
                        g2.setColor(new Color(0xFFFFFF));
                        g2.drawString(toastText, bx2 + sp(12), by2 + sp(5) + fm2.getAscent());
                    }
                }
                g2.setColor(new Color(0, 0, 0, 26));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, sp(3), sp(3)));
                g2.dispose();
                return;
            }
            int bezel = Math.max(6, w / 45);
            int ox = bezel + 6, oy = bezel + 6;
            int sw = w - (bezel + 6) * 2;
            int sh = h - (bezel + 6) * 2 - navBarH;          // 上下留白一致，下沿紧贴导航条
            int radius = Math.max(12, (int) (w * 0.019));   // 截图实测 ≈12-14px @718宽

            if (img != null) {
                Shape old = g2.getClip();
                g2.setClip(new RoundRectangle2D.Float(ox, oy, sw, sh, radius, radius));
                if (scaledCache == null || cachedSrcRef != img || cachedW != sw || cachedH != sh) {
                    if (scaledCache == null || cachedW != sw || cachedH != sh) {
                        scaledCache = new java.awt.image.BufferedImage(Math.max(1, sw), Math.max(1, sh),
                                java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
                        cachedW = sw; cachedH = sh;
                    }
                    Graphics2D hg = scaledCache.createGraphics();
                    hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    hg.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_SPEED);
                    hg.drawImage(img, 0, 0, sw, sh, null);
                    hg.dispose();
                    cachedSrcRef = img;
                }
                g2.drawImage(scaledCache, ox, oy, null);
                g2.setClip(old);
            } else {
                g2.setColor(new Color(0x1A1F28));
                g2.fillRoundRect(ox, oy, sw, sh, radius, radius);
                g2.setColor(new Color(0x8B93A3));
                g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                String msg = "等待画面…";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, ox + (sw - fm.stringWidth(msg)) / 2, oy + sh / 2);
            }

            // 顶部提示（3 秒后淡出由主窗口控制，这里不画）
            g2.dispose();
        }
    }
}
