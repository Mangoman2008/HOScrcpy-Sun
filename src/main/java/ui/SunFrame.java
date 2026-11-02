package ui;

import com.huawei.hosscrcpy.api.HosRemoteConfig;
import com.huawei.hosscrcpy.api.HosRemoteDevice;
import com.huawei.hosscrcpy.api.ScreenCapCallback;
import utils.FfmpegDecoder;
import utils.HdcUtil;
import utils.KeyCodeUtil;
import utils.ProcessExecutor;
import utils.SettingUtil;
import utils.Log;
import utils.entity.Device;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * 向日葵 Mac 风格主窗口 —— 已接 SDK。
 *
 * SDK 用法（来自旧 MainForm）：
 *   HosRemoteConfig config = new HosRemoteConfig(sn); config.setIp(ip);
 *   dev = new HosRemoteDevice(config);
 *   dev.startImageScreenCapture(callback)      // 图片流（1fps 左右）
 *   dev.startCaptureScreen(callback)           // H.264 流（需要 FFmpeg 解码）
 *   dev.executeShellCommand("uinput -K -d 18 -u 18", 3)
 */
public class SunFrame extends JFrame {

    private static final String TAG = "SunFrame";
    /** 版本号：改界面/加功能时递增，便于区分 */
    public static final String VERSION = "0.136.46-navfix";

    private static final Color BG       = new Color(0x16181D);
    private static final Color BG_CARD  = new Color(0x1E2128);
    private static final Color BG_HOVER = new Color(0x262A33);
    private static final Color LINE     = new Color(0x2A2F3A);
    private static final Color FG       = new Color(0xE6EAF2);
    private static final Color FG_DIM   = new Color(0x8B93A3);
    private static final Color ACCENT   = new Color(0x3EA6FF);
    private static final Color OK       = new Color(0x37D67A);
    private static final Color ERR      = new Color(0xFF5D5D);

    private JLabel statusDot;
    private JLabel statusText;
    private JComboBox<Device> deviceBox;
    private JComboBox<String> modeBox;   // 自动 / 视频流 / 图片流
    private JComboBox<String> qualityBox; // 原始 / 50% / 33%
    private JComboBox<String> bitrateBox; // 默认 / 10M / 20M / 40M
    private JLabel ageLabel;             // 状态：无数据计时
    private FpsSparkline spark;           // fps 频谱小图
    private JLabel hwLabel;               // 硬解状态（HW / SW）
    private javax.swing.Timer ageTimer;
    private volatile long lastFrameAt = 0;         // 最后一帧时间
    private volatile long rawPackets = 0;          // 收到的原始 H.264 包数（诊断用）
    private java.util.concurrent.BlockingQueue<Pkt> h264Queue =
            new java.util.concurrent.ArrayBlockingQueue<>(12);  // 低延迟优先（0.136.24：原 30→12）

    /** 带到达时间戳的包：用来算"到达 → 解码完成"的流水线延迟（判断是不是我们这边堆帧） */
    private static final class Pkt {
        final byte[] data;
        final long tNs;
        Pkt(byte[] d) { data = d; tNs = System.nanoTime(); }
    }
    private volatile Thread decodeThread;
    private volatile boolean decodeRunning = false;
    private volatile long lastIdrRequestMs = 0;   // 请求 IDR 的节流
    private volatile int dropCount = 0;           // 因过载丢弃的积压次数
    private volatile boolean touchMode = true;     // true=触摸事件(设备不显示指针) false=鼠标事件
    private volatile boolean pcClick = false;      // 鼠标事件里再分：true=PC点击(点按才发鼠标事件，不跟随指针)
    private int lastMoveX, lastMoveY;
    private volatile int lastSentX = 0, lastSentY = 0;   // 上次真正发出的设备坐标（拖动插值起点）
    private long lastMoveSentAt = 0;
    private boolean touchChecked = false;
    private boolean touchAvailable = false;
    // 文件配置（不碰注册表，避免 "Access denied"）
    private static final utils.Prefs PREF = new utils.Prefs("ui", SunFrame.class);
    private boolean reconnectTried = false;
    /** 调试/自动测试用（默认关，不影响正常使用）：
     *  -Dsun.connect=IP:PORT 启动即 tconn 该地址并自动进画面，方便命令行跑真机冒烟测试。 */
    private final String debugConnect = System.getProperty("sun.connect", "").trim();
    private final boolean debugPure = "true".equalsIgnoreCase(System.getProperty("sun.pure"));
    private volatile boolean autoStarted = false;
    private volatile boolean autoReconnecting = false;   // 断线自动重连进行中（用户操作时取消）
    private volatile boolean daemonCleaned = false;   // 设备端残留服务每次启动只清一次
    // ===== 直连推流（HarmonyOS-Sender，Annex-B H.265/H.264 over TCP）=====
    private volatile boolean tcpStreaming = false;
    private Thread tcpThread;
    private volatile java.net.Socket tcpSocket;
    private volatile long lastDataAt;
    private JComboBox<String> codecBox;   // H.265 / H.264
    private int tcpFrameCount = 0;
    private int nalDumpCount = 0;
    private int dumpDecoded = 0;   // 调试：存前几帧 PNG
    private java.awt.image.BandCombineOp swapOp;
    // Access Unit 组包：HEVC/H.264 解码器需要"一帧的所有 NAL"一起送
    private final java.util.List<byte[]> auBuf = new java.util.ArrayList<>();
    private boolean auHasVcl = false;
    private boolean auIsHevc = true;
    private volatile String codecLabel = "H.264";
    private byte[] psVps;
    private byte[] psSps;
    private byte[] psPps;
    private boolean needParamSets = true;
    private volatile boolean waitingIdr = true;   // 连接后先等关键帧，避免花屏/缺参考帧   // 当前解码格式（日志/界面显示用）
    private volatile boolean hevcTried = false;
    // 双缓冲：解码线程写 back，EDT 画 front，彻底消除撕裂
    private final java.awt.image.BufferedImage[] bufs = new java.awt.image.BufferedImage[3];
    private int writeIdx = 0;
    private volatile int displayedIdx = -1;   // EDT 实际画在屏上的缓冲索引
    private volatile int pendingIdx = -1;     // 刚发布、待画的缓冲索引
    private volatile java.awt.image.BufferedImage frontBuf;
    private volatile java.awt.image.BufferedImage displayBuf;   // 已按窗口尺寸缩放好的成品帧（EDT 只做 1:1 贴图）
    private int dwBufW = 0, dhBufH = 0;                          // displayBuf 的尺寸（避免每帧重建）
    private volatile java.awt.Rectangle displayRect;            // 上面那张图应贴在窗口的哪个矩形
    private volatile boolean recording = false;    // 录屏中
    private int recordIndex = 0;
    private volatile long lastRecordAt = 0;
    private volatile java.io.File recordDir;       // 录屏输出目录
    private JPanel deviceListPanel;
    private ScreenView screenView;
    private JLabel frameInfo;
    private JLabel modeLabel;                      // 当前输入模式（触控/鼠标/PC点击）
    private final java.util.Map<String, JLabel> iconLabels = new java.util.HashMap<>();
    private final java.util.Map<String, JLabel> nameLabels = new java.util.HashMap<>();

    private HosRemoteDevice device;
    private PhoneWindow miniWin;      // 小窗模式的浮窗
    private volatile String streamHost = "";          // 当前推流端 IP（反控要用它 tconn）
    private volatile boolean controlReady = false;
    private volatile boolean controlBootstrapping = false;   // 正在建立控制通道
    private volatile long controlBootstrappingSince = 0;     // 开始时间（防卡死）
    private volatile String controlSn = "";                  // 控制用的设备 sn（ip:port）
    private volatile String streamSn = "";                   // 推流设备 sn（ip:port，控制通道复用同一个）
    private volatile int devScreenW = 0, devScreenH = 0;      // 设备真实屏幕尺寸（坐标换算用）
    private volatile boolean haveDeviceSize = false;
    // 触摸校准：发送坐标 = (帧坐标 × scale + offset)
    private volatile double calScaleX = 1.0, calScaleY = 1.0;
    private volatile double calOffX = 0, calOffY = 0;
    // 拖动加速：位移 = 按下点 + (当前点 - 按下点) × 增益（只影响拖动过程，不影响单击落点）
    private volatile double dragGainX = 1.0, dragGainY = 1.0;
    private volatile double landscapeGain = 1.0;    // 横屏额外加速（默认关：native 触摸不需要，加了会一顿一顿）
    private final java.util.Map<String, JLabel> metaLabels =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, JLabel> dotLabels = new java.util.concurrent.ConcurrentHashMap<>();
    private int frameCount = 0;
    private volatile int retryNoDevice = 0;
    private volatile String activeSn = null;
    private volatile String lastDeviceKind = null;
    private final java.util.Map<String, String> kindBySn =
            new java.util.concurrent.ConcurrentHashMap<>();   // SN -> kind（启动时就记，不依赖采集状态）
    private volatile String authShownForSn = null;   // 已确认过配对校验的设备，避免反复弹窗   // 当前设备形态（phone/tablet/2in1）
    private volatile boolean authPending = false;    // 配对校验对话框是否正在显示（这期间不要自动切纯享）
    private final java.io.ByteArrayOutputStream sdkAcc = new java.io.ByteArrayOutputStream();
    private volatile long stallWatchdogAt = 0;   // 上次卡死自愈时间（冷却用）
    private volatile int stallRestarts = 0;      // 连续重启次数（冷却逐步放宽）
    private volatile int noFrameRetries = 0;    // "onReady 但一包数据都没有"的硬重连次数（拿到帧就清零）
    private volatile long warmupUntil = 0;   // 预热期内不显示 fps（连接初期编码器慢）
    private final double[] fpsSmooth = new double[3];   // 3 秒滑动平均（显示用，避免数字乱跳）
    private int fpsSmoothIdx = 0;
    private volatile boolean useShellTouch = true;   // 触摸走 uitest uiInput（SDK 触摸 API 不可用时）
    private volatile int pressX = 0, pressY = 0;
    private volatile int moveX = 0, moveY = 0;
    private volatile boolean movedFar = false;
    private volatile int shellLastX = 0, shellLastY = 0;
    private volatile long lastShellSwipeAt = 0;
    private volatile boolean connecting = false;   // 防止连点"进入投屏"叠加多个流
    private volatile boolean capturing = false;    // 是否正在投屏（用于"停止投屏"切换）
    private volatile boolean stopped = false;      // 用户已点停止：之后收到的帧一律丢弃
    private volatile int captureGen = 0;           // 每次开/停加 1，旧回调据此自动失效
    private boolean fullScreen = false;            // 是否真全屏（无标题栏）
    private JComponent topBarRef;
    private JComponent iconBarRef;
    private JComponent devicePanelRef;
    private JSplitPane splitRef;
    private boolean borderless = false;            // 无边框全屏（驱动不支持独占全屏时的兜底）
    private java.awt.Rectangle restoreBounds;      // 退出全屏要恢复的位置
    private Thread capThread;                      // 采集线程（守护）
    private FfmpegDecoder decoder;                 // H.264 解码器
    /** 后台短任务线程池（替代零散的 new Thread，避免重连后线程堆积） */
    private final java.util.concurrent.ExecutorService bgExec =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sun-bg");
                t.setDaemon(true);
                return t;
            });

    private final java.util.concurrent.ExecutorService touchExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "touch");
                t.setDaemon(true);
                return t;
            });
    private int fpsFrames = 0;                     // 每秒帧数统计
    private long fpsStamp = System.currentTimeMillis();

    // ---------------- 性能指标（HUD）：解码耗时 / 帧间隔抖动 / 顿挫 / 码率 ----------------
    private final java.util.concurrent.atomic.AtomicLong bytesIn =
            new java.util.concurrent.atomic.AtomicLong();   // 统计周期内收到的视频字节
    private long lastArrivalNs = 0;                 // 上一帧到达时刻（纳秒）
    private double intervalEwma = 0;                // 平均帧间隔 ms
    private double jitterEwma = 0;                  // 帧间隔抖动 ms（|dt-均值| 的滑动平均）
    private int gapCount = 0;                       // 本周期顿挫次数（帧间隔 > 250ms）
    private volatile double hudDecodeMs = 0;        // 解码耗时（ms，解码器 EWMA）
    private volatile double hudJitterMs = 0;        // 抖动（ms）
    private volatile int hudGaps = 0;               // 每秒顿挫次数
    private volatile double hudMbps = 0;            // 码率（Mbps）
    private volatile double hudRateSmooth = 0;      // 平滑码率（约 3 秒）
    private volatile double hudPipeMs = 0;          // 到达→解码完成（ms）
    private volatile int hudQueue = 0;              // 队列峰值（判断有没有堆帧）
    private volatile long lastTouchNs = 0;          // 最近一次触摸注入时刻
    private double pipeEwma = 0;                    // 流水线延迟滑动平均
    private int queuePeak = 0;                      // 本统计周期的队列峰值
    private int lastDropSeen = 0;                   // 上一次统计时的 drops（判断是否突然变多）
    private long lastDropHintAt = 0;
    private volatile String lastHudText = null;     // 最近一次 HUD 文本（纯享窗口打开时立即套用）

    // ---------------- 自动重连增强：窗口恢复唤醒 + 放弃标记 ----------------
    private final Object reconnectLock = new Object();
    private volatile boolean reconnectWake = false;      // 前台恢复时提前触发下一次重试
    private volatile boolean autoReconnectGaveUp = false; // 1 分钟退避用完；窗口恢复后给一次机会

    private JButton enterBtn;                      // 进入/停止 投屏 按钮

    /** 看门狗：主窗/纯享/迷你都不显示超过 10 秒，说明进入了僵尸态（无窗口但进程占锁），直接退出 */
    private void startWindowGuard() {
        Thread guard = new Thread(() -> {
            long blankSince = 0;
            while (true) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                boolean any = false;
                try {
                    if (isShowing()) any = true;
                    PhoneWindow p1 = phoneWin; if (p1 != null && p1.isShowing()) any = true;
                    PhoneWindow p2 = miniWin; if (p2 != null && p2.isShowing()) any = true;
                } catch (Throwable ignored) { }
                if (!any) {
                    if (blankSince == 0) blankSince = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - blankSince > 10000) {
                        Log.info(TAG, "window guard: no visible window 10s -> exit");
                        Runtime.getRuntime().halt(0);
                    }
                } else {
                    blankSince = 0;
                }
            }
        }, "window-guard");
        guard.setDaemon(true);
        guard.start();
    }

    public SunFrame() {
        super("HOScrcpy · 向日葵版  v" + VERSION);
        // 授权：统一走"设备配对码"（和推流端 Sender 一致），不再用机器码

        prependHdcToPath();
        // 读取上次的触摸校准参数
        try {
            calScaleX = PREF.getDouble("calScaleX", 1.0);
            calScaleY = PREF.getDouble("calScaleY", 1.0);
            calOffX = PREF.getDouble("calOffX", 0.0);
            calOffY = PREF.getDouble("calOffY", 0.0);
            dragGainX = PREF.getDouble("dragGainX", 1.0);
            dragGainY = PREF.getDouble("dragGainY", 1.0);
            landscapeGain = PREF.getDouble("landscapeGain", 1.0);
        } catch (Throwable ignored) { }
        // heap watchdog：每 30 秒记录堆占用/线程数（下次死机可回看是不是内存/线程爆掉）
        javax.swing.Timer heapTimer = new javax.swing.Timer(30000, e -> {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMb = rt.maxMemory() / (1024 * 1024);
            Log.info(TAG, "heap " + usedMb + "MB/" + maxMb + "MB threads=" + Thread.activeCount());
        });
        heapTimer.start();

        // 启动顺序：kill 残留 hdc → 体检 → 自动 tconn 上次地址 → 最后才扫设备列表。
        // ★ 顺序不能反：原来 kill 和 refreshDevices 并发，扫描常常在 kill 之后拿到空列表，
        //   左侧就一直显示"未发现设备"，只能再手输一次 IP。
        bgExec.submit(() -> {
            try {
                String hb = findHdc();
                if (hb != null) {
                    utils.ProcessExecutor ex = new utils.ProcessExecutor();
                    String out = ex.callProcess(new String[]{hb, "kill"}, 6);
                    Log.info(TAG, "startup hdc reset (kill) -> " + (out == null ? "null" : out.trim()));
                    logHdcEnvironment(hb);
                    // 先扫一次（本机/USB 设备马上可见），再自动 tconn 上次的无线地址，连上就再刷一次
                    SwingUtilities.invokeLater(this::refreshDevices);
                    boolean reconnected;
                    if (!debugConnect.isEmpty()) {
                        Log.info(TAG, "debug connect (-Dsun.connect) " + debugConnect);
                        reconnected = tconnOnce(hb, debugConnect, "debug tconn");
                    } else if (Boolean.getBoolean("sun.p2p")) {
                        // 显式要求走 P2P 时才优先
                        String p2p = findP2pPeer();
                        reconnected = false;
                        if (p2p != null) {
                            reconnected = tconnOnce(hb, p2p + ":38387", "p2p tconn");
                            if (reconnected) PREF.put("p2pPeer", p2p);
                        }
                        if (!reconnected) reconnected = autoReconnectLast(hb);
                    } else {
                        // 默认走 LAN（目标机器没装华为管家，没有 P2P 可用）
                        reconnected = autoReconnectLast(hb);
                        if (!reconnected) {
                            // LAN 连不上时才看有没有 P2P 兜底（没装华为管家时这里返回 null）
                            String p2p = findP2pPeer();
                            if (p2p != null) {
                                Log.info(TAG, "LAN 未连上，尝试 P2P 兜底 " + p2p);
                                reconnected = tconnOnce(hb, p2p + ":38387", "p2p fallback");
                                if (reconnected) PREF.put("p2pPeer", p2p);
                            }
                        }
                    }
                    Log.info(TAG, "hdc targets (startup) = "
                            + oneLine(ex.callProcess(new String[]{hb, "list", "targets"}, 8)));
                    if (reconnected) SwingUtilities.invokeLater(this::refreshDevices);
                } else {
                    Log.info(TAG, "startup hdc reset: 程序目录下没有 hdc.exe（只能走 PATH 里的 hdc）");
                    SwingUtilities.invokeLater(this::refreshDevices);
                }
            } catch (Throwable t) {
                Log.info(TAG, "startup hdc reset failed: " + t);
                SwingUtilities.invokeLater(this::refreshDevices);
            }
        });
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        startWindowGuard();
        // 退出时自动断开 hdc（停止服务、释放端口），避免残留占用
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                new utils.ProcessExecutor().callProcess("hdc kill", 4);
                Log.info(TAG, "shutdown: hdc kill done");
            } catch (Throwable ignored) { }
        }, "hdc-exit"));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) { }
            @Override public void windowDeiconified(java.awt.event.WindowEvent e) { onWindowRestored(); }
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                autoReconnecting = false;
                // 关窗也记一笔：以前点 X 退出日志里什么都没有，事后没法判断是"用户关了"还是"崩了"
                Log.info(TAG, "window closing -> exit（capturing=" + capturing + "）");
                stopped = true;
                captureGen++;
                try { stopCaptureQuietly(); } catch (Throwable t) { Log.info(TAG, "close: stopCapture failed: " + t); }
                try { savePrefs(); } catch (Throwable ignored) { }
                if (ageTimer != null) ageTimer.stop();
                if (touchExec != null) touchExec.shutdownNow();
                dispose();
                System.exit(0);   // 确保进程退出、文件锁释放
            }
        });
        setSize(1280, 820);
        setMinimumSize(new Dimension(640, 480));   // 允许缩到更小
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout());
        topBarRef = buildTopBar();
        add(topBarRef, BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);

        // 先把"正在扫描"画出来：真正的扫描要等 hdc kill 完成（后台任务），这中间可能有几秒
        if (deviceListPanel != null) {
            deviceListPanel.add(emptyTip("正在扫描设备…"));
            deviceListPanel.revalidate();
            deviceListPanel.repaint();
        }

        setConnected(false);
        bindEsc();
        loadPrefs();
        // 设备扫描放在上面那个"kill 完成之后"的后台任务里（此时扫才不会和 kill 抢 hdc）
    }

    // ---------------- 顶栏 ----------------
    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_CARD);
        bar.setBorder(new EmptyBorder(10, 18, 10, 18));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        JLabel logo = new JLabel("◈");
        logo.setFont(new Font("Dialog", Font.BOLD, 22));
        logo.setForeground(ACCENT);
        JLabel title = new JLabel("HOScrcpy");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        title.setForeground(FG);
        left.add(logo);
        left.add(title);
        JLabel ver = new JLabel("v" + VERSION);
        ver.setFont(new Font("Consolas", Font.PLAIN, 11));
        ver.setForeground(FG_DIM);
        left.add(ver);

        // 画质档：放在左侧固定可见（之前放在中间被挤掉，用户看不到）
        JLabel qLabel = new JLabel("画质");
        qLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        qLabel.setForeground(FG_DIM);
        left.add(qLabel);
        qualityBox = new JComboBox<>(new String[]{"原始", "50%", "33%"});   // 实测：SDK 只认整数分频，75% 无效
        qualityBox.setSelectedItem("50%");   // 默认 50%（658x1416）：实测最能稳住 60fps
        qualityBox.setPreferredSize(new Dimension(84, 32));
        qualityBox.setBackground(BG_HOVER);
        qualityBox.setForeground(FG);
        qualityBox.setBorder(BorderFactory.createLineBorder(LINE));
        qualityBox.setToolTipText("画质/带宽：越低越跟手（改完自动重连生效）");
        Log.info(TAG, "默认画质 = " + qualityBox.getSelectedItem() + "（可手动切换 原始/50%/33%）");
        left.add(qualityBox);

        bitrateBox = new JComboBox<>(new String[]{"默认", "10M", "20M", "40M"});
        bitrateBox.setSelectedItem("默认");
        bitrateBox.setPreferredSize(new Dimension(76, 32));
        bitrateBox.setBackground(BG_HOVER);
        bitrateBox.setForeground(FG);
        bitrateBox.setBorder(BorderFactory.createLineBorder(LINE));
        bitrateBox.setToolTipText("视频码率：弱网（WiFi 差）选 10M/20M 更稳；默认=SDK 30Mbps");
        bitrateBox.addActionListener(e -> onQualityChanged());
        left.add(bitrateBox);

        JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        center.setOpaque(false);
        deviceBox = new JComboBox<>();
        deviceBox.setPreferredSize(new Dimension(260, 32));
        deviceBox.setBackground(BG_HOVER);
        deviceBox.setForeground(FG);
        deviceBox.setBorder(BorderFactory.createLineBorder(LINE));
        center.add(deviceBox);

        modeBox = new JComboBox<>(new String[]{"自动", "视频流(锁定)", "视频流", "图片流"});
        modeBox.setPreferredSize(new Dimension(92, 32));
        modeBox.setBackground(BG_HOVER);
        modeBox.setForeground(FG);
        modeBox.setBorder(BorderFactory.createLineBorder(LINE));
        qualityBox.addActionListener(e -> onQualityChanged());
        modeBox.setSelectedItem("视频流(锁定)");   // 默认锁定视频流：不自动降级到图片流（图片流无法反控）
        modeBox.setToolTipText("视频流(锁定)=永不降级；自动=没帧就降级；图片流=强制图片");
        center.add(modeBox);

        codecBox = new JComboBox<>(new String[]{"H.265", "H.264"});
        codecBox.setPreferredSize(new Dimension(78, 32));
        codecBox.setBackground(BG_HOVER);
        codecBox.setForeground(FG);
        codecBox.setBorder(BorderFactory.createLineBorder(LINE));
        codecBox.setToolTipText("直连推流时用哪种解码器（Sender 里选的编码格式要对应）");
        center.add(codecBox);


        statusDot = new JLabel("●");
        statusDot.setFont(new Font("Dialog", Font.PLAIN, 14));
        center.add(statusDot);

        statusText = new JLabel("未连接");
        statusText.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusText.setForeground(FG_DIM);
        center.add(statusText);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton stream = iconButton("📡", "直连推流（HarmonyOS-Sender 的 TCP 流）");
        stream.addActionListener(e -> promptStreamConnect());
        right.add(stream);


        JButton pushLog = barButton("📤", "上传日志", this::uploadLogs);
        pushLog.setToolTipText("把日志打包上传到本地调试服务器（127.0.0.1:8001）");
        pushLog.setPreferredSize(new java.awt.Dimension(96, 34));
        right.add(pushLog);

        JButton abBtn = barButton("🔀", "A/B切换", () -> {
            autoPathSwitch = !autoPathSwitch;
            Log.info(TAG, "auto path switch = " + autoPathSwitch);
            screenView.setHint(autoPathSwitch
                    ? "A/B 自动切换已开：竖屏用 A 路（原生跟手），横屏自动切 B 路（转屏快）"
                    : "A/B 自动切换已关");
        });
        abBtn.setToolTipText("竖屏走 A 路(H.264 原生)，横屏自动切 B 路(H.265 推流，转屏快)");
        abBtn.setPreferredSize(new java.awt.Dimension(96, 34));
        right.add(abBtn);

        JButton hwBtn = barButton("⚡", "硬解", () -> {
            boolean cur = Boolean.TRUE.equals(FfmpegDecoder.hwForceOn);
            FfmpegDecoder.hwForceOn = !cur;
            Log.info(TAG, "hw decode force = " + (!cur) + " (decoder disposed)");
            try { if (decoder != null) decoder.close(); } catch (Throwable t) { }
            decoder = null;
            screenView.setHint((!cur) ? "硬解已开 → 请重新连接（会重建解码器）" : "硬解已关 → 请重新连接");
        });
        hwBtn.setToolTipText("切换 GPU 硬解（D3D11VA）。默认关；开/关后需重连一次生效");
        hwBtn.setPreferredSize(new java.awt.Dimension(86, 34));
        right.add(hwBtn);

        // 转横屏/转竖屏按钮已隐藏（功能保留在代码里，需要时取消注释即可）

        JButton alignBtn = barButton("⊡", "对齐系统多屏协同窗口", this::alignToMirrorWindow);
        alignBtn.setToolTipText("把本窗口对齐到系统『多屏协同』的镜像窗口位置（外框覆盖绘制）");
        alignBtn.setPreferredSize(new java.awt.Dimension(40, 34));
        right.add(alignBtn);

        JButton exitBtn = barButton("✕", "退出程序", this::appExit);
        exitBtn.setToolTipText("退出：停止采集、释放设备、保存设置后关闭程序");
        exitBtn.setPreferredSize(new java.awt.Dimension(40, 34));
        right.add(exitBtn);

        JButton treeBtn = barButton("📑", "结构树", this::showLayoutTree);
        treeBtn.setToolTipText("dump 当前页面控件结构（SDK getLayout），可搜索/复制");
        treeBtn.setPreferredSize(new java.awt.Dimension(96, 34));
        right.add(treeBtn);

        JButton calBtn = barButton("🎯", "校准", this::calibrateTouch);
        calBtn.setToolTipText("触摸坐标校准：横向/纵向比例与偏移，可边点边调");
        calBtn.setPreferredSize(new java.awt.Dimension(86, 34));
        right.add(calBtn);


        JButton help = iconButton("?", "使用说明");
        help.addActionListener(e -> showHelp());
        right.add(help);

        JButton diag = iconButton("ⓘ", "自检（环境/设备/线程状态）");
        diag.addActionListener(e -> selfCheck());
        right.add(diag);

        JButton connect = iconButton("＋", "连接设备（输入 IP:端口）");
        connect.addActionListener(e -> promptConnect());
        right.add(connect);

        JButton refresh = iconButton("⟳", "刷新设备");
        refresh.addActionListener(e -> refreshDevices());
        right.add(refresh);
        JButton settings = iconButton("⚙", "设置");
        right.add(settings);
        JButton shot = iconButton("⛶", "全屏");
        shot.addActionListener(e -> toggleFullScreen());
        right.add(shot);
        JButton save = iconButton("📷", "截图保存");
        save.addActionListener(e -> saveScreenshot());
        right.add(save);
        JButton rec = iconButton("⏺", "录制画面（PNG 序列）");
        rec.addActionListener(e -> toggleRecording());
        right.add(rec);
        JButton paste = iconButton("📋", "粘贴本机剪贴板到设备（支持中文）");
        paste.addActionListener(e -> pasteClipboardToDevice());
        right.add(paste);

        JButton control = barButton("🎮", "发起反控", () -> {
            Log.info(TAG, "=== 点击了 [发起反控] ===");
            startControlChannel();
        });
        control.setToolTipText("发起反控：连上手机控制通道（hdc + uitest），成功后设备卡片亮绿点");
        control.setPreferredSize(new java.awt.Dimension(110, 34));
        right.add(control);

        // 小窗模式按钮已隐藏（与纯享重复，先不用）

        JButton pure = iconButton("📱", "纯享模式：手机外壳 + 全屏 + 隐藏工具栏（Esc 退出）");
        pure.addActionListener(e -> togglePureMode());
        right.add(pure);

        JButton web = iconButton("🌐", "浏览器投屏：局域网内用浏览器打开即可查看+操控");
        web.addActionListener(e -> toggleWebMirror());
        right.add(web);

        JButton tools = iconButton("🛠", "设备工具：应用启停 / 文件推拉 / 熄屏");
        tools.addActionListener(e -> showDeviceTools());
        right.add(tools);

        // 当前输入模式常显（纯享窗口里看不到这个，所以那边用 ☰ 菜单 + 浮动提示）
        modeLabel = new JLabel("触控");
        modeLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        modeLabel.setForeground(FG_DIM);
        modeLabel.setBorder(new EmptyBorder(0, 4, 0, 2));
        modeLabel.setToolTipText("当前输入模式（点 🖱 循环切换）");
        right.add(modeLabel);

        JButton mode = iconButton("🖱", "输入模式：触控 → 鼠标 → PC点击（点一下切换）");
        mode.addActionListener(e -> {
            int next = touchMode ? 1 : (pcClick ? 0 : 2);   // 触控→鼠标→PC点击→触控
            setInputMode(next == 0 ? "touch" : (next == 1 ? "mouse" : "pc"));
        });
        right.add(mode);

        bar.add(left, BorderLayout.WEST);
        bar.add(center, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    // ---------------- 中部 ----------------
    private JComponent buildBody() {
        devicePanelRef = buildDeviceList();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, devicePanelRef, buildScreenAndBar());
        splitRef = split;
        split.setDividerSize(1);
        split.setBorder(null);
        split.setDividerLocation(230);
        split.setBackground(LINE);
        return split;
    }

    private JComponent buildDeviceList() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(12, 12, 12, 6));

        JLabel head = new JLabel("设备");
        head.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        head.setForeground(FG_DIM);
        head.setBorder(new EmptyBorder(0, 6, 8, 0));
        panel.add(head, BorderLayout.NORTH);

        deviceListPanel = new JPanel();
        deviceListPanel.setLayout(new BoxLayout(deviceListPanel, BoxLayout.Y_AXIS));
        deviceListPanel.setBackground(BG);

        JScrollPane sp = new JScrollPane(deviceListPanel);
        sp.setBorder(null);
        sp.getViewport().setBackground(BG);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private JComponent deviceCard(Device d) {
        String name = d.getSn();
        // Device 的 ip 字段默认是 127.0.0.1（那只是本地 hdc server 地址，不是设备地址）。
        // 网络设备的真实地址就在 SN 里（形如 192.168.1.100:40601）。
        String devIp = d.getIp();
        String addr;
        if (devIp == null || devIp.isEmpty() || "127.0.0.1".equals(devIp) || "localhost".equals(devIp)) {
            addr = name;
        } else {
            addr = devIp + "  ·  " + name;
        }
        // 能出现在 list targets 里的设备就是在线，不要在这里调 d.isOnline()
        // —— 它内部会跑一次 hdc（最长 10s），而本方法在 EDT 上执行，会冻界面 ✗
        boolean online = true;

        JPanel card = new JPanel(new BorderLayout(10, 0));
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1, true),
                new EmptyBorder(8, 10, 8, 10)));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

        // 手绘设备图标：先画未知，后台查到真实类型后替换
        JLabel thumb = new JLabel(new DeviceIcon(34, 40, online ? ACCENT : FG_DIM, DeviceIcon.Kind.UNKNOWN));
        thumb.setPreferredSize(new Dimension(36, 42));
        card.add(thumb, BorderLayout.WEST);

        JPanel info = new JPanel(new GridLayout(3, 1, 0, 2));
        info.setOpaque(false);
        JLabel n = new JLabel(name.length() > 22 ? name.substring(0, 22) + "…" : name);
        n.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        n.setForeground(FG);
        JLabel a = new JLabel(addr);
        a.setFont(new Font("Consolas", Font.PLAIN, 10));
        a.setForeground(FG_DIM);
        JLabel meta = new JLabel("…");
        meta.setFont(new Font("Consolas", Font.PLAIN, 10));
        meta.setForeground(FG_DIM);
        info.add(n);
        info.add(a);
        info.add(meta);
        card.add(info, BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 74));
        // 后台补型号/品牌/分辨率（不阻塞 EDT）
        final String snKey2 = d.getSn();
        if (snKey2 != null) {
            metaLabels.put(snKey2, meta);
            bgExec.submit(() -> {
                String model = HdcUtil.queryDeviceModel(snKey2);
                String brand = HdcUtil.queryDeviceBrand(snKey2);
                String resol = HdcUtil.queryParam(snKey2, "const.product.devicetype");
                StringBuilder sb = new StringBuilder();
                if (brand != null && !brand.isEmpty()) sb.append(brand).append(' ');
                if (model != null && !model.isEmpty()) sb.append(model);
                if (sb.length() == 0) sb.append("型号未知");
                if (resol != null && !resol.isEmpty()) sb.append("  ·  ").append(resol.trim());
                final String txt = sb.toString();
                SwingUtilities.invokeLater(() -> {
                    meta.setText(txt);
                    JLabel nm = nameLabels.get(snKey2);
                    if (nm != null && !"HUAWEI".equals(brand)) {
                        // 名字保留产品名
                    }
                });
            });
        }

        card.setToolTipText("设备: " + name + "   IP: " + devIp);
        String key = d.getSn();
        if (key != null) {
            iconLabels.put(key, thumb);
            nameLabels.put(key, n);
        }

        JLabel dot = new JLabel(online ? "●" : "○");
        dot.setForeground(online ? OK : FG_DIM);
        dot.setToolTipText(online ? "在线" : "离线");
        card.add(dot, BorderLayout.EAST);
        if (key != null) {
            dotLabels.put(key, dot);
            if (controlReady) {
                dot.setText("●");
                dot.setForeground(new Color(0x19C37D));
                dot.setToolTipText("在线 · 反控可用");
            }
        }

        card.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { card.setBackground(BG_HOVER); }
            @Override public void mouseExited(MouseEvent e) { card.setBackground(BG_CARD); }
            @Override public void mouseClicked(MouseEvent e) {
                deviceBox.setSelectedItem(d);
                setConnected(true);
                if (e.getClickCount() >= 2) {
                    // 双击：直接进投屏
                    if (capturing || device != null) {
                        teardownCapture();   // 换设备：彻底拆掉旧的再连
                    }
                    startCapture();
                } else {
                    screenView.setHint("已选择 " + d.getSn() + "，双击卡片或点【进入投屏】开始");
                }
            }
        });
        return card;
    }

    private JComponent buildScreenAndBar() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(BG);
        wrap.setBorder(new EmptyBorder(12, 6, 12, 12));

        screenView = new ScreenView();
        // 画面反控：鼠标事件 → SDK 触控（图片流同样支持，控制是独立通道）
        screenView.addKeyListener(pcKeyHandler);
        PhoneWindow.setHoverEnabled(HOVER_ON);
        PhoneWindow.setPcKeyHandler(pcKeyHandler);   // 纯享/迷你窗口也接同一套键盘
        initTypeFlush();
        // 窗口尺寸变化时立刻按新尺寸重排画面（否则静态画面不会跟着变）
        screenView.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                relayoutDisplayBuf();
            }
        });

        screenView.setTouchListener(new ScreenView.TouchListener() {
            @Override public void onDown(int dx, int dy, String button) {
                // 右键=返回、中键=桌面（和原版多屏协同一致；主画面也生效）
                if (HosRemoteDevice.MOUSE_RIGHT.equals(button)) { floatNav(2, "back"); return; }
                if (HosRemoteDevice.MOUSE_MIDDLE.equals(button)) { floatNav(3, "home"); return; }
                Log.info(TAG, "onDown " + dx + "," + dy + " device=" + (device != null));
                HosRemoteDevice dev = device;
                if (dev == null) {
                    // 绿点可能是上次会话残留；这里自动重建控制通道
                    if (!controlBootstrapping) {
                        // 注意：不要在这里置 true，否则 startControlChannel 会判定"已在运行"而直接返回
                        screenView.setHint("反控通道未就绪，正在自动建立…（几秒后再点）");
                        Log.info(TAG, "device null on touch -> bootstrap control channel");
                        SwingUtilities.invokeLater(() -> startControlChannel());
                    } else {
                        screenView.setHint("正在建立反控通道，请稍后重试");
                    }
                    return;
                }
                int[] dc = toDeviceCoord(dx, dy);
                final int ddx = dc[0], ddy = dc[1];
                pressX = ddx; pressY = ddy; moveX = ddx; moveY = ddy; movedFar = false;
                lastSentX = ddx; lastSentY = ddy;
                smoothCurX = ddx; smoothCurY = ddy; stopSmoothSender();
                if (useShellTouch && !controlReady) {
                    shellLastX = ddx; shellLastY = ddy;
                    lastShellSwipeAt = 0;
                    Log.info(TAG, "tap down " + ddx + "," + ddy + " (shell mode)");
                    return;
                }
                touchExec.submit(() -> {
                    try {
                        Log.info(TAG, "touch down " + ddx + "," + ddy + " btn=" + button + " mode=" + (touchMode ? "touch" : "mouse"));
                        if (touchMode && callTouch(dev, "onTouchDown", ddx, ddy)) return;
                        dev.onMouseDown(button == null ? HosRemoteDevice.MOUSE_LEFT : button, ddx, ddy);
                        Log.info(TAG, "touch down ok");
                    } catch (Throwable ex) {
                        Log.info(TAG, "touch down failed: " + ex);
                    }
                });
            }
            @Override public void onMove(int dx, int dy, String button) {
                HosRemoteDevice dev = device;
                if (dev == null) return;
                // PC点击模式：没按键就不发 move —— 设备端就不会有"跟着鼠标跑"的指针
                if (pcClick && button == null) return;
                int[] mc = toDeviceCoord(dx, dy);
                int gx = mc[0], gy = mc[1];
                boolean land = isLandscapeNow();
                // ★ 原生触摸：不加速度（1:1 跟手）；只有 shell 注入才用增益补偿
                double g = useShellTouch ? (land ? landscapeGain : 1.0) : 1.0;
                double effX = useShellTouch ? dragGainX * g : 1.0;
                double effY = useShellTouch ? dragGainY * g : 1.0;
                if (effX != 1.0 || effY != 1.0) {
                    gx = pressX + (int) Math.round((gx - pressX) * effX);
                    gy = pressY + (int) Math.round((gy - pressY) * effY);
                }
                final int mdx = gx, mdy = gy;
                // 只保留最新坐标：EDT/网络慢时丢弃中间位置，跟手更顺
                lastMoveX = mdx; lastMoveY = mdy;
                final int mx = lastMoveX, my = lastMoveY;
                if (useShellTouch && !controlReady) {
                    moveX = mx; moveY = my;
                    if (Math.abs(mx - pressX) > 24 || Math.abs(my - pressY) > 24) movedFar = true;
                    // 不在这里连续注入：单条 hdc 命令约 550ms，连续发会排队 → 越拖越慢 ✗
                    // 只在松手时发一条 swipe（见 onUp），总延迟约 0.6s
                    return;
                }
                if (touchMode) {
                    // 只更新目标点；由 smoothSender 以固定 8ms 节拍均匀外发（真正丝滑的关键）
                    lastSentX = mx; lastSentY = my;
                    ensureSmoothSender();
                    return;
                }
                // ★ 鼠标模式：不再"每个事件都 submit"，改成只留最新坐标 + 单线程泵
                //   （旧实现 touchExec 单线程排队，SDK 调用一慢就积压 → 越动越滞后）
                queueMouseMove(button, mx, my);
            }
            @Override public void onHover(int dx, int dy) {
                hoverMove(dx, dy);
            }
            @Override public void onUp(int dx, int dy, String button) {
                HosRemoteDevice dev = device;
                if (dev == null) return;
                int[] uc = toDeviceCoord(dx, dy);
                int ux0 = uc[0], uy0 = uc[1];
                boolean land2 = isLandscapeNow();
                double g2 = useShellTouch ? (land2 ? landscapeGain : 1.0) : 1.0;
                double effX2 = useShellTouch ? dragGainX * g2 : 1.0;
                double effY2 = useShellTouch ? dragGainY * g2 : 1.0;
                if (effX2 != 1.0 || effY2 != 1.0) {
                    ux0 = pressX + (int) Math.round((ux0 - pressX) * effX2);
                    uy0 = pressY + (int) Math.round((uy0 - pressY) * effY2);
                }
                dx = ux0; dy = uy0;
                final int ux = dx, uy = dy;
                stopSmoothSender();
                if (touchMode && HosRemoteDevice.MOUSE_RIGHT.equals(button)) {
                    // ★ 触控模式：右键 = 系统返回（用 uitest 键，不注入鼠标右键，
                    //   否则设备上会冒出黑色鼠标指针 ✗）
                    touchExec.submit(() -> {
                        try { dev.executeShellCommand("uitest uiInput keyEvent Back", 5); }
                        catch (Throwable ex) { Log.info(TAG, "right-click back failed: " + ex); }
                    });
                    return;
                }
                if (useShellTouch && !controlReady) {
                    final int x1 = pressX, y1 = pressY, x2 = ux, y2 = uy;
                    final boolean far = movedFar || Math.abs(x2 - x1) > 24 || Math.abs(y2 - y1) > 24;
                    final boolean alreadyDragging = lastShellSwipeAt > 0;
                    final String cmd = alreadyDragging
                            ? ("uitest uiInput swipe " + shellLastX + " " + shellLastY + " " + x2 + " " + y2 + " 60")
                            : (far
                                ? ("uitest uiInput swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 800")
                                : ("uitest uiInput click " + x2 + " " + y2));
                    touchExec.submit(() -> {
                        try {
                            String r = shellViaHdc(cmd);
                            if (r.isEmpty() || r.contains("Fail") || r.contains("not match")) {
                                // 退一层：走 SDK 的 shell
                                r = String.valueOf(dev.executeShellCommand(cmd, 3));
                                Log.info(TAG, "shell touch(sdk): " + cmd + " -> " + r);
                            }
                        } catch (Throwable ex) {
                            Log.info(TAG, "shell touch failed: " + ex);
                        }
                    });
                    return;
                }
                touchExec.submit(() -> {
                    if (touchMode) {
                        if (!callTouch(dev, "onTouchUp", ux, uy)) {
                            Log.info(TAG, "onTouchUp failed (touch mode, 不发鼠标事件以免冒出指针)");
                        }
                        return;
                    }
                    try { dev.onMouseUp(button == null ? HosRemoteDevice.MOUSE_LEFT : button, ux, uy); } catch (Exception ex) { }
                });
            }
            private long lastWheelAt = 0;
            private java.util.Timer wheelStopTimer = new java.util.Timer("wheel-stop", true);

            @Override public void onWheel(int dx, int dy, boolean up) {
                HosRemoteDevice dev = device;
                if (dev == null) return;
                // ★ 按 SDK 规范：滚轮就该用 onMouseWheelUp / onMouseWheelDown / onMouseWheelStop
                //   不要自己造滑动 ✗（滑动会触发手机端的惯性滚动 → "一直滚个不停" ✗）
                final int x = dx, y = dy;
                lastWheelAt = System.currentTimeMillis();
                final boolean isTouch = touchMode;
                touchExec.submit(() -> {
                    try {
                        if (isTouch) {
                            // 触控模式：用一次短滑动代替滚轮（不产生鼠标指针）
                            // ★ 夹在安全区：从屏幕边缘起滑会被系统识别成手势（桌面上下滑会锁屏）
                            int span = 120;
                            int safeX = Math.max(60, Math.min(x, (devScreenW > 60 ? devScreenW - 60 : x)));
                            int topY = 140;
                            int botY = devScreenH > 260 ? devScreenH - 180 : y;
                            int cy = Math.max(topY, Math.min(y, botY));
                            int fromY = up ? Math.min(botY, cy + span) : Math.max(topY, cy - span);
                            if (!callTouch(dev, "onTouchDown", safeX, fromY)) return;
                            for (int i = 1; i <= 5; i++) {
                                callTouch(dev, "onTouchMove", safeX, fromY + (cy - fromY) * i / 5);
                            }
                            callTouch(dev, "onTouchUp", safeX, cy);
                            Log.info(TAG, "wheel(touch) " + (up ? "up" : "down") + " @ " + safeX + "," + cy);
                            return;
                        }
                        // 先把指针放到滚动点，再发系统滚轮（SDK 的 Axis 在 PC 形态不动）
                        dev.onMouseMove(null, x, y);
                        queueWheel(up ? -1 : 1);
                    } catch (Throwable t) {
                        Log.info(TAG, "wheel inject failed: " + t);
                    }
                });
                // 150ms 内没有新的滚轮事件 → 发 Stop，结束这次滚动（避免惯性延续）
                try {
                    wheelStopTimer.cancel();
                    wheelStopTimer = new java.util.Timer("wheel-stop", true);
                    wheelStopTimer.schedule(new java.util.TimerTask() {
                        @Override public void run() {
                            if (System.currentTimeMillis() - lastWheelAt < 220) return;
                            if (touchMode) return;   // 触控模式没有鼠标滚轮会话，不用 Stop
                            touchExec.submit(() -> {
                                try { dev.onMouseWheelStop(x, y); } catch (Throwable ignored) { }
                            });
                        }
                    }, 230);
                } catch (Throwable ignored) { }
            }
        });
        wrap.add(screenView, BorderLayout.CENTER);
        iconBarRef = buildIconBar();
        wrap.add(iconBarRef, BorderLayout.SOUTH);
        return wrap;
    }

    private JComponent buildIconBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                new EmptyBorder(8, 12, 8, 12)));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        left.setOpaque(false);

        enterBtn = primaryButton("进入投屏");
        enterBtn.setToolTipText("开始投屏 / 停止投屏（同一时间只建议连一台设备）");
        enterBtn.addActionListener(e -> {
            if (capturing) {
                stopCapture();                 // 暂停
            } else if (device != null && stopped) {
                // 继续：只解除暂停，绝不能改 captureGen
                // （帧回调里有 myGen != captureGen 校验，改了代数会让所有帧被丢弃）
                stopped = false;
                capturing = true;
                lastFrameAt = 0;
                enterBtn.setText("停止投屏");
                screenView.setHint("");
                Log.info(TAG, "resume capture (reuse connection)");
            } else {
                startCapture();                // 首次或换设备后
            }
        });
        left.add(enterBtn);
        left.add(Box.createHorizontalStrut(10));

        left.add(barButton("⏻", "电源", () -> keyPress(18)));
        left.add(barButton("⟳", "重启", () -> shell("reboot")));
        left.add(barButton("🔊+", "音量+", () -> keyPress(16)));
        left.add(barButton("🔊-", "音量-", () -> keyPress(17)));
        left.add(barButton("🔌", "强制断开 hdc（停止服务、释放 8710 端口）", this::forceHdcDisconnect));
        left.add(barButton("←", "返回", () -> shell("uinput -K -d 2 -u 2")));
        left.add(barButton("⏏", "释放当前设备（切换设备前用）", () -> releaseAll("手动释放")));
        left.add(barButton("⌨", "键盘输入", this::showKeyboardBar));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.setOpaque(false);
        frameInfo = new JLabel("—");
        frameInfo.setFont(new Font("Consolas", Font.PLAIN, 11));
        frameInfo.setForeground(FG_DIM);
        right.add(frameInfo);

        // 硬解状态：HW(绿)=D3D11VA 硬解，SW(灰)=软件解码
        hwLabel = new JLabel("SW");
        hwLabel.setFont(new Font("Consolas", Font.BOLD, 11));
        hwLabel.setForeground(FG_DIM);
        hwLabel.setToolTipText("解码方式：HW=D3D11VA 硬解，SW=软件解码");
        hwLabel.setBorder(new EmptyBorder(0, 6, 0, 8));
        right.add(hwLabel);

        spark = new FpsSparkline(120, 26);
        right.add(spark);

        ageLabel = new JLabel("");
        ageLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        ageLabel.setForeground(FG_DIM);
        right.add(ageLabel);

        // 每秒刷新一次"无数据"提示，代替延迟显示
        ageTimer = new javax.swing.Timer(1000, e -> { updateAgeLabel(); stallWatchdog(); });
        ageTimer.start();

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        // 底栏空白处可拖动窗口（缩放后顶栏被遮住时还能移动窗口）
        // ★ JLabel / FpsSparkline 也会吃掉鼠标事件，必须逐个装，否则"看着是空白、其实点不动"
        installBarDrag(bar);
        installBarDrag(left);
        installBarDrag(right);
        installBarDrag(frameInfo);
        installBarDrag(hwLabel);
        installBarDrag(spark);
        installBarDrag(ageLabel);
        return bar;
    }

    /** 给一个组件装上"按住空白处拖窗口"的能力（按钮会自己吃掉事件，不受影响） */
    private void installBarDrag(JComponent c) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        final java.awt.Point[] st = new java.awt.Point[2];   // [0]=按下时的屏幕坐标 [1]=按下时的窗口位置
        c.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    st[0] = e.getLocationOnScreen();
                    st[1] = getLocation();
                }
            }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                st[0] = null;
            }
        });
        c.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (st[0] == null || st[1] == null) return;
                java.awt.Point now = e.getLocationOnScreen();
                setLocation(st[1].x + (now.x - st[0].x), st[1].y + (now.y - st[0].y));
            }
        });
    }

    // ---------------- 输入模式（触控 / 鼠标 / PC点击） ----------------
    /** m: "touch" | "mouse" | "pc" */
    private void setInputMode(String m) {
        if ("mouse".equals(m)) { touchMode = false; pcClick = false; }
        else if ("pc".equals(m)) { touchMode = false; pcClick = true; }
        else { touchMode = true; pcClick = false; }
        updateModeUi();
        PhoneWindow pw = phoneWin;
        if (pw != null) pw.showToast("输入模式：" + currentModeText());
        Log.info(TAG, "input mode = " + (touchMode ? "touch" : (pcClick ? "pc-click" : "mouse")));
    }

    private String currentModeText() {
        return touchMode ? "触控（无指针）"
                : (pcClick ? "PC点击（点按用鼠标，不跟随）" : "鼠标（指针跟随）");
    }

    private void updateModeUi() {
        if (modeLabel != null) {
            modeLabel.setText(touchMode ? "触控" : (pcClick ? "PC点击" : "鼠标"));
            modeLabel.setForeground(touchMode ? FG_DIM : new Color(0x19C37D));
        }
        if (screenView != null) {
            screenView.setHint("输入模式：" + currentModeText());
        }
        if (phoneWin != null) phoneWin.setLocalHover(!touchMode);
    }

    // ---------------- SDK 接线 ----------------
    /** 自检：把运行环境、hdc、设备、状态全查一遍，结果弹框显示（全部在后台线程做） */
    private void selfCheck() {
        screenView.setHint("自检中…");
        bgExec.submit(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("== 版本 ==\n");
            sb.append("  HOScrcpy Sun v").append(VERSION).append("\n");
            sb.append("  Java ").append(System.getProperty("java.version"))
              .append("  arch=").append(System.getProperty("os.arch")).append("\n");
            sb.append("  工作目录 ").append(System.getProperty("user.dir")).append("\n\n");

            sb.append("== 运行文件 ==\n");
            java.io.File dir = new java.io.File(System.getProperty("user.dir"));
            for (String name : new String[]{"hdc.exe", "libusb_shared.dll", "HOScrcpy.jar"}) {
                java.io.File f = new java.io.File(dir, name);
                sb.append("  ").append(String.format("%-20s", name))
                  .append(f.exists() ? "✓ " + f.length() + " 字节" : "✗ 缺失").append("\n");
            }
            sb.append("\n");

            sb.append("== hdc ==\n");
            try {
                String v = new ProcessExecutor().callProcess("hdc -v", 6);
                sb.append("  版本: ").append(v == null ? "(无输出)" : v.trim().split("\n")[0]).append("\n");
            } catch (Exception e) {
                sb.append("  版本: 调用失败 ").append(e.getMessage()).append("\n");
            }
            try {
                String t = new ProcessExecutor().callProcess("hdc list targets", 10);
                sb.append("  设备: ").append(t == null || t.trim().isEmpty() ? "(空)" : t.trim().replace("\n", " | ")).append("\n");
            } catch (Exception e) {
                sb.append("  设备: 调用失败 ").append(e.getMessage()).append("\n");
            }
            sb.append("\n");

              sb.append("== 推流 ==\n");
              sb.append("  tcpStreaming = ").append(tcpStreaming).append("\n");
              sb.append("  codecLabel   = ").append(codecLabel).append("\n");
              sb.append("  tcpFrames    = ").append(tcpFrameCount).append("\n");
              sb.append("\n");
            sb.append("== 会话状态 ==\n");
            sb.append("  connecting = ").append(connecting).append("\n");
            sb.append("  capturing  = ").append(capturing).append("\n");
            sb.append("  stopped    = ").append(stopped).append("\n");
            sb.append("  captureGen = ").append(captureGen).append("\n");
            sb.append("  device     = ").append(device == null ? "null" : "已连接").append("\n");
            sb.append("  frames     = ").append(frameCount).append("\n");
            long age = lastFrameAt == 0 ? -1 : (System.currentTimeMillis() - lastFrameAt);
            sb.append("  最后一帧   = ").append(age < 0 ? "还没收到" : age + " ms 前").append("\n\n");

            sb.append("== 性能（最近 1 秒） ==\n");
            sb.append(String.format("  解码耗时 = %.1f ms（EWMA）%n", hudDecodeMs));
            sb.append(String.format("  帧间隔抖动 = %.1f ms%n", hudJitterMs));
            sb.append(String.format("  顿挫 = %d 次/秒（帧间隔 > 250ms）%n", hudGaps));
            sb.append(String.format("  码率 = %.1f Mbps%n", hudMbps));
            sb.append(String.format("  自动重连 = %s%s%n",
                    autoReconnecting ? "进行中" : (autoReconnectGaveUp ? "已放弃（窗口恢复会重试）" : "空闲"),
                    autoReconnecting ? "" : ""));
            sb.append("\n");
            sb.append("== 内存 ==\n");
            Runtime rt = Runtime.getRuntime();
            sb.append("  已用 ").append((rt.totalMemory() - rt.freeMemory()) / 1048576)
              .append(" MB / 上限 ").append(rt.maxMemory() / 1048576).append(" MB\n");

            String text = sb.toString();
            Log.info(TAG, "self check:\n" + text);
            SwingUtilities.invokeLater(() -> {
                screenView.setHint("");
                JTextArea area = new JTextArea(text, 22, 58);
                area.setEditable(false);
                area.setFont(new Font("Consolas", Font.PLAIN, 12));
                area.setBackground(new Color(0x1B1E24));
                area.setForeground(FG);
                JScrollPane sp = new JScrollPane(area);
                JOptionPane.showMessageDialog(this, sp, "自检报告", JOptionPane.INFORMATION_MESSAGE);
            });
        });
    }

    /** 推流看门狗：Sender 卡住（不再发数据）时自动重连自愈 */
    private void tcpWatchdog() {
        if (!tcpStreaming || tcpSocket == null) return;
        long now = System.currentTimeMillis();
        long idle = now - lastDataAt;
        // 画面完全静止时本来就没有数据，所以只在"很久没数据"时才重连
        if (idle >= 10000 && idle < 12000) {
            SwingUtilities.invokeLater(() -> screenView.setHint("长时间没有新画面（屏幕静止？还是推流端卡住？）"));
        }
        if (idle < 25000) return;
        Log.info(TAG, "watchdog: idle " + (idle / 1000) + "s, force reconnect");
        final String addr = tcpSocket.getInetAddress().getHostAddress();
        final int port = tcpSocket.getPort();
        final boolean hevc = !"H.264".equals(codecBox == null ? "H.265" : codecBox.getSelectedItem());
        stopTcpStream();
        final int gen = ++captureGen;
        scheduleTcpReconnect(addr, port, hevc, gen, 1);
    }

    /** 转储目录：优先 -Dhoscrcpy.dumpdir（可指到网络共享），否则工作目录\naldump */
    private java.io.File dumpDirFile() {
        String p = System.getProperty("hoscrcpy.dumpdir");
        return (p != null && !p.isEmpty()) ? new java.io.File(p)
                : new java.io.File(System.getProperty("user.dir"), "naldump");
    }

    /** 窗口尺寸变化后，用最近一帧重新生成 displayBuf（不依赖新帧到达） */
    private void relayoutDisplayBuf() {
        java.awt.image.BufferedImage src = screenView.getShownImage();
        if (src == null) return;
        int vw = screenView.getWidth(), vh = screenView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        int sw = src.getWidth(), sh = src.getHeight();
        double ar = 1.0 * sw / sh;
        int dw = (int) Math.min(vw - 12, ar * (vh - 12));
        int dh = (int) (dw / ar);
        if (dw <= 0 || dh <= 0) return;
        java.awt.image.BufferedImage buf = new java.awt.image.BufferedImage(dw, dh,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = buf.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, dw, dh, null);
        g.dispose();
        screenView.setDisplay(buf, new java.awt.Rectangle((vw - dw) / 2, (vh - dh) / 2, dw, dh));
        screenView.repaint();
    }

    /** 彻底复位所有推流状态（重连前调用，避免旧线程/旧解码器卡死导致必须重启软件） */
    /** 画质切换：SDK 的 setScale 只在建 device 时生效 → 自动重连一次让新画质生效 */
    /** 主动刷新设备真实屏幕尺寸（折叠/展开、转屏后必须刷新，否则触摸坐标按旧尺寸算 → 点不准） */
    private void refreshDeviceSize() {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        try {
            com.huawei.hosscrcpy.api.Size sz = dev.getScreenSize(true);   // true = 重新读取
            if (sz != null && sz.width > 0 && sz.height > 0) {
                if (sz.width != devScreenW || sz.height != devScreenH) {
                    Log.info(TAG, "device size refreshed: " + devScreenW + "x" + devScreenH
                            + " -> " + sz.width + "x" + sz.height + "（折叠/转屏）");
                }
                devScreenW = sz.width;
                devScreenH = sz.height;
                haveDeviceSize = true;
                navBarCache = null;      // 尺寸变了，导航栏坐标作废
            }
        } catch (Throwable t) {
            Log.info(TAG, "refreshDeviceSize failed: " + t);
        }
    }

    /** 画质/码率切换：SDK 的 setScale/setBitRate 只在建 device 时生效 → 必须重建视频 device。
     *  ★ 0.136.22：只重启"视频采集"，**不再 releaseAll、不再重建控制通道**。
     *  旧实现会 releaseAll（清 controlReady/useShellTouch）+ 之后 startControlChannel()，
     *  而 startControlChannel 会把 `device` 换成 5fps/1.0 scale 的控制设备 →
     *  视频在新设备上起不来，实测切「原始」直接掉线 15 秒（can not found device）。 */
    private void onQualityChanged() {
        if (device == null || connecting) return;
        String q = String.valueOf(qualityBox.getSelectedItem());
        Log.info(TAG, "quality changed to " + q + " -> 只重启采集（保留反控通道）");
        screenView.setHint("画质切换为 " + q + "，正在重启画面…");
        bgExec.submit(() -> {
            // 1) 只停视频采集（device / 原生触摸都不动）
            try { if (device != null) device.stopCaptureScreen(); } catch (Throwable ignored) { }
            teardownCapture();
            try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
            // 2) 重建 device 让新画质生效；不再碰控制通道
            startCapture();
        });
    }

    /** 对齐到系统"多屏协同"窗口的位置和大小（在外框上覆盖绘制） */
    /** 查询系统"多屏协同"窗口的物理像素位置和尺寸；找不到返回 null。
     *  脚本里已 SetProcessDPIAware，坐标和 Java(DPI-aware) 一致。 */
    private java.awt.Rectangle queryMirrorWindow() {
        try {
            java.io.File script = new java.io.File("找多屏协同窗口.ps1");
            if (!script.isFile()) {
                Log.info(TAG, "mirror script missing: " + script);
                return null;
            }
            String out = new ProcessExecutor().callProcess(new String[]{
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath()}, 15);
            Log.info(TAG, "mirror windows:\n" + out);
            if (out == null || out.trim().isEmpty()) return null;
            // 第一行：Title \t Class \t X \t Y \t W \t H
            String line = out.trim().split("\r?\n")[0];
            String[] p = line.split("\t");
            if (p.length < 6) return null;
            return new java.awt.Rectangle(
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                    Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()));
        } catch (Throwable t) {
            Log.info(TAG, "queryMirrorWindow failed: " + t);
            return null;
        }
    }

    private void alignToMirrorWindow() {
        bgExec.submit(() -> {
            try {
                java.awt.Rectangle r = queryMirrorWindow();
                if (r == null) {
                    SwingUtilities.invokeLater(() -> screenView.setHint("没找到多屏协同窗口（先打开系统的多屏协同）"));
                    return;
                }
                Log.info(TAG, "align to mirror -> " + r.x + "," + r.y + " " + r.width + "x" + r.height);
                SwingUtilities.invokeLater(() -> {
                    setLocation(r.x, r.y);
                    setSize(r.width, r.height);
                    validate();
                    screenView.setHint("已对齐到多屏协同窗口（" + r.width + "x" + r.height + "）");
                });
            } catch (Throwable t) {
                Log.info(TAG, "alignToMirrorWindow failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("对齐失败：" + t));
            }
        });
    }

    /** 退出程序：释放设备 → 保存设置 → 关窗 → 结束进程 */
    private void appExit() {
        int r = JOptionPane.showConfirmDialog(this, "确定退出 HOScrcpy？\n（会停止投屏并释放设备）",
                "退出", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        Log.info(TAG, "user requested exit");
        stopped = true;
        captureGen++;
        try { releaseAll("退出程序"); } catch (Throwable ignored) { }
        try { stopCaptureQuietly(); } catch (Throwable ignored) { }
        try { savePrefs(); } catch (Throwable ignored) { }
        if (ageTimer != null) ageTimer.stop();
        dispose();
        System.exit(0);
    }

    /** 一键释放：彻底断开当前设备（切设备前必须先调，否则旧的占着 hdc/SDK 会连不上新的） */
    /** 强制断开 hdc：停掉本机 hdc 服务（所有连接一起断，8710 端口释放） */
    private void forceHdcDisconnect() {
        Log.info(TAG, "force hdc disconnect requested");
        try { releaseAll("手动断开 hdc"); } catch (Throwable ignored) { }
        bgExec.submit(() -> {
            try {
                new utils.ProcessExecutor().callProcess("hdc kill", 5);
                Log.info(TAG, "hdc kill done (manual)");
                SwingUtilities.invokeLater(() -> screenView.setHint("hdc 已断开（服务已停止、端口已释放）"));
            } catch (Throwable t) {
                Log.info(TAG, "hdc kill failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("断开失败: " + t));
            }
        });
    }

    private void releaseAll(String why) {
        Log.info(TAG, "releaseAll: " + why);
        autoReconnecting = false;      // 用户主动释放/切换：停掉自动重连
        capturing = false;
        connecting = false;
        controlReady = false;
        useShellTouch = true;
        try { teardownCapture(); } catch (Throwable t) { Log.info(TAG, "teardown: " + t); }
        try { resetAllStreamState(); } catch (Throwable t) { Log.info(TAG, "reset: " + t); }
        HosRemoteDevice old = device;
        device = null;
        activeSn = null;
        if (old != null) {
            try { old.stopCaptureScreen(); } catch (Throwable ignored) { }
            try { old.stopImageScreenCapture(); } catch (Throwable ignored) { }
        }
        frameCount = 0; rawPackets = 0;
        devScreenW = 0; devScreenH = 0; haveDeviceSize = false;
        captureGen++;                       // 让所有在途回调失效
        SwingUtilities.invokeLater(() -> {
            if (enterBtn != null) enterBtn.setText("进入投屏");
            screenView.setHint("已释放：" + why);
            setConnected(false);
        });
    }

    private void resetAllStreamState() {
        Log.info(TAG, "resetAllStreamState");
        stopTcpStream();
        
        try { stopDecodeThread(); } catch (Throwable ex) { Log.info(TAG, "ignored: " + ex); }
        decodeRunning = false;
        decodeThread = null;
        if (decoder != null) {
            
            try { decoder.close(); } catch (Throwable ex) { Log.info(TAG, "ignored: " + ex); }
            decoder = null;
        }
        
        try { h264Queue.clear(); } catch (Throwable ex) { Log.info(TAG, "ignored: " + ex); }
        auBuf.clear();
        auHasVcl = false;
        psVps = null;
        psSps = null;
        psPps = null;
        needParamSets = true;
        waitingIdr = true;
        tcpStreaming = false;
        lastDataAt = 0;
        tcpFrameCount = 0;
        nalDumpCount = 0;
        dumpDecoded = 0;
        frameCount = 0;
        lastFrameAt = 0;
        reconnectTried = false;
    }

    /** 色度校正：YUV601 下交换 U/V 再回 RGB 的等效仿射矩阵（修"蓝色变橙"）
     *  已验证：流里的 (183,108,0) → (25,150,200) ≈ 真值蓝 (25,118,210) */
    private java.awt.image.BufferedImage fixChroma(java.awt.image.BufferedImage src) {
        try {
            if (swapOp == null) {
                // UV 交换（YUV601）等效矩阵：只改色度，不动亮度
                // 实测平均误差 11.6/255；白/黑不变（不会发灰）
                float[][] m = {
                        {0.0621f, 0.1229f, 0.8150f, 0f},
                        {0.2477f, 0.9675f, -0.2151f, 0f},
                        {1.1850f, -0.1555f, -0.0295f, 0f}
                };
                swapOp = new java.awt.image.BandCombineOp(m, null);
            }
            java.awt.image.WritableRaster srcR = src.getRaster();
            java.awt.image.WritableRaster dstR = srcR.createCompatibleWritableRaster();
            swapOp.filter(srcR, dstR);
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                    src.getWidth(), src.getHeight(), src.getType());
            out.setData(dstR);
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 使用说明 */
    private void showHelp() {
        String h = "<html><body style='width:430px;font-size:11px'>"
            + "<b>三种用法</b><br><br>"
            + "<b>1. 常规（H.264，hdc）</b><br>"
            + "&nbsp;&nbsp;点 <b>+</b> 输入 <i>设备IP:hdc端口</i> → 双击设备卡片<br><br>"
            + "<b>2. 高清（H.265，推流）★推荐</b><br>"
            + "&nbsp;&nbsp;手机/平板装 HarmonyOS-Sender → 选 H.265 → 开始推流 → 选屏幕<br>"
            + "&nbsp;&nbsp;sun 点 <b>📡</b> 输入 <i>设备IP:5000</i>，编码下拉选 <b>H.265</b><br><br>"
            + "<b>3. 推流 + 反控（最完整）</b><br>"
            + "&nbsp;&nbsp;先按 2 出画面，再点 <b>+</b> 连 hdc，然后双击设备卡片<br>"
            + "&nbsp;&nbsp;（推流模式下双击只建立控制通道，不会抢走画面）<br><br>"
            + "<b>快捷键</b><br>"
            + "&nbsp;&nbsp;F11 全屏 &nbsp; Esc 退出全屏/浮窗 &nbsp; Ctrl+V 粘贴文本<br>"
            + "&nbsp;&nbsp;右键 返回 &nbsp; 中键 回桌面 &nbsp; 滚轮 滚动<br><br>"
            + "<b>常见问题</b><br>"
            + "&nbsp;&nbsp;· 画面静止不刷新 → 正常，推流端只在屏幕变化时出帧<br>"
            + "&nbsp;&nbsp;· 花屏/绿屏 → Sender 里换 H.264 再试<br>"
            + "&nbsp;&nbsp;· 连不上 → 确认 Sender 已点“开始推流”并过了系统授权框<br>"
            + "</body></html>";
        JOptionPane.showMessageDialog(this, h, "使用说明", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 直连推流：连 Sender 的 TCP，收 Annex-B 码流并用 FFmpeg 解码 */
    private void promptStreamConnect() {
              String input = askStreamAddr();
          if (input == null || input.trim().isEmpty()) return;
          final String addr = input.trim();
          rememberStreamAddr(addr);

        int idx = addr.lastIndexOf(':');
        if (idx <= 0) { screenView.setHint("格式应为 IP:端口"); return; }
        final String host = addr.substring(0, idx);
        final int port;
        try { port = Integer.parseInt(addr.substring(idx + 1).trim()); }
        catch (Exception ex) { screenView.setHint("端口不是数字"); return; }

        final boolean useHevc = !"H.264".equals(codecBox == null ? "H.265" : codecBox.getSelectedItem());
        resetAllStreamState();
        stopped = false;
        capturing = true;
        connecting = false;
        final int myGen = ++captureGen;
        warmupUntil = System.currentTimeMillis() + 2500;
        screenView.setHint("正在连接推流端 " + host + ":" + port + (useHevc ? "（H.265）" : "（H.264）"));
        setConnected(true);

        if (decoder != null) { try { decoder.close(); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); } }
        decoder = new FfmpegDecoder();
        codecLabel = useHevc ? "H.265 推流" : "H.264 推流";
        auIsHevc = useHevc;
        auBuf.clear();
        auHasVcl = false;
        psVps = null; psSps = null; psPps = null;
        needParamSets = true;
        waitingIdr = true;
        if (useHevc) decoder.initAsHevc(); else decoder.init();
        startDecodeThread(decoder, myGen);

        tcpStreaming = true;
        lastDataAt = System.currentTimeMillis();
        tcpThread = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            try (java.net.Socket sock = new java.net.Socket()) {
                tcpSocket = sock;
                sock.connect(new java.net.InetSocketAddress(host, port), 5000);
                streamHost = host;
                // 先发配对码（服务端开启校验时必须，4 字节）
                String authCode = PREF.get("authCode", "");
                if (authCode != null && authCode.trim().length() >= 4) {
                    try {
                        sock.getOutputStream().write(authCode.trim().substring(0, 4).getBytes("UTF-8"));
                        sock.getOutputStream().flush();
                        Log.info(TAG, "auth code sent");
                    } catch (Exception e) {
                        Log.info(TAG, "auth code send failed: " + e);
                    }
                }
                // 反控前提：需要有 HosRemoteDevice（内部用 hdc/uitest 注入触摸）。
                // 只用 📡 连了流、没选设备时，这里补建一次控制通道。
                if (device == null) {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            Log.info(TAG, "tcp connected but no device -> start control channel");
                            startCapture();
                        } catch (Throwable t) {
                            Log.info(TAG, "auto control channel failed: " + t);
                        }
                    });
                }
                sock.setTcpNoDelay(true);
                sock.setReceiveBufferSize(1 << 18);
                Log.info(TAG, "stream connected " + host + ":" + port);
                // 读握手头：SUNHDR <w> <h>\n（发送端会先发这一行）
                try {
                    sock.setSoTimeout(1500);
                    java.io.InputStream hs = sock.getInputStream();
                    java.io.ByteArrayOutputStream hb = new java.io.ByteArrayOutputStream();
                    int c;
                    int guard = 0;
                    while ((c = hs.read()) != -1 && guard++ < 64) {
                        if (c == '\n') break;
                        hb.write(c);
                    }
                    String header = hb.toString("UTF-8").trim();
                    Log.info(TAG, "stream header: " + header);
                    if (header.startsWith("SUNHDR")) {
                        String[] p3 = header.split("\\s+");
                        if (p3.length >= 3) {
                            devScreenW = Integer.parseInt(p3[1]);
                            devScreenH = Integer.parseInt(p3[2]);
                            haveDeviceSize = true;
                            Log.info(TAG, "device screen from header = " + devScreenW + "x" + devScreenH);
                        }
                    }
                } catch (Throwable t) {
                    Log.info(TAG, "read header failed (old sender?): " + t);
                } finally {
                    try { sock.setSoTimeout(0); } catch (Throwable ignored) { }
                }
                SwingUtilities.invokeLater(() -> screenView.setHint("已连接，等待参数集…"));
                new Thread(() -> {
                    
                    try { Thread.sleep(3000); } catch (InterruptedException ex) { Log.info(TAG, "ignored: " + ex); }
                    if (myGen == captureGen && System.currentTimeMillis() - lastDataAt >= 2900) {
                        SwingUtilities.invokeLater(() -> screenView.setHint(
                                "已连接但没收到画面：请确认手机端已点【开始推流】并通过授权"));
                    }}, "tcp-firstframe-hint").start();   // ★ 漏了 .start() 这段提示永远不会出现
                java.io.InputStream in = sock.getInputStream();
                while (tcpStreaming && myGen == captureGen) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    lastDataAt = System.currentTimeMillis();
                    acc.write(buf, 0, n);
                    splitAndFeedNals(acc, myGen);
                }
                Log.info(TAG, "stream closed by peer");
            } catch (Exception ex) {
                // 本地主动关闭（重连/停止）导致的 Socket closed 不算错误
                String raw = ex.getMessage() == null ? "" : ex.getMessage();
                if (raw.contains("Socket closed")) {
                    Log.info(TAG, "stream closed locally");
                    return;
                }
                Log.error(TAG, "stream error", ex);
                final String msg = friendlyNetError(ex);
                SwingUtilities.invokeLater(() -> screenView.setHint("推流中断：" + msg));
                // 意外断开 → 自动重连（最多 3 次，间隔 2 秒）
                if (myGen == captureGen && !stopped) {
                    scheduleTcpReconnect(host, port, useHevc, myGen, 1);
                }
            } finally {
                SwingUtilities.invokeLater(() -> setConnected(false));
            }
        }, "tcp-stream");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    /** 把当前 AU 的所有 NAL 拼成一包送给解码器 */
    private void feedAu() {
        if (auBuf.isEmpty()) return;
        // 首帧必须是 IDR（H.265:19/20, H.264:5），否则解码器缺参考帧会花屏
        if (waitingIdr) {
            boolean hasIdr = false;
            for (byte[] n : auBuf) {
                int p = 0;
                while (p + 3 < n.length && !(n[p] == 0 && n[p + 1] == 0 && n[p + 2] == 1)) p++;
                int off = (p + 3 < n.length) ? p + 3 : -1;
                if (off < 0 || off >= n.length) continue;
                int b0 = n[off] & 0xFF;
                int hevcType = (b0 >> 1) & 0x3F;
                int avcType = b0 & 0x1F;
                if (auIsHevc ? (hevcType == 19 || hevcType == 20) : (avcType == 5)) {
                    hasIdr = true;
                    break;
                }
            }
            if (!hasIdr) {
                auBuf.clear();
                auHasVcl = false;
                return;
            }
            waitingIdr = false;
            Log.info(TAG, "idr received, start decoding");
        }
        java.util.List<byte[]> parts = new java.util.ArrayList<>();
        if (needParamSets) {
            if (psVps != null) parts.add(psVps);
            if (psSps != null) parts.add(psSps);
            if (psPps != null) parts.add(psPps);
            needParamSets = false;
        }
        parts.addAll(auBuf);
        int total = 0;
        for (byte[] n : parts) total += n.length;
        byte[] au = new byte[total];
        int pos = 0;
        for (byte[] n : parts) {
            System.arraycopy(n, 0, au, pos, n.length);
            pos += n.length;
        }
        auBuf.clear();
        auHasVcl = false;
        enqueuePkt(au);
    }

    private static String firstBytes(byte[] a, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, a.length); i++) {
            sb.append(String.format("%02X ", a[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    /** 把网络异常翻译成人话 */
    private static String friendlyNetError(Exception ex) {
        String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (ex instanceof java.net.ConnectException) return "对方没在监听（Sender 没点开始推流？端口不对？）";
        if (ex instanceof java.net.SocketTimeoutException) return "连接超时（IP 不通/网段不同）";
        if (m.contains("Connection reset")) return "对方强制断开（手机端停止推流/切后台被杀）";
        if (m.contains("Broken pipe")) return "网络断了（Wi-Fi 抖动/手机休眠）";
        if (ex instanceof java.net.UnknownHostException) return "IP 解析失败（填错了？）";
        return m;
    }

    /** 意外断开后自动重连 */
    private void scheduleTcpReconnect(final String host, final int port, final boolean useHevc,
                                      final int myGen, final int attempt) {
        if (attempt > 20 || myGen != captureGen || stopped) return;
        SwingUtilities.invokeLater(() ->
                screenView.setHint("连接断开，正在自动重连（第 " + attempt + " 次）…"));
        Log.info(TAG, "auto reconnect #" + attempt + " -> " + host + ":" + port);
        bgExec.submit(() -> {
            
            try {
                // 第一次重连要快（转屏/重建导致的短暂断开），后面逐步放慢
                Thread.sleep(attempt <= 1 ? 300 : 1000);
            } catch (InterruptedException ex) { Log.info(TAG, "ignored: " + ex); }
            if (myGen != captureGen || stopped) return;
            stopTcpStream();                     // 先关旧的，再连新的
            try (java.net.Socket sock = new java.net.Socket()) {
                sock.connect(new java.net.InetSocketAddress(host, port), 5000);
                streamHost = host;
                sock.setTcpNoDelay(true);
                tcpSocket = sock;
                final int gen2 = ++captureGen;
                tcpStreaming = true;
                lastDataAt = System.currentTimeMillis();
                waitingIdr = true;      // 重连后也要等 IDR
                auBuf.clear();
                auHasVcl = false;
                needParamSets = true;
                SwingUtilities.invokeLater(() -> screenView.setHint("重连成功"));
                Log.info(TAG, "auto reconnect ok");
                // 复用同一套读取逻辑
                if (decoder != null) { try { decoder.close(); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); } }
                decoder = new FfmpegDecoder();
                if (useHevc) decoder.initAsHevc(); else decoder.init();
                startDecodeThread(decoder, gen2);
                java.io.InputStream in = sock.getInputStream();
                java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                while (tcpStreaming && gen2 == captureGen) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    lastDataAt = System.currentTimeMillis();
                    acc.write(buf, 0, n);
                    splitAndFeedNals(acc, gen2);
                }
            } catch (Exception ex) {
                Log.error(TAG, "reconnect failed #" + attempt, ex);
                scheduleTcpReconnect(host, port, useHevc, myGen, attempt + 1);
            }
        });
    }

    /** 按 00 00 01 / 00 00 00 01 切 NAL，逐个喂解码器 */
    private void splitAndFeedNals(java.io.ByteArrayOutputStream acc, int myGen) {
        byte[] data = acc.toByteArray();
        if (data.length < 8) return;
        java.util.List<int[]> starts = new java.util.ArrayList<>();
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                starts.add(new int[]{i, (i > 0 && data[i - 1] == 0) ? i - 1 : i});
                i += 2;
            }
        }
        if (starts.size() < 2) return;             // 还没拿到完整 NAL
        int lastStart = starts.get(starts.size() - 1)[1];
        for (int k = 0; k + 1 < starts.size(); k++) {
            int from = starts.get(k)[1];
            int to = starts.get(k + 1)[1];
            if (to <= from) continue;
            byte[] nal = java.util.Arrays.copyOfRange(data, from, to);
            if (nalDumpCount < 8) {
                nalDumpCount++;
                int p = 0;
                while (p + 3 < nal.length && !(nal[p] == 0 && nal[p + 1] == 0 && nal[p + 2] == 1)) p++;
                int off = (p + 3 < nal.length) ? p + 3 : 0;
                int b0 = (off < nal.length) ? (nal[off] & 0xFF) : -1;
                int hevcType = (b0 >= 0) ? ((b0 >> 1) & 0x3F) : -1;
                int avcType = (b0 >= 0) ? (b0 & 0x1F) : -1;
                Log.info(TAG, "nal#" + nalDumpCount + " len=" + nal.length
                        + " firstBytes=" + firstBytes(nal, 8)
                        + " hevcType=" + hevcType + " avcType=" + avcType);
                try {
                    java.io.File dir = dumpDirFile();
                    if (!dir.exists()) dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, "nal-" + nalDumpCount + ".bin").toPath(), nal);
                } catch (Exception ex) {
                    Log.info(TAG, "ignored: " + ex);
                }
            }
            // ---- Access Unit 组包 ----
            int p2 = 0;
            while (p2 + 3 < nal.length && !(nal[p2] == 0 && nal[p2 + 1] == 0 && nal[p2 + 2] == 1)) p2++;
            int off2 = (p2 + 3 < nal.length) ? p2 + 3 : 0;
            int b = (off2 < nal.length) ? (nal[off2] & 0xFF) : -1;
            int hevcType = (b >= 0) ? ((b >> 1) & 0x3F) : -1;
            int avcType = (b >= 0) ? (b & 0x1F) : -1;
            boolean isVcl = auIsHevc ? (hevcType >= 0 && hevcType <= 31)
                                     : (avcType >= 1 && avcType <= 5);
            // ★ 关键：HEVC 一帧可能有多个 slice，只有 first_slice_segment_in_pic_flag=1
            //   才是新图像的开始；否则是同一帧的后续 slice，不能切开
            boolean newPicture = true;
            if (isVcl) {
                int hdrLen = 2;                       // NAL header
                int bitPos = off2 + hdrLen;           // slice header 起点
                if (auIsHevc) {
                    newPicture = bitPos < nal.length && ((nal[bitPos] & 0x80) != 0);
                } else {
                    // AVC: first_mb_in_slice 是 ue(v)，为 0 时首字节为 0x80 形式
                    newPicture = bitPos < nal.length && ((nal[bitPos] & 0x80) != 0);
                }
            }
            // 参数集单独缓存（不进 AU，避免同一帧里重复塞多份）
            boolean isParamSet = auIsHevc
                    ? (hevcType == 32 || hevcType == 33 || hevcType == 34)
                    : (avcType == 7 || avcType == 8);
            if (isParamSet) {
                if (!auIsHevc) {
                    if (avcType == 7) psSps = nal; else psPps = nal;
                } else if (hevcType == 32) {
                    psVps = nal;
                } else if (hevcType == 33) {
                    psSps = nal;
                } else {
                    psPps = nal;
                }
                needParamSets = true;      // 参数集更新 → 下一帧要带上
                continue;                   // 不加入 AU
            }

            if (isVcl && newPicture && auHasVcl && !auBuf.isEmpty()) {
                feedAu();       // 上一帧结束，送出去
            }
            auBuf.add(nal);
            if (isVcl) auHasVcl = true;
            if (auBuf.size() > 64) feedAu();   // 防御
        }
        acc.reset();
        acc.write(data, lastStart, data.length - lastStart);
    }

    private void stopTcpStream() {
        tcpStreaming = false;
        java.net.Socket sock = tcpSocket;
        tcpSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }   // 关闭才能让阻塞的 read 退出
        }
        Thread t = tcpThread;
        tcpThread = null;
        if (t != null) t.interrupt();
    }

    /** 连接设备：弹框输入 IP:端口，后台执行 hdc tconn，再刷新列表 */
    private void promptConnect() {
        String last = SettingUtil.getLastConnectAddress();
        // ★ 常用地址下拉：预置两个常用 IP，历史自动累积（最多 8 条）
        java.util.List<String> hist = new java.util.ArrayList<>();
        hist.add("192.168.1.100:37911   HarmonyOS 设备（示例）");
        hist.add("192.168.1.101:40601   HarmonyOS 平板（示例）");
        String saved = PREF.get("ipHistory", "");
        for (String h : saved.split(";")) {
            if (!h.trim().isEmpty() && !hist.contains(h.trim())) hist.add(h.trim());
        }
        if (!last.isEmpty() && !hist.contains(last)) hist.add(0, last);
        // 用"可编辑下拉框"：既能选常用地址，也能直接手输新的
        JComboBox<String> box = new JComboBox<>(hist.toArray(new String[0]));
        box.setEditable(true);
        box.setPreferredSize(new Dimension(380, 32));
        box.setSelectedItem(hist.isEmpty() ? "" : hist.get(0));
        JLabel tip = new JLabel("选常用地址，或直接输入新的 IP:端口（多台用空格分隔）");
        tip.setForeground(FG_DIM);
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(tip, BorderLayout.NORTH);
        panel.add(box, BorderLayout.CENTER);
        Object[] opts = {"连接", "取消"};
        int ret = JOptionPane.showOptionDialog(this, panel, "连接设备",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        if (ret != 0) return;
        Object sel = box.getEditor().getItem();
        String picked = sel == null ? "" : String.valueOf(sel);
        if (picked == null) return;
        String input = picked.trim();
        // 下拉项带"IP  备注"，取第一个空格前的地址（兼容多地址用空格分隔的写法）
        int cut = input.indexOf("  ");
        if (cut > 0) input = input.substring(0, cut).trim();
        if (input.isEmpty()) return;
        // 记住历史
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>(hist);
        uniq.add(input);
        StringBuilder sb = new StringBuilder();
        int cnt = 0;
        for (String u : uniq) { if (cnt++ >= 8) break; if (sb.length() > 0) sb.append(';'); sb.append(u); }
        PREF.put("ipHistory", sb.toString());

        final String arg = input;
        SettingUtil.setLastConnectAddress(arg);
        PREF.put("lastConnectAddress", arg);   // 落盘：下次启动自动 tconn 这个地址，不用再手输
        screenView.setHint("正在连接 " + arg + " …");

        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            boolean anyOk = false;
            for (String addr : arg.split("[\\s,]+")) {
                if (addr.isEmpty()) continue;
                try {
                    ProcessExecutor ex = new ProcessExecutor();
                    String out = ex.callProcess("hdc tconn " + addr, 10);
                    log.append(addr).append(" -> ").append(out == null ? "" : out.trim()).append("\n");
                    if (out != null && (out.contains("OK") || out.contains("Connect"))) anyOk = true;
                } catch (Exception e) {
                    log.append(addr).append(" -> 异常 ").append(e.getMessage()).append("\n");
                }
            }
            final boolean ok = anyOk;
            final String detail = log.toString();
            Log.info(TAG, "connect result: " + detail);
            SwingUtilities.invokeLater(() -> {
                screenView.setHint(ok ? "连接成功，正在刷新设备…" : "连接失败：" + detail.trim());
                refreshDevices();
            });
            if (ok) {
                // 若刷新后已有设备，2.5 秒后自动进入画面（无需再点「进入投屏」）
                bgExec.submit(() -> {
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { return; }
                    final boolean[] go = {false};
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            go[0] = !capturing && deviceBox != null
                                    && deviceBox.getItemCount() > 0 && deviceBox.getSelectedItem() != null;
                        });
                    } catch (Throwable ignored) { }
                    if (go[0]) {
                        Log.info(TAG, "auto-enter capture (device found after refresh)");
                        SwingUtilities.invokeLater(this::startCapture);
                    }
                });
            }
            if (ok) {
                // 等 hdc 扫描一会儿；列表仍空就用刚连上的地址合成设备（否则永远"选不到设备"）
                final String firstAddr = arg.split("[\\s,]+")[0];
                bgExec.submit(() -> {
                    for (int i = 0; i < 6; i++) {
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                        final boolean[] has = {false};
                        try {
                            SwingUtilities.invokeAndWait(() -> {
                                has[0] = deviceBox != null && deviceBox.getItemCount() > 0;
                            });
                        } catch (Throwable ignored) { }
                        if (has[0]) return;
                    }
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            try {
                                Device synth = new Device(firstAddr);
                                if (deviceBox != null) {
                                    deviceBox.addItem(synth);
                                    deviceBox.setSelectedItem(synth);
                                }
                                // 左侧也画出来（否则"已添加但列表不显示"）
                                if (!iconLabels.containsKey(firstAddr)) {
                                    deviceListPanel.removeAll();
                                    deviceListPanel.add(deviceCard(synth));
                                    deviceListPanel.revalidate();
                                    deviceListPanel.repaint();
                                }
                                Log.info(TAG, "device list empty after 6s -> synthesized " + firstAddr);
                                screenView.setHint("已添加设备 " + firstAddr + "，正在自动进入画面…");
                                // 自动进入画面：不用再手动点「进入投屏」
                                if (!capturing && deviceBox != null && deviceBox.getSelectedItem() != null) {
                                    Log.info(TAG, "auto-enter capture after connect");
                                    startCapture();
                                }
                            } catch (Throwable t) {
                                Log.info(TAG, "synthesize in connect failed: " + t);
                            }
                        });
                    } catch (Throwable ignored) { }
                });
            }
        }, "connect").start();
    }

    /** 刷新设备：hdc 调用都是阻塞的（最长 10s），必须放后台线程，否则界面会卡死 */
    private void refreshDevices() {
        deviceListPanel.removeAll();
        deviceBox.removeAllItems();
        iconLabels.clear();
        nameLabels.clear();
        deviceListPanel.add(emptyTip("正在扫描设备…"));
        deviceListPanel.revalidate();
        deviceListPanel.repaint();

        new Thread(() -> {
            java.util.List<Device> found = new java.util.ArrayList<>();
            String err = null;
            try {
                
                try { found.addAll(HdcUtil.getLocalDevices()); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
                
                try { found.addAll(HdcUtil.getRemoteDevice()); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
            } catch (Exception ex) {
                err = ex.getMessage();
                Log.error(TAG, "refresh devices failed", ex);
            }
            final String finalErr = err;
            SwingUtilities.invokeLater(() -> applyDevices(found, finalErr));
        }, "device-scan").start();   // ★ 以前这里漏了 .start()：扫描线程从来没跑过，
                                     //   左侧永远停在"正在扫描设备…"，只能靠 ＋ 手输地址合成设备
    }

    /** 在 EDT 上把设备列表画出来 */
    private void applyDevices(java.util.List<Device> raw, String err) {
        deviceListPanel.removeAll();
        deviceBox.removeAllItems();
        iconLabels.clear();
        nameLabels.clear();

        // 先把"手工添加/合成"的设备并进来，避免刷新把它们冲掉（否则左侧会闪回"未发现设备"）
        java.util.List<Device> merged = new java.util.ArrayList<>();
        if (raw != null) merged.addAll(raw);
        if (deviceBox != null) {
            for (int i = 0; i < deviceBox.getItemCount(); i++) {
                Object it = deviceBox.getItemAt(i);
                boolean exists = false;
                for (Device d : merged) {
                    if (d != null && it instanceof Device
                            && String.valueOf(d.getSn()).equals(String.valueOf(((Device) it).getSn()))) {
                        exists = true;
                        break;
                    }
                }
                if (!exists && it instanceof Device) merged.add((Device) it);
            }
        }

        // 按 SN 去重（本机 tconn 的设备与手工添加的远程 IP 可能重复）
        java.util.LinkedHashMap<String, Device> uniq = new java.util.LinkedHashMap<>();
        for (Device d : merged) {
            if (d != null && d.getSn() != null) uniq.put(d.getSn(), d);
        }
        java.util.List<Device> list = new java.util.ArrayList<>(uniq.values());
        // 默认 LAN：192.168.137.x（P2P）排最后；只有 -Dsun.p2p=true 才把 P2P 排第一
        final boolean p2pMode = Boolean.getBoolean("sun.p2p");
        list.sort((a, b) -> {
            boolean ap = String.valueOf(a.getSn()).startsWith("192.168.137.");
            boolean bp = String.valueOf(b.getSn()).startsWith("192.168.137.");
            if (ap == bp) return 0;
            return p2pMode ? (ap ? -1 : 1) : (ap ? 1 : -1);
        });

        if (list.isEmpty()) {
            deviceListPanel.add(emptyTip(err != null
                    ? "刷新失败: " + err
                    : "未发现设备（确认 hdc.exe 与 libusb_shared.dll 在运行目录，且已 tconn）"));
        } else {
            for (Device d : list) {
                deviceListPanel.add(deviceCard(d));
                deviceListPanel.add(Box.createVerticalStrut(6));
                deviceBox.addItem(d);
            }
        }
        deviceListPanel.revalidate();
        deviceListPanel.repaint();

        // 后台查设备类型和产品名（hdc 调用较慢，不阻塞界面）
        java.util.List<String> sns = new java.util.ArrayList<>(iconLabels.keySet());
        if (!sns.isEmpty()) {
            bgExec.submit(() -> resolveDeviceInfo(sns));
        }

        // 调试/自动测试：-Dsun.connect=IP:PORT（或 -Dsun.autostart=true）→ 设备一出现就自动进画面
        if ((!debugConnect.isEmpty() || "true".equalsIgnoreCase(System.getProperty("sun.autostart")))
                && !autoStarted && !capturing && !connecting) {
            Device first = (Device) deviceBox.getSelectedItem();
            if (first != null) {
                autoStarted = true;
                Log.info(TAG, "autostart capture (debug): " + first.getSn());
                bgExec.submit(this::startCapture);
                if (debugPure) {
                    // 调试：-Dsun.pure=true → 7 秒后自动开纯享（用来核对"多屏协同外框"）
                    javax.swing.Timer pt = new javax.swing.Timer(7000, ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        if (phoneWin == null) togglePureMode();
                    });
                    pt.setRepeats(false);
                    pt.start();
                }
            }
        }
    }

    /** 后台查询每台设备的类型/型号/产品名（hdc 调用），再回 EDT 更新卡片 */
    private void resolveDeviceInfo(java.util.List<String> sns) {
        for (String sn : sns) {
            // ↓ 全部在后台线程完成，绝不在 EDT 上跑 hdc ✗
            String type = HdcUtil.queryDeviceType(sn);
            String prodName = HdcUtil.queryDeviceName(sn);
            String model = HdcUtil.queryDeviceModel(sn);
            utils.ProductCatalog.Info info = utils.ProductCatalog.byModel(model);
            if (info == null) info = utils.ProductCatalog.byProductName(prodName);
            // 产品库没收录 → 用 devicetype/型号/产品名/屏幕比例 综合判断（一定能区分手机/平板）
            String kindStr = (info != null) ? info.kind
                    : utils.ProductCatalog.classify(type, model, prodName, devScreenW, devScreenH);
            if (info == null) {
                Log.info(TAG, "classify fallback: type=" + type + " model=" + model
                        + " name=" + prodName + " -> " + kindStr);
            }
            final DeviceIcon.Kind kind = toKind(kindStr);
            if (sn != null) kindBySn.put(sn, kindStr);          // 每台设备都记，别只在采集后记
            if (sn != null && this.activeSn != null && sn.startsWith(this.activeSn.split(":")[0])) {
                lastDeviceKind = kindStr;
            }
            // 形态确定后同步纯享窗口的三大金刚（平板/PC 强制隐藏）
            if (sn != null && phoneWin != null) {
                final boolean showNav = !isPcLike();
                PhoneWindow pw0 = phoneWin;
                SwingUtilities.invokeLater(() -> pw0.setNavBarVisible(showNav));
            }
            // 标题：设备自报的名称最官方，优先用它；没有才用产品库里的
            String official = (prodName == null || prodName.isEmpty()) ? null : prodName.trim();
            final String prodFinal = (official != null && !official.isEmpty())
                    ? official : (info != null ? info.product : "");
            final String metaText = (info != null)
                    ? (kindName(kind) + " · " + (info.inch > 0 ? trimNum(info.inch) + "\" · " : "")
                        + Math.max(info.w, info.h) + "×" + Math.min(info.w, info.h))
                    : (kindName(kind) + " · 型号 " + (model == null || model.isEmpty() ? "未知" : model));
            // 未命中产品库 → 记下来（后台"延期识别"：下次启动仍能看到型号，我也据此补库）
            if (info == null && model != null && !model.isEmpty()) {
                String prev = PREF.get("unknownModels", "");
                if (!prev.contains(model)) {
                    PREF.put("unknownModels", (prev.isEmpty() ? model : prev + ";" + model));
                    Log.info(TAG, "catalog: 未收录型号 " + model + "（已记录，可在产品库里补上）");
                }
            }

            SwingUtilities.invokeLater(() -> {
                JLabel icon = iconLabels.get(sn);
                if (icon != null) {
                    icon.setIcon(new DeviceIcon(34, 40, COLOR_ONLINE, kind));
                    icon.setToolTipText(metaText);
                }
                JLabel nameLbl = nameLabels.get(sn);
                if (nameLbl != null && prodFinal != null && !prodFinal.isEmpty()) {
                    nameLbl.setText(prodFinal.length() > 24 ? prodFinal.substring(0, 24) + "…" : prodFinal);
                }
                JLabel metaLbl = metaLabels.get(sn);
                if (metaLbl != null) metaLbl.setText(metaText);
                Log.info(TAG, "device info: sn=" + sn + " type=" + type + " model=" + model
                        + " name=" + prodFinal + " kind=" + kind);
            });
        }
    }

    /** 未收录型号 → 打开浏览器搜索（运维手动确认产品信息，比抓网页可靠） */
    private static void openModelSearch(String model) {
        try {
            String q = java.net.URLEncoder.encode(model + " 华为 参数", "UTF-8");
            java.awt.Desktop.getDesktop().browse(
                    new java.net.URI("https://www.bing.com/search?q=" + q));
        } catch (Throwable ignored) { }
    }

    private static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d);
    }

    private static final Color COLOR_ONLINE = OK;

    private static DeviceIcon.Kind toKind(String type) {
        if (type == null) return DeviceIcon.Kind.UNKNOWN;
        String t = type.toLowerCase();
        if (t.contains("tablet") || t.contains("pad")) return DeviceIcon.Kind.PAD;
        if (t.contains("2in1") || t.contains("pc") || t.contains("desktop")) return DeviceIcon.Kind.PC;
        if (t.contains("fold")) return DeviceIcon.Kind.PHONE;    // 折叠屏按手机画（但名字里带"折叠"）
        if (t.contains("phone") || t.contains("mobile")) return DeviceIcon.Kind.PHONE;
        if (t.isEmpty()) return DeviceIcon.Kind.UNKNOWN;
        return DeviceIcon.Kind.UNKNOWN;
    }

    private static String kindName(DeviceIcon.Kind k) {
        switch (k) {
            case PHONE: return "手机";
            case PAD: return "平板";
            case PC: return "电脑";
            default: return "设备";
        }
    }

    /**
     * 连接时的配对校验（像蓝牙配对那样）：
     *   ① 本机生成 4 位随机校验码
     *   ② 通过 hdc 让设备原样回显（echo SUNAUTH-<code>）
     *   ③ 本机比对：一致 → 弹窗让用户确认 → 通过
     * 作用：证明"这条连接真的通到这台设备"，防止端口被他人占用/中间转发。
     */
    /**
     * 连接前的配对码校验（这才是"告诉对方我在连接、让他填 4 位码"的机制）：
     *   · 设备持有人先用 设配对码.bat 往手机写 /data/local/tmp/sun.pin
     *   · 任何人来连这台手机，都必须填对这个 4 位码才能用
     *   · 码读不到（没设过）→ 不拦截，直接连（向后兼容）
     */
    /** 每个设备记住上一次用过的配对码（和推流端一样，连过就不再问） */
    private String pinFor(String sn) {
        return PREF.get("pin_" + sn, "");
    }

    private void savePin(String sn, String pin) {
        PREF.put("pin_" + sn, pin);
    }

    /**
     * 连接前后的配对码校验，完全对齐推流端（Sender）的机制：
     *   ① 本机已记住这台设备的码 → 直接用（不再打扰）
     *   ② 手机上设了 /data/local/tmp/sun.pin → 必须填对才允许连接
     *   ③ 都没设 → 不拦（向后兼容）
     */
    private boolean askPairingPin(String sn) {
        if (sn == null || sn.isEmpty()) return true;
        // 用户选过"跳过"→ 一律不拦，直接出画面
        if ("true".equalsIgnoreCase(PREF.get("pinSkip", "false"))) return true;

        // ① 已记住
        String cached = pinFor(sn);
        if (cached != null && cached.length() == 4) {
            Log.info(TAG, "pairing pin: use cached " + cached + " for " + sn);
            return true;
        }

        // ② 读设备上的码（有就要求填）
        String pinOnDevice = null;
        String hb = findHdc();
        if (hb != null) {
            try {
                pinOnDevice = new ProcessExecutor().callProcess(
                        new String[]{hb, "-t", sn, "shell", "cat /data/local/tmp/sun.pin"}, 8);
                if (pinOnDevice != null) pinOnDevice = pinOnDevice.trim();
                Log.info(TAG, "pairing pin on device: " + pinOnDevice);
            } catch (Throwable t) {
                Log.info(TAG, "read pairing pin failed: " + t);
            }
        }
        boolean enforced = pinOnDevice != null && pinOnDevice.matches("\\d{4}");
        if (!enforced) {
            Log.info(TAG, "pairing pin: not enforced on device -> skip");
            return true;
        }

        // ③ 要求输入（三选项：确定 / 跳过以后不再问 / 取消连接）
        String host = sn.contains(":") ? sn.substring(0, sn.indexOf(':')) : sn;
        for (int attempt = 0; attempt < 3; attempt++) {
            JTextField pinField = new JTextField(6);
            pinField.setFont(new Font("Consolas", Font.BOLD, 18));
            JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
            p.add(new JLabel("设备 " + sn + " 已开启连接配对码。"));
            p.add(new JLabel("配对码显示在手机 HOScrcpy Sender 的「连接配对码」一行："));
            p.add(pinField);
            Object[] opts = {"确定", "跳过（以后不再询问）", "取消连接"};
            int r = JOptionPane.showOptionDialog(this, p, "连接配对码",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
            if (r == 1) {
                PREF.put("pinSkip", "true");
                Log.info(TAG, "pairing pin: user chose skip forever -> connect directly");
                return true;
            }
            if (r != 0) {
                Log.info(TAG, "pairing pin: cancelled");
                return false;
            }
            String got = pinField.getText() == null ? "" : pinField.getText().trim().replaceAll("[^0-9]", "");
            if (got.length() >= 4) got = got.substring(0, 4);
            Log.info(TAG, "pairing pin: try " + got + " via TCP " + host + ":6000");
            Boolean okTcp = verifyPinViaSender(host, got);
            if (okTcp == null) {
                okTcp = got.equals(pinOnDevice);
                Log.info(TAG, "pairing pin: sender offline, compare with file -> " + okTcp);
            }
            if (Boolean.TRUE.equals(okTcp)) {
                savePin(sn, got);
                Log.info(TAG, "pairing pin: ok, remembered for " + sn);
                return true;
            }
            Log.info(TAG, "pairing pin: wrong");
            JOptionPane.showMessageDialog(this, "配对码不正确，请重试。");
        }
        JOptionPane.showMessageDialog(this, "配对码错误次数过多，已取消连接。");
        return false;
    }

    /** 用推流端 Sender 的握手来校验配对码：连 6000 → 发 4 位码 → 收到 SUNHDR 就算通过。
     *  返回 null 表示推流端没开（无法校验）。 */
    private Boolean verifyPinViaSender(String host, String code) {
        java.net.Socket sock = null;
        try {
            sock = new java.net.Socket();
            sock.connect(new java.net.InetSocketAddress(host, 6000), 1500);
            sock.setSoTimeout(2500);
            sock.getOutputStream().write(code.getBytes("UTF-8"));
            sock.getOutputStream().flush();
            java.io.InputStream in = sock.getInputStream();
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n <= 0) return false;
            String head = new String(buf, 0, n, "UTF-8");
            Log.info(TAG, "sender handshake -> " + head.replace("\n", " "));
            return head.startsWith("SUNHDR");
        } catch (java.net.ConnectException ce) {
            return null;              // 推流端没开
        } catch (Throwable t) {
            Log.info(TAG, "verifyPinViaSender: " + t);
            return false;
        } finally {
            if (sock != null) { try { sock.close(); } catch (Exception ignored) { } }
        }
    }

    private void deviceAuthHandshake() {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        String snTmp = null;
        try { snTmp = ((Device) deviceBox.getSelectedItem()).getSn(); } catch (Throwable ignored) { }
        final String snNow = snTmp;   // lambda 需要 final 副本
        if (snNow != null && snNow.equals(authShownForSn)) return;   // 这台设备已确认过
        // 调试/自动测试：-Dsun.noauth=true 跳过配对码确认弹窗（只给命令行冒烟测试用）
        if ("true".equalsIgnoreCase(System.getProperty("sun.noauth"))) {
            Log.info(TAG, "auth handshake skipped (-Dsun.noauth)");
            authShownForSn = snNow;
            return;
        }

        final char[] AB = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(AB[rnd.nextInt(AB.length)]);
        final String code = sb.toString();
        bgExec.submit(() -> {
            try {
                Log.info(TAG, "auth handshake: send code " + code);
                String out = dev.executeShellCommand("echo SUNAUTH-" + code, 5);
                boolean matched = out != null && out.contains("SUNAUTH-" + code);
                Log.info(TAG, "auth handshake: device replied " + (out == null ? "null" : out.trim())
                        + " matched=" + matched);
                SwingUtilities.invokeLater(() -> {
                    if (!matched) {
                        JOptionPane.showMessageDialog(this,
                                "设备校验失败：设备没有正确回显校验码。\n连接可能被占用或转发，请检查后重连。",
                                "配对校验失败", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    // ★ 纯享/多屏协同窗口开着时，主窗口是隐藏的：对话框必须以"可见的窗口"为父，
                    //   否则它会挂在隐藏的主窗口下面，用户根本看不到 → 看起来就是"卡住不出画面"。
                    java.awt.Window owner = (phoneWin != null && phoneWin.isShowing())
                            ? (java.awt.Window) phoneWin : (java.awt.Window) SunFrame.this;
                    authPending = true;
                    int r;
                    try {
                        r = confirmDialog(owner, "设备校验码：" + code + "\n\n设备已回显一致的校验码。\n确认连接这台设备吗？",
                                "配对校验");
                    } finally {
                        authPending = false;
                    }
                    if (r == JOptionPane.OK_OPTION) {
                        Log.info(TAG, "auth handshake: user confirmed " + code);
                        authShownForSn = snNow;
                        screenView.setHint("配对校验通过（" + code + "）");
                    } else {
                        Log.info(TAG, "auth handshake: user rejected " + code);
                        releaseAll("用户取消配对校验");
                    }
                });
            } catch (Throwable t) {
                Log.info(TAG, "auth handshake failed: " + t);
            }
        });
    }

    /** 确认对话框：显式建 JDialog 并置顶，保证在"纯享窗口盖住主窗口"时也一定能看到 */
    private int confirmDialog(java.awt.Window owner, String msg, String title) {
        JOptionPane pane = new JOptionPane(msg, JOptionPane.INFORMATION_MESSAGE,
                JOptionPane.OK_CANCEL_OPTION);
        JDialog dlg = pane.createDialog(owner, title);
        dlg.setAlwaysOnTop(true);
        dlg.setVisible(true);
        Object v = pane.getValue();
        try { dlg.dispose(); } catch (Throwable ignored) { }
        if (v instanceof Integer) return (Integer) v;
        return JOptionPane.CLOSED_OPTION;
    }

    /** 授权校验：机器码 + 授权码；未通过直接退出 */
    private void ensureActivated() {
        String mc = utils.License.machineCode();
        String saved = PREF.get("licenseKey", "");
        if (utils.License.verify(mc, saved)) {
            Log.info(TAG, "license ok (" + mc + ")");
            return;
        }
        // 主控台（我们自己调试用）：-Dsun.license=skip
        if ("skip".equalsIgnoreCase(System.getProperty("sun.license"))) {
            Log.info(TAG, "license skipped by -Dsun.license=skip");
            return;
        }
        JTextField keyField = new JTextField(saved, 10);
        keyField.setFont(new Font("Consolas", Font.PLAIN, 14));
        JTextField mcField = new JTextField(mc, 18);
        mcField.setFont(new Font("Consolas", Font.PLAIN, 14));
        mcField.setEditable(false);
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        p.add(new JLabel("本机机器码（发给作者换授权码）："));
        p.add(mcField);
        p.add(new JLabel("授权码（4 位，形如 K7M3）："));
        p.add(keyField);
        p.add(new JLabel("未激活无法使用投屏与反控。"));
        for (int attempt = 0; attempt < 3; attempt++) {
            int r = JOptionPane.showConfirmDialog(null, p, "HOScrcpy 授权验证",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                JOptionPane.showMessageDialog(null, "已取消，程序退出。");
                System.exit(0);
            }
            if (utils.License.verify(mc, keyField.getText())) {
                PREF.put("licenseKey", keyField.getText().trim().toUpperCase());
                Log.info(TAG, "license activated");
                return;
            }
            JOptionPane.showMessageDialog(null, "授权码不正确，请重试。");
        }
        JOptionPane.showMessageDialog(null, "授权失败，程序退出。");
        System.exit(0);
    }

    /** 把 hdc.exe 所在目录塞到 PATH 最前面：
     *  SDK 内部有裸 "hdc" 调用，这样能保证它用的和我们同一个 exe ✓ */
    /** static 版 findHdc（供静态方法用） */
    private static String findHdcStatic() {
        String[] dirs = {System.getProperty("user.dir"),
                System.getProperty("user.dir") + java.io.File.separator + "target"};
        String[] names = {"hdc.exe", "hdc"};
        for (String d : dirs) {
            for (String n : names) {
                java.io.File f = new java.io.File(d, n);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** 只做体检，不再反射改本进程的环境变量。
     *  原因：Java 9+ 那个反射必然失败（日志里天天一条 InaccessibleObjectException），
     *  而真让它成功反而更危险 —— ProcessEnvironment.theEnvironment 的 key 是内部 Variable 类型，
     *  塞一个 String key 进去，之后所有子进程创建都会炸。
     *  真正需要 hdc 的地方我们本来就传绝对路径（config.setHdcPath + 本类所有 ProcessBuilder），
     *  PATH 只影响 SDK / 系统里按名字找 hdc 的兜底。 */
    private static void prependHdcToPath() {
        try {
            String hb = findHdcStatic();
            if (hb == null) {
                Log.info(TAG, "hdc.exe 不在程序目录：SDK 会按 PATH 找 hdc（建议把 hdc.exe 放进来）");
                return;
            }
            java.io.File dir = new java.io.File(hb).getAbsoluteFile().getParentFile();
            if (dir == null) return;
            String d = dir.getAbsolutePath();
            String path = System.getenv("PATH");
            boolean onPath = path != null
                    && (path.equals(d) || path.startsWith(d + java.io.File.pathSeparator));
            Log.info(TAG, "hdc dir = " + d + "  已在 PATH 最前 = " + onPath);
        } catch (Throwable t) {
            Log.info(TAG, "prependHdcToPath check failed: " + t);
        }
    }

    /** 单行化：hdc 的输出常带换行，直接写日志会把一条记录拆成好几行 */
    private static String oneLine(String s) {
        if (s == null) return "null";
        String t = s.trim().replace("\r", " ").replace("\n", " | ");
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    /** 记录这台电脑上 hdc 的真实情况。
     *  11.128 上出现过 6 个 hdc.exe 抢着起服务 → SDK 报 can not found device，
     *  所以每次启动把"用的是哪个 / PATH 里还有谁"记下来，事后一眼能看出来。 */
    private static void logHdcEnvironment(String hb) {
        try {
            utils.ProcessExecutor ex = new utils.ProcessExecutor();
            String ver = oneLine(ex.callProcess(new String[]{hb, "-v"}, 6));
            if (ver.isEmpty() || ver.equals("null")) ver = oneLine(ex.callProcess(new String[]{hb, "version"}, 6));
            Log.info(TAG, "hdc used = " + hb + "   version = " + ver);
            StringBuilder dup = new StringBuilder();
            String path = System.getenv("PATH");
            java.io.File self = new java.io.File(hb).getAbsoluteFile();
            if (path != null) {
                for (String p : path.split(java.io.File.pathSeparator)) {
                    if (p == null) continue;
                    String dir = p.trim().replace("\"", "");
                    if (dir.isEmpty()) continue;
                    for (String n : new String[]{"hdc.exe", "hdc"}) {
                        java.io.File f = new java.io.File(dir, n);
                        if (!f.isFile()) continue;
                        String abs = f.getAbsolutePath();
                        if (abs.equalsIgnoreCase(self.getAbsolutePath())) continue;
                        if (dup.toString().contains(abs)) continue;
                        if (dup.length() > 0) dup.append(" ; ");
                        dup.append(abs);
                    }
                }
            }
            if (dup.length() > 0) {
                Log.info(TAG, "hdc WARN: PATH 里还有别的 hdc（多版本混用会导致 can not found device）：" + dup);
            } else {
                Log.info(TAG, "hdc PATH clean（没有第二个 hdc 抢服务）");
            }
        } catch (Throwable t) {
            Log.info(TAG, "hdc env check failed: " + t);
        }
    }

    /** 重启后自动 tconn 上次用过的地址。
     *  启动时的 hdc kill 会断开无线设备；不重连的话每次开程序左侧都是空的，
     *  用户只能再手输一次 IP（更新一次程序就要输一次）。 */
    /** 手机的 Wi-Fi Direct 地址：原版"多屏协同"建立 P2P 链路时，
     *  netstat 里会有 192.168.137.x 的对端；拿它走 hdc 可以绕开家里 WiFi 拥塞。 */
    private String findP2pPeer() {
        try {
            String out = new utils.ProcessExecutor().callProcess("netstat -ano", 8);
            if (out == null || out.isEmpty()) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:TCP|UDP)\\s+192\\.168\\.137\\.\\d+:\\d+\\s+(192\\.168\\.137\\.\\d+):\\d+")
                    .matcher(out);
            java.util.Map<String, Integer> cnt = new java.util.HashMap<>();
            while (m.find()) {
                String peer = m.group(1);
                Integer c = cnt.get(peer);
                cnt.put(peer, c == null ? 1 : c + 1);
            }
            String best = null;
            int bestN = 0;
            for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
                if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
            }
            if (best != null) Log.info(TAG, "P2P peer = " + best + " (" + bestN + " flows)");
            return best;
        } catch (Throwable t) {
            Log.info(TAG, "findP2pPeer failed: " + t);
            return null;
        }
    }

    private boolean autoReconnectLast(String hb) {
        String raw = PREF.get("lastConnectAddress", "");
        String addr = (raw == null || raw.trim().isEmpty())
                ? SettingUtil.getLastConnectAddress() : raw;
        if (addr == null || addr.trim().isEmpty()) {
            Log.info(TAG, "startup auto-tconn: 没有历史地址，跳过");
            return false;
        }
        final String target = addr.trim().split("[\\s,]+")[0];
        return tconnOnce(hb, target, "startup auto-tconn");
    }

    /** 执行一次 hdc tconn（启动自动重连 / -Dsun.connect 调试都走这里） */
    private boolean tconnOnce(String hb, String target, String why) {
        try {
            utils.ProcessExecutor ex = new utils.ProcessExecutor();
            String out = ex.callProcess(new String[]{hb, "tconn", target}, 6);
            boolean ok = out != null && (out.contains("OK") || out.contains("Connect"));
            Log.info(TAG, why + " " + target + " -> " + oneLine(out) + " ok=" + ok);
            if (ok) Thread.sleep(800);      // 给 hdc 一点时间把设备挂到 targets 里
            return ok;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            Log.info(TAG, why + " failed: " + t);
            return false;
        }
    }

    /** 后台线程里安全地改画面提示（startCapture 现在也会被 bgExec 直接调用） */
    private void hintLater(String s) {
        SwingUtilities.invokeLater(() -> {
            if (screenView != null) screenView.setHint(s);
        });
    }

    /** hdc 里一个设备都没有 → 说明设备已断开，别再无限重试 */
    private volatile long lastNoDeviceHintAt = 0;
    private boolean noDeviceAttached() {
        String hb = findHdc();
        if (hb == null) return true;
        try {
            String t = new ProcessExecutor().callProcess(new String[]{hb, "list", "targets"}, 6);
            boolean empty = t == null || t.trim().isEmpty() || t.contains("Empty");
            if (empty) {
                long now = System.currentTimeMillis();
                if (now - lastNoDeviceHintAt > 5000) {
                    lastNoDeviceHintAt = now;
                    Log.info(TAG, "hdc has NO device attached -> stop retrying");
                    SwingUtilities.invokeLater(() -> screenView.setHint(
                            "设备已断开：请确认手机无线调试仍开启、地址端口没变，然后重新连接"));
                }
            }
            return empty;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 采集前的 hdc 预检：杀掉残留 hdc → 重连 → 确认设备在 targets 里
     *  不同电脑上 SDK 常因为 hdc 服务状态混乱而"找不到设备"，这里统一兜底。 */
    /** 旁路检测：SDK 的 uitest daemon 是否真的在设备上跑起来了。
     *  有些机型 onReady 不回调（SDK 报 can not found device），但 daemon 实际已启动 →
     *  这种情况也把反控标成就绪，用原生触摸（比 shell 快得多）。 */
    private void checkDaemonAndMarkReady(String sn) {
        String hb = findHdc();
        if (hb == null || sn == null || sn.isEmpty()) return;
        bgExec.submit(() -> {
            try {
                utils.ProcessExecutor ex = new utils.ProcessExecutor();
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(800);
                    String out = ex.callProcess(new String[]{hb, "-t", sn, "shell",
                            "ps -ef | grep 'uitest start-daemon' | grep -v grep | wc -l"}, 10);
                    String cnt = out == null ? "" : out.trim();
                    Log.info(TAG, "daemon probe #" + (i + 1) + " = " + cnt);
                    if (cnt.startsWith("1") || cnt.startsWith("2")) {
                        Log.info(TAG, "daemon detected via hdc -> mark control ready (native touch)");
                        useShellTouch = false;
                        SwingUtilities.invokeLater(() -> markControlReady());
                        return;
                    }
                }
                Log.info(TAG, "daemon not detected in 5s, keep shell touch");
            } catch (Throwable t) {
                Log.info(TAG, "checkDaemonAndMarkReady failed: " + t);
            }
        });
    }

    /** 官方 API：device.executeShellCommand(String) —— 不用 spawn hdc.exe，快很多。
     *  签名用反射探测（jar 里的描述符没公开文档），失败就返回 null 走老路。 */
    /** 官方 API：device.executeShellCommand(String, int 超时秒) —— 不起进程，比 spawn hdc 快很多 */
    private String execShellNative(String cmd) {
        HosRemoteDevice dev = device;
        if (dev == null) return null;
        try {
            String out = dev.executeShellCommand(cmd, 5);
            Log.info(TAG, "native shell[" + cmd + "] -> " + (out == null ? "null" : out.trim()));
            return out;
        } catch (Throwable t) {
            Log.info(TAG, "native shell failed: " + t);
            return null;
        }
    }

    /** 清掉设备上残留的 uitest 守护进程。
     *  它们都抢 5000 端口：残留一个旧的，新会话 onReady 了也一包数据都没有（10-29 实测），
     *  SDK 侧就报 can not found device / 黑屏。
     *  ★ 实测 pkill -f 'uitest start-daemon' 在设备上不生效（daemon before=1 / after=1），
     *    所以改成 pidof + kill -9（验证有效：两个残留进程一次清掉，随后立刻出帧）。 */
    private void cleanupStaleDaemon(String sn) {
        String hb = findHdc();
        if (hb == null || sn == null || sn.isEmpty()) return;
        try {
            utils.ProcessExecutor ex = new utils.ProcessExecutor();
            String pids = ex.callProcess(new String[]{hb, "-t", sn, "shell", "pidof uitest"}, 8);
            // 只保留数字和空格，避免把异常输出拼进 shell 命令
            final String clean = pids == null ? "" : pids.replaceAll("[^0-9 ]", " ").trim();
            Log.info(TAG, "uitest daemons before=[" + clean + "]");
            if (clean.isEmpty()) return;
            String after = ex.callProcess(new String[]{
                    hb, "-t", sn, "shell", "kill -9 " + clean + "; sleep 1; pidof uitest"}, 10);
            Log.info(TAG, "uitest daemons after=[" + (after == null ? "null" : after.trim()) + "]");
        } catch (Throwable t) {
            Log.info(TAG, "cleanupStaleDaemon failed: " + t);
        }
    }

    private boolean hdcPreflight(String sn) {
        String hb = findHdc();
        if (hb == null) {
            Log.info(TAG, "preflight: no hdc.exe");
            return false;
        }
        try {
            utils.ProcessExecutor ex = new utils.ProcessExecutor();
            String targets = ex.callProcess(new String[]{hb, "list", "targets"}, 8);
            boolean present = targets != null && sn != null && targets.contains(sn);
            if (!present) {
                // ★ 绝不 kill hdc（kill 后 SDK 立刻查设备会 Device not found → 无画面）
                //   只做一次 tconn，然后等它稳定
                Log.info(TAG, "preflight: tconn only（不 kill）");
                ex.callProcess(new String[]{hb, "tconn", sn}, 10);
                Thread.sleep(800);
                targets = ex.callProcess(new String[]{hb, "list", "targets"}, 8);
                present = targets != null && sn != null && targets.contains(sn);
            }
            Log.info(TAG, "preflight: targets=" + (targets == null ? "null"
                    : targets.trim().replace("\n", " | ")) + " present=" + present);
            return present;
        } catch (Throwable t) {
            Log.info(TAG, "preflight failed: " + t);
            return false;
        }
    }

    private void startCapture() {
        // ★ 只有"目标设备变了"才释放旧连接。
        //   原来写成"device != null 就释放"→ auto-start / watchdog 重启时
        //   会把自己刚建好的连接反复释放 → 死循环、永远不出画面 ✗
        if (noDeviceAttached()) {
            connecting = false;
            capturing = false;
            return;      // 设备不在 hdc 列表里 → 不启动采集，也不重试
        }
        Device target = (Device) deviceBox.getSelectedItem();
        String targetSn = target == null ? null : target.getSn();
        if (device != null && activeSn != null && targetSn != null && !activeSn.equals(targetSn)) {
            Log.info(TAG, "startCapture: 切换到新设备 " + activeSn + " -> " + targetSn + "，释放旧的");
            daemonCleaned = false;   // 换设备要重新清一遍设备端残留服务
            releaseAll("切换设备");
        }
        if (connecting) {
            hintLater("正在连接中，请稍候…");
            return;
        }
        Device d = (Device) deviceBox.getSelectedItem();
        if (d == null) {
            hintLater("请先选择一台设备");
            return;
        }
        connecting = true;
        stopped = false;
        final int myGen = ++captureGen;
        hintLater("正在连接 " + d.getSn() + " …");
        setConnected(true);
        frameCount = 0;
        rawPackets = 0;
        sdkAcc.reset();
        hevcTried = false;
        reconnectTried = false;
        retryNoDevice = 0;
        lastFrameAt = 0;
        fpsFrames = 0;
        fpsStamp = System.currentTimeMillis();

        capThread = new Thread(() -> {
            try {
                if (device != null) {
                    
                    try { device.stopImageScreenCapture(); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
                    device = null;
                    Thread.sleep(400);
                }
                // ★ 先清设备上残留的 uitest/scrcpy 服务（上次强杀、崩溃、b.bat 重启都会留下）。
                //   不清的话 SDK 会连到"假活着"的会话：onReady 回调了，但一个视频包都不来
                //   （2026-10-29 实测：连续启动 8 次里有 4 次 0 包，清完就稳定）。
                //   只在进程内第一次连接/换设备时做，转屏和看门狗重启不做（否则恢复会慢）。
                if (!daemonCleaned) {
                    daemonCleaned = true;
                    long cleanT0 = System.currentTimeMillis();
                    cleanupStaleDaemon(d.getSn());
                    Log.info(TAG, "stale daemon cleanup took " + (System.currentTimeMillis() - cleanT0) + "ms");
                }
                // ===== 严格按官方 API 文档（干净版，不再堆历史 hack）=====
                // config：sn / ip / hdcPath(完整 exe) / hdcPort；画质档用 setScale(int)
                HosRemoteConfig config = new HosRemoteConfig(d.getSn());
                streamSn = d.getSn();
                PREF.put("streamSn", streamSn);
                if (!streamSn.startsWith("192.168.137.")) {
                    PREF.put("lastConnectAddress", streamSn);   // 只记 LAN，P2P 不写进默认地址
                }
                if (d.getIp() != null) config.setIp(d.getIp());
                String hb = findHdc();
                if (hb != null) {
                    config.setHdcPath(new java.io.File(hb).getAbsolutePath());
                    Log.info(TAG, "[official] hdcPath=" + new java.io.File(hb).getAbsolutePath()
                            + "（不设 hdcPort，昨晚可用版本也没设）");
                }
                String q = qualityBox == null ? "原始" : String.valueOf(qualityBox.getSelectedItem());
                if ("50%".equals(q)) {
                    config.setScale(2);
                    Log.info(TAG, "[official] quality=50% -> setScale(2) 期望 658x1416");
                } else if ("33%".equals(q)) {
                    config.setScale(3);
                    Log.info(TAG, "[official] quality=33% -> setScale(3) 期望 438x944");
                } else {
                    Log.info(TAG, "[official] quality=原始 -> SDK 默认参数（不设帧率/缩放）");
                }
                // 码率：弱网手动降（官方 setBitRate，单位 bps）
                String brProp = System.getProperty("sun.bitrate", "").trim();
                if (!brProp.isEmpty()) {
                    try {
                        config.setBitRate(Integer.parseInt(brProp));
                        Log.info(TAG, "[debug] bitRate=" + brProp + " (sun.bitrate override)");
                    } catch (Throwable t) {
                        Log.info(TAG, "[debug] bad -Dsun.bitrate=" + brProp);
                    }
                } else {
                    String br = bitrateBox == null ? "默认" : String.valueOf(bitrateBox.getSelectedItem());
                    if ("10M".equals(br)) { config.setBitRate(10_000_000); Log.info(TAG, "[official] bitRate=10Mbps"); }
                    else if ("20M".equals(br)) { config.setBitRate(20_000_000); Log.info(TAG, "[official] bitRate=20Mbps"); }
                    else if ("40M".equals(br)) { config.setBitRate(40_000_000); Log.info(TAG, "[official] bitRate=40Mbps"); }
                    else { Log.info(TAG, "[official] bitRate=默认（SDK 30Mbps）"); }
                }
                // 帧率：默认限 60（手机屏是 90/120Hz，SDK 会照原样推 → 解码线程追不上，队列堆到几秒延迟）
                String fpsProp = System.getProperty("sun.fps", "").trim();
                if (!fpsProp.isEmpty()) {
                    try {
                        config.setFrameRate(Integer.parseInt(fpsProp));
                        Log.info(TAG, "[debug] frameRate=" + fpsProp + " (sun.fps override)");
                    } catch (Throwable t) {
                        Log.info(TAG, "[debug] bad -Dsun.fps=" + fpsProp);
                    }
                } else {
                    try {
                        config.setFrameRate(60);
                        Log.info(TAG, "[official] frameRate=60（默认限帧，避免队列堆积成秒级延迟）");
                    } catch (Throwable t) {
                        Log.info(TAG, "[official] setFrameRate(60) failed: " + t);
                    }
                }
                String ifrProp = System.getProperty("sun.iframe", "").trim();
                if (!ifrProp.isEmpty()) {
                    try {
                        config.setIFrameInterval(Integer.parseInt(ifrProp));
                        Log.info(TAG, "[debug] iFrameInterval=" + ifrProp + "ms (sun.iframe override)");
                    } catch (Throwable t) {
                        Log.info(TAG, "[debug] bad -Dsun.iframe=" + ifrProp);
                    }
                }
                // ★ 连接配对码：设备上设了 sun.pin 就要求填码（谁连谁填）
                if (!askPairingPin(d.getSn())) {
                    Log.info(TAG, "pairing pin rejected -> abort capture（不释放设备，保留反控状态）");
                    connecting = false;
                    return;
                }
                device = new HosRemoteDevice(config);      // 官方：用 config 建 device
                activeSn = d.getSn();
                  // 正在收 TCP 推流（Sender 的 HEVC）→ 只建立控制通道，不启动 SDK 采集
                  if (tcpStreaming) {
                      connecting = false;
                      capturing = true;
                      SwingUtilities.invokeLater(() -> {
                          if (enterBtn != null) enterBtn.setText("停止投屏");
                          screenView.setHint("");
                          setConnected(true);
                      });
                      // 反控关键：HOScrcpy 的触控注入依赖它自己启动的 uitest 守护进程 + 一条真实的采集会话。
                      // 这里用"原生分辨率 + 低帧率 + 低码率"起一条控制专用会话（几乎不占资源），
                      // 画面数据丢弃——显示仍然用 Sender 的推流。
                      HosRemoteDevice ctl = device;
                      try {
                          Device dv = (Device) deviceBox.getSelectedItem();
                          if (dv != null) {
                              com.huawei.hosscrcpy.api.HosRemoteConfig ccfg =
                                      new com.huawei.hosscrcpy.api.HosRemoteConfig(dv.getSn());
                              if (dv.getIp() != null) ccfg.setIp(dv.getIp());
                              ccfg.setImageScaleSize(1.0f);   // 关键：坐标空间 = 真实屏幕像素
                              ccfg.setFrameRate(5);
                              ccfg.setBitRate(2 * 1024 * 1024);
                              ccfg.setIFrameInterval(2000);
                              ctl = new HosRemoteDevice(ccfg);
                              device = ctl;
                              Log.info(TAG, "control device re-created: scale=1.0 fps=5");
                          }
                      } catch (Throwable t) {
                          Log.info(TAG, "control device re-create failed: " + t);
                      }
                      final HosRemoteDevice ctlDev = ctl;
                      new Thread(() -> {
                          try {
                              ctlDev.startCaptureScreen(new com.huawei.hosscrcpy.api.ScreenCapCallback() {
                                  @Override public void onData(java.nio.ByteBuffer data) {
                                      // 丢弃：画面走 Sender 推流
                                      if (data != null) data.position(data.limit());
                                  }
                                  @Override public void onReady() {
                                      Log.info(TAG, "control daemon ready");
                                      markControlReady();
                                  }
                                  @Override public void onException(Throwable t) {
                                      Log.info(TAG, "control daemon exception: " + t);
                                  }
                              });
                              Log.info(TAG, "control channel started (uitest daemon up)");
                          } catch (Throwable t) {
                              Log.info(TAG, "control channel start failed: " + t);
                          }}, "tcp-control").start();   // ★ 漏了 .start() → B 路反控 daemon 从来没起来过
                      Log.info(TAG, "control channel ready, tcp stream keeps displaying");
                      return;
                  }

                String pick = modeBox != null ? (String) modeBox.getSelectedItem() : "自动";
                boolean videoOk;
                if ("图片流".equals(pick)) {
                    videoOk = false;
                } else {
                    // 自动 / 视频流：一律先试 H.264。
                    // 之前用"设备是否有 libCPHMediaEngine.z.so"来判断是错的 ——
                    // 实测两台设备都没有这个库，但旧版视频流照样能出画面。
                    // 真正的判据是"8 秒内有没有帧"，交给看门狗降级即可。
                    videoOk = true;
                }
                final String modeHint = videoOk ? "H.264 视频流" : "图片流";
                SwingUtilities.invokeLater(() -> screenView.setHint("连接成功，使用 " + modeHint));
                if (videoOk) {
                    startVideoCapture(d, myGen);
                } else {
                    startImageCapture(d, myGen);
                }
            } catch (Exception ex) {
                connecting = false;
                Log.error(TAG, "start capture failed", ex);
                final String msg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    screenView.setHint("启动失败: " + msg);
                    setConnected(false);
                });
            }
        }, "capture");
        capThread.setDaemon(true);
        capThread.start();
    }

    /** H.264 视频流：流畅（目标 30-60fps）；失败自动降级到图片流 */
    private void startVideoCapture(Device d, int myGen) {
        if (decoder == null) {
            decoder = new FfmpegDecoder();
            decoder.init();
        }
        codecLabel = "H.264 hdc";
        useShellTouch = false;                // ★ A 路：用 SDK 原生触摸（跟手，无 hdc 开销）
        // A 路也要拿设备真实尺寸（旋转/坐标都依赖它）
        try {
            com.huawei.hosscrcpy.api.Size sz0 = device.getScreenSize(false);
            if (sz0 != null) {
                devScreenW = sz0.width;
                devScreenH = sz0.height;
                haveDeviceSize = true;      // ★ 关键：启用坐标换算（50%/33% 档触控才不会偏）
                Log.info(TAG, "device screen = " + devScreenW + "x" + devScreenH
                        + " (haveDeviceSize=true)");
            }
        } catch (Throwable t) {
            Log.info(TAG, "getScreenSize failed: " + t);
        }
        auIsHevc = false;                     // ★ 传统路径是 AVC，AU 切分要按 AVC 规则
        needParamSets = true;
        waitingIdr = true;
        final FfmpegDecoder dec = decoder;
        startDecodeThread(dec, myGen);
        // 旧版的做法：先 stop 一次再 start（清掉残留的 scrcpy 服务），实测这样才能出流
        try {
            device.stopCaptureScreen();
        } catch (Exception ex) {
            Log.info(TAG, "ignored: " + ex);
        }
        armNoFrameWatchdog(d, myGen);   // 8 秒没帧才降级（正常 2 秒内就有数据）
        // SDK 的 onReady 在部分机型不回调 → 用 hdc 直接探 daemon，探到就切原生触摸
        checkDaemonAndMarkReady(d.getSn());
        deviceAuthHandshake();
        device.startCaptureScreen(new ScreenCapCallback() {
            @Override public void onData(ByteBuffer bb) {
                if (stopped || myGen != captureGen) return;
                try {
                    // 关键：旧版用的是 bb.array()（整个底层数组），不是 remaining 切片。
                    // 用 remaining 会从 position 开始拷，丢掉前面的起始码/头部，导致
                    // av_parser_parse2 永远返回 0（一直"攒数据"却攒不出完整 NAL）。
                    byte[] bytes = readBuffer(bb);
                    int n = bytes.length;
                    long cnt = ++rawPackets;
                    if (decodeRunning) {
                        // SDK 路：逐包直喂（12:40 那版，横竖屏都能出帧 ✓）
                        // 不要再走 sdkAcc+splitAndFeedNals：横屏 AVC 用组帧方式会一直 EAGAIN 解不出帧 ✗
                        enqueuePkt(bytes);
                        if (cnt <= 3 || cnt % 600 == 0) {
                            Log.info(TAG, "sdk onData#" + cnt + " bytes=" + n);
                        }
                        return;
                    }
                    if (cnt <= 3) {
                        Log.info(TAG, "packet#" + cnt + " pos=" + bb.position() + " lim=" + bb.limit()
                                + " cap=" + bb.capacity() + " hasArray=" + bb.hasArray()
                                + " fed=" + n);
                    }
                    java.awt.image.BufferedImage img = dec.decode(bytes);
                    if (cnt <= 3 || cnt % 600 == 0) {
                        Log.info(TAG, "onData#" + cnt + " bytes=" + n
                                + (img == null ? " 解码未出帧" : " 出帧 " + img.getWidth() + "x" + img.getHeight()));
                    }
                    if (img != null) {
                        onFrame(img, "H.264");
                    }
                } catch (Exception ex) {
                    Log.info(TAG, "decode h264 failed: " + ex);
                }
            }

            @Override public void onException(Throwable t) {
                Log.error(TAG, "video stream error", t);
                if (myGen != captureGen) return;
                String msg = String.valueOf(t.getMessage());
                final String reason = classifyFailure(msg);
                // ★ 网络断/设备掉线：不要再"放弃重试"停成一张死画面，
                //   自动 tconn 回同一个地址并把采集重新拉起来（WiFi 抖动时最常见）。
                if ("NETWORK".equals(reason) || "DEVICE_SIDE".equals(reason)) {
                    Log.info(TAG, "device/network lost (" + reason + ") -> auto reconnect");
                    connecting = false;
                    try { teardownCapture(); } catch (Throwable ignored) { }
                    startAutoReconnect(msg, d.getSn());
                    return;
                }
                if (!reconnectTried) {
                    reconnectTried = true;
                    // 横屏/换档时 SDK 会重建采集，这里快速重连（200ms），把空档压到最短
                    boolean maybeRotate = String.valueOf(t.getMessage()).contains("device")
                            || String.valueOf(t.getMessage()).contains("capture")
                            || String.valueOf(t.getMessage()).contains("stop");
                    SwingUtilities.invokeLater(() -> screenView.setHint(
                            maybeRotate ? "画面换向/重建中…" : "连接中断，正在自动重连…"));
                    bgExec.submit(() -> {
                        try { Thread.sleep(maybeRotate ? 200 : 800); } catch (InterruptedException ignored) { return; }
                        teardownCapture();
                        if (maybeRotate) {
                            // 转屏：只重启采集，不动 device（重建 device 反而会触发 SDK 重新握手 → 画面断更久）
                            Log.info(TAG, "rotation -> restart capture (keep device)");
                        }
                        startCapture();
                    });
                } else {
                    SwingUtilities.invokeLater(() -> screenView.setHint("连接中断： " + t.getMessage()));
                }
            }

            @Override public void onReady() {
                connecting = false;
                capturing = true;
                SwingUtilities.invokeLater(() -> {
                    if (enterBtn != null) enterBtn.setText("停止投屏");
                    screenView.setHint("");
                });
                SwingUtilities.invokeLater(() -> screenView.setHint("已连接，等待关键帧…"));
                Log.info(TAG, "h264 stream ready");
            }
        });
    }

    /** 只有一台设备时，第一次连上就自动进"多屏协同"（纯享）窗口。
     *  每个进程只自动开一次，用户关掉后不会再弹；-Dsun.autopure=false 可关闭。
     *  ★ 必须等配对校验确认完（authPending=false）再切，否则对话框会被纯享窗口盖住 /
     *    挂在隐藏的主窗口下，用户看不到 → 卡住不出画面（2026-10-29 实测）。 */
    private volatile boolean autoPureTried = false;
    private void maybeAutoPureMode() {
        if (autoPureTried) return;
        if (authPending) return;            // 等用户在对话框点完"确定"
        if ("false".equalsIgnoreCase(System.getProperty("sun.autopure"))) return;
        if (phoneWin != null) return;
        int n = deviceBox == null ? 0 : deviceBox.getItemCount();
        if (n != 1) return;                 // 多台设备时不自动切，避免连错设备
        autoPureTried = true;
        Log.info(TAG, "auto pure mode（单设备，自动切多屏协同）");
        SwingUtilities.invokeLater(this::togglePureMode);
    }

    /** 入队：队列满就丢掉所有积压、只留最新包，并请求一个 IDR 重新同步。
     *  ★ 0.136.24：以前队列 300 不丢包，手机推 90/120fps 时解码追不上，
     *    实测队列堆到 274 → 延迟 2.7 秒。宁可闪一下也不能堆成秒级延迟。 */
    private void enqueuePkt(byte[] d) {
        if (!h264Queue.offer(new Pkt(d))) {
            // 过载：只丢最旧的一包（丢掉整队会造成 0.3–0.5 秒空窗，平板/大分辨率下非常明显）
            h264Queue.poll();
            if (!h264Queue.offer(new Pkt(d))) {
                h264Queue.clear();
                h264Queue.offer(new Pkt(d));
            }
            dropCount++;
            requestIdrThrottled("queue overflow");
        }
    }

    /** 请求关键帧（节流 800ms）：清队列后必须等 IDR 才能继续正常解码 */
    private void requestIdrThrottled(String why) {
        long now = System.currentTimeMillis();
        if (now - lastIdrRequestMs < 800) return;
        lastIdrRequestMs = now;
        final HosRemoteDevice dv = device;
        if (dv == null) return;
        bgExec.submit(() -> {
            try {
                dv.requestIDRFrame();
                Log.info(TAG, "requestIDR (" + why + "), drops=" + dropCount);
            } catch (Throwable t) {
                Log.info(TAG, "requestIDR failed: " + t);
            }
        });
    }

    /** 独立解码线程：网络回调只入队，解码在这里做，避免互相拖慢 */
    private void startDecodeThread(FfmpegDecoder dec, int myGen) {
        stopDecodeThread();
        h264Queue = new java.util.concurrent.ArrayBlockingQueue<>(12);   // 低延迟优先：只留少量缓冲
        decodeRunning = true;
        decodeThread = new Thread(() -> {
            while (decodeRunning && myGen == captureGen) {
                try {
                    Pkt pkt = h264Queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (pkt == null) continue;
                    int qs = h264Queue.size();
                    if (qs > queuePeak) queuePeak = qs;
                    bytesIn.addAndGet(pkt.data.length);    // 码率统计（HUD）
                    java.awt.image.BufferedImage img = dec.decode(pkt.data);
                    if (img != null) {
                        double pipe = (System.nanoTime() - pkt.tNs) / 1_000_000.0;
                        pipeEwma = pipeEwma <= 0 ? pipe : pipeEwma * 0.8 + pipe * 0.2;
                        // 自愈：延迟已经 >250ms 而且队列还有积压 → 丢掉积压 + 请求关键帧
                        if (pipe > 250 && h264Queue.size() > 4) {
                            h264Queue.clear();
                            dropCount++;
                            requestIdrThrottled("latency " + Math.round(pipe) + "ms");
                        }
                        onFrame(img, codecLabel);
                    }
                } catch (InterruptedException ie) {
                    break;
                } catch (Throwable t) {
                    Log.info(TAG, "decode thread error: " + t);
                }
            }
            Log.info(TAG, "decode thread exit");
        }, "decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private void stopDecodeThread() {
        decodeRunning = false;
        Thread t = decodeThread;
        decodeThread = null;
        if (t != null) t.interrupt();
    }

    /** 看门狗：H.264 连上了却一直没帧（常见于设备缺 libCPHMediaEngine.z.so），
     *  8 秒后自动拆连接、重建并改走图片流。 */
    /** 失败原因分类：决定日志口径，也方便事后统计"到底是网断、设备端服务没了，还是配对失效" */
    private static String classifyFailure(String msg) {
        String m = msg == null ? "" : msg.toLowerCase();
        if (m.contains("can not find scrcpy pid") || m.contains("uitest") || m.contains("daemon")) {
            return "DEVICE_SIDE";        // 设备端采集服务缺失/被占端口
        }
        if (m.contains("auth") || m.contains("配对") || m.contains("pin")) {
            return "AUTH";
        }
        if (m.contains("can not found device") || m.contains("network closed") || m.contains("unavailable")
                || m.contains("connection reset") || m.contains("connection refused")
                || m.contains("broken pipe") || m.contains("timeout") || m.contains("timed out")
                || m.contains("econn")) {
            return "NETWORK";
        }
        return "OTHER";
    }

    /** 窗口从最小化恢复：立刻唤醒正在等待的重连；若上一轮已放弃，再给一次机会 */
    private void onWindowRestored() {
        if (autoReconnecting) {
            wakeReconnect();
            Log.info(TAG, "window restored -> 提前触发重连（不等退避）");
            return;
        }
        if (autoReconnectGaveUp && !capturing) {
            String addr = device != null ? device.getSn() : PREF.get("lastConnectAddress", "");
            if (addr != null && !addr.trim().isEmpty()) {
                Log.info(TAG, "window restored -> 重新尝试自动重连 " + addr);
                startAutoReconnect("window restored", addr);
            }
        }
    }

    /** 唤醒 auto-reconnect 线程里等待中的退避 */
    private void wakeReconnect() {
        synchronized (reconnectLock) {
            reconnectWake = true;
            reconnectLock.notifyAll();
        }
    }

    /** 断线自动重连：tconn 回原地址 → 重新开采集。
     *  以前设备一掉线就直接放弃，画面停在最后一帧（看起来就是"卡屏"）。 */
    private void startAutoReconnect(String why, String addr) {
        if (autoReconnecting) return;
        if (addr == null || addr.trim().isEmpty()) {
            Log.info(TAG, "auto reconnect: 没有地址，跳过（" + why + "）");
            return;
        }
        final String target = addr.trim().split("[\\s,]+")[0];
        final String hb = findHdc();
        if (hb == null) {
            Log.info(TAG, "auto reconnect: 没有 hdc.exe，跳过");
            return;
        }
        final String reason = classifyFailure(why);
        autoReconnecting = true;
        autoReconnectGaveUp = false;
        reconnectWake = false;
        stopped = false;               // teardownCapture 会把 stopped 置 true，这里要恢复，否则重连线程第一步就退出
        Log.info(TAG, "auto reconnect start: " + why + " (reason=" + reason + ") -> " + target);
        hintLater("连接断开（" + reason + "），正在自动重连…");
        Thread th = new Thread(() -> {
            final int[] delays = {2, 3, 5, 8, 12, 15, 15, 15, 15};   // 先快后慢，合计 90 秒（1.5 分钟）
            for (int i = 0; i < delays.length; i++) {
                if (!autoReconnecting) return;
                try {
                    // 用 wait 代替 sleep：窗口从最小化恢复时可以立刻打断退避（wakeReconnect）
                    synchronized (reconnectLock) {
                        if (!reconnectWake) reconnectLock.wait(delays[i] * 1000L);
                        reconnectWake = false;
                    }
                } catch (InterruptedException ie) { return; }
                if (!autoReconnecting) return;
                String cur = target;
                if (target.startsWith("192.168.137.") && i >= 3) {
                    String lan = PREF.get("lastConnectAddress", "");
                    if (lan != null && !lan.trim().isEmpty()) {
                        cur = lan.trim().split("[\\s,]+")[0];
                        Log.info(TAG, "auto reconnect: P2P 连续失败，改用 LAN " + cur);
                    }
                }
                String out = null, tg = null;
                try {
                    utils.ProcessExecutor ex = new utils.ProcessExecutor();
                    out = ex.callProcess(new String[]{hb, "tconn", cur}, 8);
                    tg = ex.callProcess(new String[]{hb, "list", "targets"}, 8);
                } catch (Throwable t) {
                    Log.info(TAG, "auto reconnect exec error: " + t);
                }
                // ★ 旧判断只认 "OK"/"Connect" 两个大写词，但设备已连接时 hdc 返回的是
                //   "Target is connected, repeat operation"（全小写 connect）→ 被误判为失败，
                //   于是明明连上了还空转 90 秒（21:36 实测：present=true 却连试 9 次）。
                //   以 "list targets 里真的出现" 为准，tconn 只当副作用。
                String ol = out == null ? "" : out.toLowerCase();
                boolean present = tg != null && tg.contains(cur);
                boolean ok = present || ol.contains("ok") || ol.contains("connect");
                Log.info(TAG, "auto reconnect #" + (i + 1) + " tconn=" + oneLine(out)
                        + " present=" + present + " verdict=" + (ok && present ? "OK" : "retry"));
                if (ok && present) {
                    autoReconnecting = false;
                    autoReconnectGaveUp = false;
                    hintLater("已重连，正在恢复画面…");
                    Log.info(TAG, "auto reconnect ok, restart capture (after " + (i + 1) + " tries)");
                    SwingUtilities.invokeLater(this::startCapture);
                    return;
                }
                hintLater("连接断开，正在自动重连…（第 " + (i + 1) + " 次）");
            }
            autoReconnecting = false;
            autoReconnectGaveUp = true;
            hintLater("自动重连失败（已等 1 分钟）：确认手机无线调试仍开启；把窗口最小化再恢复会再试一次，或点 ＋ 重新连接");
            Log.info(TAG, "auto reconnect gave up (window restore will retry once)");
        }, "auto-reconnect");
        th.setDaemon(true);
        th.start();
    }

    private void armNoFrameWatchdog(Device d, int myGen) {
        new Thread(() -> {
            try { Thread.sleep(20000); } catch (InterruptedException ignored) { return; }   // SDK 起流要 3-8 秒，20 秒才判无数据
            if (myGen != captureGen) return;        // 已经换过会话
            if (frameCount > 0) return;             // 有帧，不用管
            if (rawPackets > 0) {
                // 有数据却出不了帧：可能是 HEVC 码流，换解码器再试一次
                // ★ 但 SDK(H.264) 那条路绝不能猜：转屏/切页时会有短暂无帧，
                //   一猜就把 H.264 解码器切走 → 黑屏（就是"转回来绷了"的原因 ✗）
                FfmpegDecoder dec = decoder;
                if (!tcpStreaming) {
                    Log.info(TAG, "sdk path: data flows but no frame; do NOT switch codec");
                    return;
                }
                if (dec != null && !dec.isHevc() && !hevcTried) {
                    hevcTried = true;
                    SwingUtilities.invokeLater(() -> screenView.setHint("尝试 H.265 解码器…"));
                    new Thread(() -> {
                        try {
                            dec.switchToHevc();
                        } catch (Throwable t) {
                            Log.info(TAG, "switch hevc failed: " + t);
                        }
                    }, "hevc-switch").start();
                    return;
                }
                // 数据在流动，只是还没解出画面 —— 不要降级，继续等并报告
                Log.info(TAG, "data flows (" + rawPackets + " packets) but no decoded frame yet; keep waiting");
                SwingUtilities.invokeLater(() ->
                        screenView.setHint("收到 " + rawPackets + " 个 H.264 包，正在等解码出帧…"));
                return;
            }
            // ★ 锁定判断放最前：锁定了就绝不降级（不管有没有出过帧）
            boolean locked = modeBox != null && String.valueOf(modeBox.getSelectedItem()).contains("锁定");
            boolean forcedImage = modeBox != null && "图片流".equals(String.valueOf(modeBox.getSelectedItem()));
            if (locked && !forcedImage) {
                Log.info(TAG, "locked 模式：不降级，继续等/重启采集（frameCount=" + frameCount + "）");
                if (myGen == captureGen) {
                    teardownCapture();
                    try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                    startCapture();
                }
                return;
            }
            if (frameCount > 0) {
                Log.info(TAG, "no data in 20s but frameCount=" + frameCount + " locked=" + locked
                        + " -> restart capture (NOT image mode)");
                if (myGen == captureGen) {
                    teardownCapture();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                    startCapture();
                }
                return;
            }
            // ★ 从没出过帧、也没收到过任何包：这是"SDK 连上了假会话"（设备端残留服务/
            //   弱网把握手拖死）。先清残留服务硬重连一次，不要直接降级成图片流 ——
            //   图片流只有 1fps，用户看到的就是"卡成 PPT"。
            if (noFrameRetries < 1) {
                noFrameRetries++;
                Log.info(TAG, "no data in 20s (never had a frame) -> hard reconnect #" + noFrameRetries);
                SwingUtilities.invokeLater(() -> screenView.setHint("视频流没数据，正在清理重连…"));
                if (myGen == captureGen) {
                    daemonCleaned = false;      // 强制下次连接前清设备端残留 uitest/scrcpy
                    teardownCapture();
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                    startCapture();
                }
                return;
            }
            Log.info(TAG, "no data in 20s -> fallback to image mode (never had a frame, retry exhausted)");
            SwingUtilities.invokeLater(() ->
                    screenView.setHint("视频流无数据（设备缺 libCPHMediaEngine.z.so），切换图片流…"));
            teardownCapture();
            
            try { Thread.sleep(600); } catch (InterruptedException ex) { Log.info(TAG, "ignored: " + ex); }
            startImageOnly(d, ++captureGen);
        }, "watchdog").start();
    }

    /** 只走图片流（重建连接，用在视频流确认不可用之后） */
    private void startImageOnly(Device d, int myGen) {
        stopped = false;
        frameCount = 0;
        warmupUntil = System.currentTimeMillis() + 2500;
        new Thread(() -> {
            try {
                HosRemoteConfig config = new HosRemoteConfig(d.getSn());
                // 回到原版：不设 setHdcPath（设了反而让 SDK 找不到设备）
                if (d.getIp() != null) config.setIp(d.getIp());
                // 官方文档：setHdcPath 要传 hdc.exe 的完整路径
                // 不设 setHdcPort（昨晚可用版本也没设；设了反而报 device not online）
                try {
                    String hb = findHdc();
                    if (hb != null) {
                        config.setHdcPath(new java.io.File(hb).getAbsolutePath());
                        Log.info(TAG, "main hdcPath=" + new java.io.File(hb).getAbsolutePath());
                    }
                    Log.info(TAG, "main hdcPath ok（不设 hdcPort）");
                } catch (Throwable t) { Log.info(TAG, "setHdcPath/Port failed: " + t); }
                try {
                    String q = qualityBox == null ? "原始" : (String) qualityBox.getSelectedItem();
                    float scale = "75%".equals(q) ? 0.75f : "50%".equals(q) ? 0.5f : "35%".equals(q) ? 0.35f : 1.0f;
                    if (scale < 1.0f) {
                        // 官方 API：setScale(int)，2=1/2、3=1/3，最大 5
                        int sc = scale <= 0.35f ? 3 : scale <= 0.5f ? 2 : 1;
                        config.setScale(sc);
                        Log.info(TAG, "setScale(" + sc + ") for quality scale=" + scale);
                        Log.info(TAG, "stream scale = " + scale);
                    }
                } catch (Throwable ignored) {
                    Log.info(TAG, "scale not supported: " + ignored);
                }
                device = new HosRemoteDevice(config);
                startImageCapture(d, myGen);
                Log.info(TAG, "switched to image mode");
            } catch (Exception ex) {
                Log.error(TAG, "image only start failed", ex);
            }
        }, "image-only").start();
    }

    /** 图片流（约 2-3fps）：视频流不可用时的降级方案 */
    private void startImageCapture(Device d, int myGen) {
        codecLabel = "图片流";
        try {
            if (device == null) return;
            device.startImageScreenCapture(new ScreenCapCallback() {
                @Override public void onData(ByteBuffer bb) {
                    if (stopped || myGen != captureGen) return;
                    try {
                        byte[] bytes = new byte[bb.remaining()];
                        bb.get(bytes);
                        java.awt.image.BufferedImage img =
                                ImageIO.read(new ByteArrayInputStream(bytes));
                        if (img != null) {
                            onFrame(img, "图片流");
                        }
                    } catch (Exception ex) {
                        Log.info(TAG, "decode image failed: " + ex);
                    }
                }

                @Override public void onException(Throwable t) {
                    Log.error(TAG, "image stream error", t);
                    if (myGen != captureGen) return;
                    SwingUtilities.invokeLater(() -> {
                        connecting = false;
                        capturing = false;
                        if (enterBtn != null) enterBtn.setText("进入投屏");
                        screenView.setHint("获取图片流失败: " + t.getMessage());
                        setConnected(false);
                    });
                }

                @Override public void onReady() {
                    connecting = false;
                    capturing = true;
                    SwingUtilities.invokeLater(() -> {
                        if (enterBtn != null) enterBtn.setText("停止投屏");
                        screenView.setHint("");
                    });
                    Log.info(TAG, "image stream ready");
                }
            });
        } catch (Exception ex) {
            Log.error(TAG, "start image capture failed", ex);
        }
    }

    /** A 路卡死自愈：连续 6 秒没有新帧 → 重建采集（30 秒冷却，最多恢复到能看） */
    private void stallWatchdog() {
        if (!capturing || tcpStreaming) return;          // TCP 路有自己的重连
        if (device == null) return;
        long idle = System.currentTimeMillis() - lastFrameAt;
        if (lastFrameAt == 0 || idle < 6000) return;
        long now = System.currentTimeMillis();
        // ★ 静止画面本来就不出帧（SDK 协议固有行为）
        //   所以只有在"确实很久没有任何数据"时才重启，且间隔逐步放宽（6s→12s→24s→30s）
        long cooldown = Math.min(30000, 6000L * (1L << Math.min(3, stallRestarts)));
        if (now - stallWatchdogAt < cooldown) return;
        stallRestarts++;
        stallWatchdogAt = now;
        // ★ 官方 API：requestIDRFrame() 请求一个关键帧（比拆了重建轻得多）
        try {
            if (device != null) {
                device.requestIDRFrame();
                Log.info(TAG, "stall " + (idle / 1000) + "s -> requestIDRFrame()");
                return;
            }
        } catch (Throwable t) {
            Log.info(TAG, "requestIDRFrame failed: " + t);
        }
        Log.info(TAG, "stall " + (idle / 1000) + "s -> restart capture");
        SwingUtilities.invokeLater(() -> {
            screenView.setHint("画面卡住 " + (idle / 1000) + " 秒，正在自动恢复…");
            bgExec.submit(() -> {
                teardownCapture();
                try { if (device != null) device.stopCaptureScreen(); } catch (Throwable ignored) { }
                device = null;
                startCapture();
            });
        });
    }

    /** 状态：超过 3 秒没帧就提示无数据 */
    private void updateAgeLabel() {
        if (ageLabel == null) return;
        if (!capturing || lastFrameAt == 0) {
            ageLabel.setText("");
            return;
        }
        long age = System.currentTimeMillis() - lastFrameAt;
        if (age < 3000) {
            ageLabel.setForeground(OK);
            ageLabel.setText("画面 "+ (age / 100) / 10.0 + "s 前");
            } else if (tcpStreaming) {
                // 推流端是"画面变化才出帧"，静止时无新帧属正常
                ageLabel.setForeground(FG_DIM);
                ageLabel.setText("静止 " + age / 1000 + "s（屏幕没变化）");
            } else {
                ageLabel.setForeground(ERR);
                ageLabel.setText("无数据 " + age / 1000 + "s");
            }
    }

    /** 截图：把当前画面存成 PNG */
    private void saveScreenshot() {
        java.awt.image.BufferedImage img = screenView.getImage();
        if (img == null) {
            screenView.setHint("还没有画面，先进入投屏");
            return;
        }
        final java.awt.image.BufferedImage snapshot = img;
        screenView.setHint("正在保存截图…");
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(System.getProperty("user.dir"), "screenshots");
                if (!dir.exists()) dir.mkdirs();
                java.io.File f = new java.io.File(dir,
                        "shot_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".png");
                ImageIO.write(snapshot, "png", f);      // 写盘放后台，别卡 EDT
                Log.info(TAG, "screenshot saved: " + f.getAbsolutePath());
                SwingUtilities.invokeLater(() -> {
                    screenView.setHint("已保存截图：" + f.getName());
                    JOptionPane.showMessageDialog(this, "已保存到\n" + f.getAbsolutePath(),
                            "截图", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception ex) {
                Log.error(TAG, "save screenshot failed", ex);
                SwingUtilities.invokeLater(() -> screenView.setHint("截图失败：" + ex.getMessage()));
            }
        }, "screenshot").start();
    }

    /** 录制：把收到的画面按 ≤5fps 存成 PNG 序列（不是 mp4，保证可靠） */
    private void toggleRecording() {
        if (!recording) {
            recordDir = new java.io.File(System.getProperty("user.dir"),
                    "record_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()));
            if (!recordDir.exists() && !recordDir.mkdirs()) {
                screenView.setHint("无法创建录制目录");
                return;
            }
            recordIndex = 0;
            recording = true;
            screenView.setHint("开始录制（PNG 序列）→ " + recordDir.getName());
            Log.info(TAG, "recording started: " + recordDir.getAbsolutePath());
        } else {
            recording = false;
            screenView.setHint("录制结束，共 " + recordIndex + " 帧：" + (recordDir == null ? "" : recordDir.getAbsolutePath()));
            Log.info(TAG, "recording stopped, frames=" + recordIndex);
        }
    }

    /** 读本机剪贴板 → 发到设备（uitest uiInput text，支持中文） */
    private void pasteClipboardToDevice() {
        HosRemoteDevice dev = device;
        if (dev == null) {
            screenView.setHint("未连接设备");
            return;
        }
        try {
            java.awt.datatransfer.Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                screenView.setHint("剪贴板没有文本");
                return;
            }
            String content = (String) cb.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (content == null || content.isEmpty()) {
                screenView.setHint("剪贴板为空");
                return;
            }
            content = sanitize(content);
            final String text = content.length() > 2000 ? content.substring(0, 2000) : content;
            touchExec.submit(() -> {
                try {
                    dev.executeShellCommand("uitest uiInput text '" + text + "'", 10);
                    Log.info(TAG, "pasted " + text.length() + " chars to device");
                } catch (Exception ex) {
                    Log.error(TAG, "paste failed", ex);
                }
            });
            screenView.setHint("已粘贴 " + text.length() + " 字到设备");
        } catch (Exception ex) {
            screenView.setHint("读取剪贴板失败：" + ex.getMessage());
        }
    }

    /** 反射查一次就缓存（原来每次触摸都 getMethods()，高频拖动时是明显开销） */
    private final java.util.Map<String, java.lang.reflect.Method> touchMethods =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> touchMissing =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean touchDownFlag = false;   // 有 down 还没配对 up
    private volatile int touchDownX, touchDownY;
    private volatile long touchDownAtMs = 0;
    private volatile boolean touchWatchdogOn = false;

    private java.lang.reflect.Method touchMethod(HosRemoteDevice dev, String name) {
        java.lang.reflect.Method cached = touchMethods.get(name);
        if (cached != null) return cached;
        if (touchMissing.contains(name)) return null;
        for (java.lang.reflect.Method cand : dev.getClass().getMethods()) {
            if (cand.getName().equals(name) && cand.getParameterCount() == 2) {
                touchMethods.put(name, cand);
                return cand;
            }
        }
        touchMissing.add(name);
        return null;
    }

    /** 用反射调 onTouchXxx(x, y)：SDK 里触摸方法签名不确定，用反射避免编译期报错 */
    private boolean callTouch(HosRemoteDevice dev, String name, int x, int y) {
        if (!touchAvailable && touchChecked) return false;
        try {
            java.lang.reflect.Method m = touchMethod(dev, name);
            if (!touchChecked) {
                touchChecked = true;
                touchAvailable = m != null;
                Log.info(TAG, "touch api " + name + " available=" + touchAvailable);
            }
            if (m == null) return false;
            // ★ 上一次 down 没配上 up（丢事件/切换设备/卡住）→ 先补一个 up，
            //   否则设备端会一直处于"按下"状态，表现就是"点了没反应/卡住"。
            if ("onTouchDown".equals(name) && touchDownFlag) {
                int px = touchDownX, py = touchDownY;
                touchDownFlag = false;
                java.lang.reflect.Method up = touchMethod(dev, "onTouchUp");
                if (up != null) {
                    try {
                        up.invoke(dev, px, py);
                        Log.info(TAG, "touch: 补发遗漏的 up @" + px + "," + py + "（上一次 down 没抬起）");
                    } catch (Throwable ignored) { }
                }
            }
            m.invoke(dev, x, y);
            if ("onTouchDown".equals(name)) {
                touchDownFlag = true;
                touchDownX = x; touchDownY = y; touchDownAtMs = System.currentTimeMillis();
                ensureTouchWatchdog();
            } else if ("onTouchUp".equals(name)) {
                touchDownFlag = false;
            }
            return true;
        } catch (Throwable t) {
            if (!touchChecked) {
                touchChecked = true;
                touchAvailable = false;
            }
            return false;
        }
    }

    /** 看门狗：down 超过 2.5 秒没有 up（鼠标在外面抬起/窗口失焦）→ 补发 up */
    private void ensureTouchWatchdog() {
        if (touchWatchdogOn) return;
        touchWatchdogOn = true;
        java.util.Timer t = new java.util.Timer("touch-watchdog", true);
        t.schedule(new java.util.TimerTask() {
            @Override public void run() {
                if (!touchDownFlag) return;
                long held = System.currentTimeMillis() - touchDownAtMs;
                if (held < 2500) return;
                HosRemoteDevice dv = device;
                touchDownFlag = false;
                if (dv == null) return;
                Log.info(TAG, "touch watchdog: down " + held + "ms 没抬起，补发 onTouchUp @" + touchDownX + "," + touchDownY);
                touchExec.submit(() -> {
                    try { callTouch(dv, "onTouchUp", touchDownX, touchDownY); } catch (Throwable ignored) { }
                });
            }
        }, 500, 500);
    }

    /** 优先取整个底层数组（和旧版一致）；没有数组时才按 remaining 拷贝 */
    private static byte[] readBuffer(ByteBuffer bb) {
        if (bb.hasArray()) {
            return bb.array();
        }
        byte[] out = new byte[bb.remaining()];
        bb.get(out);
        return out;
    }

    /** 可打印字符：走 uiInput text（大小写、符号都自然正确） */
    /** 主窗口 / 纯享 / 迷你窗口共用的 PC 键盘处理器 */
    private final java.awt.event.KeyAdapter pcKeyHandler = new java.awt.event.KeyAdapter() {
        @Override public void keyTyped(java.awt.event.KeyEvent e) {
            if (e.isControlDown() || e.isMetaDown() || e.isAltDown()) return;
            sendPrintable(e.getKeyChar());
        }
        @Override public void keyPressed(java.awt.event.KeyEvent e) {
            sendFunctionKey(e);
        }
    };

    // 打字缓冲：连续字符合并成一条 uitest text，避免一个字一次 shell
    private final StringBuilder typeBuf = new StringBuilder();
    private volatile long lastTypeAt = 0;
    private javax.swing.Timer typeFlush;

    private void initTypeFlush() {
        typeFlush = new javax.swing.Timer(60, e -> {
            if (typeBuf.length() > 0 && System.currentTimeMillis() - lastTypeAt >= 70) flushTypeBuf();
        });
        typeFlush.start();
    }

    private void flushTypeBuf() {
        if (typeBuf.length() == 0) return;
        String raw = typeBuf.toString();
        typeBuf.setLength(0);
        HosRemoteDevice dev = device;
        if (dev == null) return;
        final String text = sanitize(raw);
        if (text.isEmpty()) return;
        touchExec.submit(() -> {
            try { dev.executeShellCommand("uitest uiInput text '" + text + "'", 6); }
            catch (Exception ex) { Log.info(TAG, "type ignored: " + ex); }
        });
    }

    private void sendPrintable(char c) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        if (c < 32) return;                     // 控制字符交给功能键
        typeBuf.append(c);                      // 中文/英文都进缓冲，合并外发
        lastTypeAt = System.currentTimeMillis();
        if (typeBuf.length() >= 80) flushTypeBuf();
    }

    /** 功能键（keyPressed 才有正确的 keyCode） */
    private void sendFunctionKey(java.awt.event.KeyEvent e) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        flushTypeBuf();                          // 先把已缓冲的字符发出去，保证顺序
        if (KeyCodeUtil.isPasteAction(e)) {   // Ctrl+V → 粘贴本机剪贴板
            pasteClipboardToDevice();
            return;
        }
        if (!KeyCodeUtil.isSupport(e)) return;
        int code = KeyCodeUtil.getKeyCode(e);
        if (code <= 0) return;
        touchExec.submit(() -> {
            
            try { dev.executeShellCommand("uinput -K -d " + code + " -u " + code, 5); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
        });
    }

    /** 空白光标：鼠标在手机画面上时不显示 Windows 指针（hover 效果由设备渲染） */
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

    /** 防止把 shell 特殊字符带进单引号命令里导致命令被截断 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("'", "")
                .replace("\\", "")
                .replace("$", "")
                .replace("`", "")
                .replace("\"", "");
    }
    /** 统一的收帧处理：更新画面 + 统计 fps */
    private volatile int lastShownW = 0;
    private volatile int lastShownH = 0;
    private final java.util.concurrent.atomic.AtomicBoolean edtPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);   // EDT 是否还有一帧没画完
    private volatile double dispScale = 0;          // 显示像素 / 源像素（竖屏基准，横屏沿用）
    private volatile boolean lastOrientWasLandscape = false;
    private volatile boolean autoPathSwitch = false;     // A/B 路自动切换（竖屏A，横屏B）
    private volatile long lastPathSwitchMs = 0;

    private void onFrame(java.awt.image.BufferedImage img, String mode) {
        if (img != null) {
            lastFrameW = img.getWidth();
            lastFrameH = img.getHeight();
        }
        if (img != null && (img.getWidth() != lastShownW || img.getHeight() != lastShownH)) {
            lastShownW = img.getWidth();
            lastShownH = img.getHeight();
            boolean landscapeNow = img.getWidth() > img.getHeight();
            maybeAutoSwitchPath(landscapeNow);
            final int fw2 = img.getWidth(), fh2 = img.getHeight();
            SwingUtilities.invokeLater(() -> screenView.setHint("画面 " + fw2 + "x" + fh2));
            // ★ 折叠/展开、转屏都会改分辨率 → 必须同步刷新设备真实尺寸，
            //   否则触摸坐标还按旧尺寸换算 → 展开态点哪都不灵 ✗
            bgExec.submit(this::refreshDeviceSize);
        }
        // 只对 H.265 推流应用色度校正（H.264 通道本身颜色正确，不能套）
        // 色度校正在 surface 直连模式下已不需要（颜色由系统转换保证）
        if ("true".equalsIgnoreCase(System.getProperty("sun.dump")) && dumpDecoded < 60 && img != null) {
            dumpDecoded++;
            try {
                java.io.File dir = dumpDirFile();
                if (!dir.exists()) dir.mkdirs();
                String tag = mode.contains("H.265") ? "h265" : (mode.contains("H.264") ? "h264" : "other");
                javax.imageio.ImageIO.write(img, "png",
                        new java.io.File(dir, "decoded-" + tag + "-" + dumpDecoded + ".png"));
                Log.info(TAG, "dumped decoded-" + dumpDecoded + ".png " + img.getWidth() + "x" + img.getHeight());
            } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
        }
        frameCount++;
        lastFrameAt = System.currentTimeMillis();
        // 帧间隔抖动/顿挫：链路抖（网差）会让出帧间隔忽长忽短；解码慢则是"帧率低但间隔均匀"
        long arrNs = System.nanoTime();
        if (lastArrivalNs != 0) {
            double dtMs = (arrNs - lastArrivalNs) / 1_000_000.0;
            if (dtMs > 0 && dtMs < 2000) {
                if (intervalEwma <= 0) {
                    intervalEwma = dtMs;
                } else {
                    intervalEwma = intervalEwma * 0.9 + dtMs * 0.1;
                }
                double dev = Math.abs(dtMs - intervalEwma);
                jitterEwma = jitterEwma <= 0 ? dev : jitterEwma * 0.9 + dev * 0.1;
                if (dtMs > 250) gapCount++;      // 顿挫：>250ms 才出下一帧
            }
        }
        lastArrivalNs = arrNs;
        // 单设备：真正出画面之后再自动切"多屏协同"（此时配对校验一定已经确认过了）
        if (frameCount >= 3 && frameCount % 15 == 0) maybeAutoPureMode();
        // 出第二帧时做一次滚轮通道诊断：借 SDK 内部 socket 发一条 AxisStop，把 agent 回包打出来
        if (frameCount == 2 && !uitestProbeDone) {
            uitestProbeDone = true;
            final int pw = Math.max(1, lastFrameW / 2), ph = Math.max(1, lastFrameH / 2);
            touchExec.submit(() -> probeWheelAgent(pw, ph));
        }
        // 触摸注入 → 下一帧到达：这就是用户能感知到的端到端延迟（含手机处理/编码/网络/解码）
        long tn = lastTouchNs;
        if (tn > 0) {
            lastTouchNs = 0;
            long tapMs = (System.nanoTime() - tn) / 1_000_000;
            if (tapMs >= 0 && tapMs < 3000) {
                Log.info(TAG, "tap->frame " + tapMs + "ms");
            }
        }
        stallRestarts = 0;
        noFrameRetries = 0;   // 真出帧了，把"无首帧重连"计数清零

        if (recording && recordDir != null && System.currentTimeMillis() - lastRecordAt >= 200) {
            lastRecordAt = System.currentTimeMillis();
            try {
                // 复制一份再写盘，避免解码线程同时修改造成半帧
                java.awt.image.BufferedImage copy = new java.awt.image.BufferedImage(
                        img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
                java.awt.Graphics2D g = copy.createGraphics();
                g.drawImage(img, 0, 0, null);
                g.dispose();
                ImageIO.write(copy, "png", new java.io.File(recordDir,
                        String.format("frame_%05d.png", ++recordIndex)));
            } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); }
        }
        fpsFrames++;
        long now = System.currentTimeMillis();
        String fpsText = null;
        if (now - fpsStamp >= 1000) {
            double fpsValue = fpsFrames * 1000.0 / (now - fpsStamp);
            fpsSmooth[fpsSmoothIdx] = fpsValue;
            fpsSmoothIdx = (fpsSmoothIdx + 1) % fpsSmooth.length;
            double avg = 0; int cnt = 0;
            for (double v : fpsSmooth) { if (v > 0) { avg += v; cnt++; } }
            double fpsShown = cnt == 0 ? fpsValue : avg / cnt;
            fpsText = String.format("%.1f fps", fpsShown);
            // ---- 性能 HUD：本周期码率 / 解码耗时 / 抖动 / 顿挫 ----
            long spanMs = Math.max(1, now - fpsStamp);
            double instMbps = bytesIn.getAndSet(0) * 8.0 / (spanMs / 1000.0) / 1_000_000.0;
            hudMbps = instMbps;
            // 静态画面时 SDK 几乎不发包，瞬时码率会掉到 0 —— 用 3 秒平滑值避免误读
            hudRateSmooth = hudRateSmooth <= 0 ? instMbps : hudRateSmooth * 0.6 + instMbps * 0.4;
            hudPipeMs = pipeEwma;
            hudQueue = queuePeak;
            queuePeak = 0;
            // 丢包/积压突然变多：提示降档（平板 3120×2080 在 50% 档也可能压力大）
            if (dropCount - lastDropSeen >= 3 && System.currentTimeMillis() - lastDropHintAt > 15000) {
                lastDropHintAt = System.currentTimeMillis();
                hintLater("画面压力较大（已丢弃 " + (dropCount - lastDropSeen) + " 帧），可切到 33% 档");
            }
            lastDropSeen = dropCount;
            final FfmpegDecoder dec0 = decoder;
            hudDecodeMs = dec0 != null ? dec0.getDecodeMs() : 0;
            hudJitterMs = jitterEwma;
            hudGaps = gapCount;
            gapCount = 0;
            // 纯享/小窗的性能 HUD（☰ → 性能信息 打开）
            final String hudLine = String.format("%.1ffps  dec %.0fms  jit %.0fms  lat %.0fms  q%d  %.1fMb",
                    fpsShown, hudDecodeMs, hudJitterMs, hudPipeMs, hudQueue, hudRateSmooth);
            lastHudText = hudLine;
            SwingUtilities.invokeLater(() -> {
                PhoneWindow pw0 = phoneWin, mw0 = miniWin;
                if (pw0 != null) pw0.setHudText(hudLine);
                if (mw0 != null) mw0.setHudText(hudLine);
            });
            if (System.currentTimeMillis() < warmupUntil) {
                Log.info(TAG, "fps warming up (" + mode + ") total=" + frameCount);
            } else {
                Log.info(TAG, "fps " + fpsText + " (" + mode + ") total=" + frameCount
                        + " dec=" + String.format("%.1f", hudDecodeMs) + "ms"
                        + " jit=" + String.format("%.1f", hudJitterMs) + "ms"
                        + " lat=" + String.format("%.1f", hudPipeMs) + "ms"
                        + " q=" + hudQueue
                        + " gaps=" + hudGaps
                        + " rate=" + String.format("%.1f", hudMbps) + "Mbps"
                        + " avg=" + String.format("%.1f", hudRateSmooth) + "Mbps"
                        + " drops=" + dropCount);
            }
            SwingUtilities.invokeLater(() -> {
                if (spark != null) spark.addSample(fpsValue);
                if (hwLabel != null && dec0 != null) {
                    boolean hw = dec0.isHwActive();
                    hwLabel.setText(hw ? "HW" : "SW");
                    hwLabel.setForeground(hw ? new Color(0x19C37D) : FG_DIM);
                }
            });
            fpsFrames = 0;
            fpsStamp = now;
        }
        if (frameCount <= 3 || frameCount % 120 == 0) {
            Log.info(TAG, "frame#" + frameCount + " " + img.getWidth() + "x" + img.getHeight() + " (" + mode + ")");
        }
        final String ft = fpsText;
        final int w = img.getWidth(), h = img.getHeight();
        // 首次出帧：分辨率过大时提示降档（原画 2816p 在普通 PC 上解码压力大）
        if (frameCount == 30 && (long) w * h > 1_200_000L) {
            SwingUtilities.invokeLater(() -> screenView.setHint(
                    "当前分辨率较高（" + w + "×" + h + "），若卡顿请把手机画质切「高清」"));
        }
        // 同步"正在显示"的缓冲索引（EDT 上 image 引用的就是它）
        java.awt.image.BufferedImage cur = screenView.getShownImage();
        for (int i = 0; i < 3; i++) {
            if (cur != null && bufs[i] == cur) { displayedIdx = i; break; }
        }
        // 三缓冲：写"不在显示中、也不在待显示"的那一块，彻底避免边写边画造成撕裂
        if (bufs[0] == null || bufs[0].getWidth() != w || bufs[0].getHeight() != h) {
            for (int i = 0; i < 3; i++) {
                bufs[i] = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
            }
            writeIdx = 0;
            displayedIdx = -1;
            pendingIdx = -1;
        }
        // 选一块既不是当前显示的、也不是刚发布待显示的后台缓冲
        int pick = (writeIdx + 1) % 3;
        while (pick == displayedIdx || pick == pendingIdx) {
            pick = (pick + 1) % 3;
        }
        writeIdx = pick;
        final java.awt.image.BufferedImage shown = bufs[pick];
        java.awt.Graphics2D gb = shown.createGraphics();
        gb.drawImage(img, 0, 0, null);
        gb.dispose();
        frontBuf = shown;
        pendingIdx = pick;

        // ★ 在解码线程里就把帧缩放到"窗口实际显示尺寸"，EDT 只做 1:1 拷贝
        //   —— 否则 EDT 每帧要缩放 2816p，耗时 40-80ms，缓冲被复用 → 撕裂
        // ★ 0.136.28：纯享/多屏协同窗口开着时主窗口是隐藏的，再缩一份 displayBuf 纯属浪费，
        //   平板（1560×1040 起）上这一步能省 3–6ms/帧，正好用来消掉 drops。
        if (phoneWin != null) return;
        int vw = screenView.getWidth(), vh = screenView.getHeight();
        if (vw > 0 && vh > 0) {
            // 显示比例跟随"竖屏时的缩放比例"：
            //   竖屏算出一个 scale（显示像素 / 源像素），横屏沿用同一个 scale
            //   → 横屏画面大小和竖屏观感一致，不会突然变巨大 ✓
            double ar = 1.0 * w / h;
            if (dispScale <= 0 && w > h) {
                // 第一帧就是横屏：按高度的 85% 定比例
                dispScale = (vh - 24) * 0.85 / h;
                lastOrientWasLandscape = true;
            }
            if (dispScale <= 0 || (h > w && lastOrientWasLandscape)) {
                // 竖屏：按可用高度算
                int pw = (int) Math.min(vw - 12, ar * (vh - 12));
                dispScale = (double) pw / w;
                lastOrientWasLandscape = false;
            } else if (w > h) {
                // ★ 横屏：按高度的 85% 定比例，不要撑满宽度（撑满会显得很大 ✗）
                double scaleFitH = (vh - 24) * 0.85 / h;
                dispScale = Math.min(dispScale > 0 ? dispScale : scaleFitH, scaleFitH);
                lastOrientWasLandscape = true;
            }
            int dw = (int) Math.round(w * dispScale);
            int dh = (int) Math.round(h * dispScale);
            // 超出面板就等比缩到能放下（横屏也不会溢出去）
            if (dw > vw - 12 || dh > vh - 12) {
                double fit = Math.min((double) (vw - 12) / dw, (double) (vh - 12) / dh);
                dw = (int) (dw * fit);
                dh = (int) (dh * fit);
            }
            if (dw <= 0 || dh <= 0) return;
            // 横竖屏切换时，设备屏幕尺寸跟着换（坐标换算用）
            if (devScreenW > 0 && devScreenH > 0) {
                boolean frameLandscape = w > h;
                boolean devLandscape = devScreenW > devScreenH;
                if (frameLandscape != devLandscape) {
                    int tmp = devScreenW; devScreenW = devScreenH; devScreenH = tmp;
                    Log.info(TAG, "devScreen rotated -> " + devScreenW + "x" + devScreenH);
                }
            }
            if (dwBufW != dw || dhBufH != dh || displayBuf == null) {
                displayBuf = new java.awt.image.BufferedImage(dw, dh,
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                dwBufW = dw;
                dhBufH = dh;
            }
            long t0 = System.nanoTime();
            if (dw == img.getWidth() && dh == img.getHeight()) {
                // 1:1 时直接拷像素（比 drawImage 快 5-10 倍）
                try {
                    java.awt.image.Raster srcR = img.getRaster();
                    java.awt.image.WritableRaster dstR = displayBuf.getRaster();
                    int[] row = new int[Math.min(img.getWidth() * 3, 1 << 16)];
                    int bands = Math.min(srcR.getNumBands(), dstR.getNumBands());
                    for (int y = 0; y < dh; y++) {
                        int w2 = Math.min(row.length / bands, dw);
                        srcR.getPixels(0, y, w2, 1, row);
                        dstR.setPixels(0, y, w2, 1, row);
                        if (w2 < dw) {
                            int[] rest = new int[(dw - w2) * bands];
                            srcR.getPixels(w2, y, dw - w2, 1, rest);
                            dstR.setPixels(w2, y, dw - w2, 1, rest);
                        }
                    }
                } catch (Throwable t) {
                    java.awt.Graphics2D dg = displayBuf.createGraphics();
                    dg.drawImage(img, 0, 0, null);
                    dg.dispose();
                }
            } else {
                java.awt.Graphics2D dg = displayBuf.createGraphics();
                // 缩小用最近邻（比 BILINEAR 快很多，小尺寸下肉眼差别不大）
                dg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                dg.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                        java.awt.RenderingHints.VALUE_RENDER_SPEED);
                dg.drawImage(img, 0, 0, dw, dh, null);
                dg.dispose();
            }
            long dt = (System.nanoTime() - t0) / 1000000;
            if (dt > 8 && (frameCount % 60 == 0)) {
                Log.info(TAG, "scale cost " + dt + " ms (" + img.getWidth() + "x" + img.getHeight()
                        + " -> " + dw + "x" + dh + ")");
            }
            displayRect = new java.awt.Rectangle((vw - dw) / 2, (vh - dh) / 2, dw, dh);
        }
        // ★ 低延迟关键：EDT 还有一帧没画完就丢弃本帧（不排队）
        //   否则 EDT 慢时任务会越积越多，画面滞后越来越大 ✗
        if (!edtPending.compareAndSet(false, true)) return;
        final long edtSubmit = System.nanoTime();
        SwingUtilities.invokeLater(() -> {
            try {
                screenView.setImage(shown);
                screenView.setDisplay(displayBuf, displayRect);
                if (frameInfo != null) {
                    if (ft != null) {
                        // HUD：分辨率 / 帧率 / 解码耗时 / 抖动 / 顿挫 / 码率
                        frameInfo.setText(String.format("%d×%d  %s  dec %.0fms  jit %.0fms  lat %.0fms  q%d  %.1fMb",
                                w, h, ft, hudDecodeMs, hudJitterMs, hudPipeMs, hudQueue, hudRateSmooth));
                        frameInfo.setToolTipText("解码耗时 / 帧间隔抖动 / 每秒顿挫次数（>250ms 才出帧）/ 码率 Mbps");
                    } else {
                        String q = qualityBox == null ? "原始" : (String) qualityBox.getSelectedItem();
                        frameInfo.setText(w + "×" + h + "   " + mode + "   " + q);
                    }
                }
                long edtMs = (System.nanoTime() - edtSubmit) / 1000000;
                if (edtMs > 60 && (frameCount % 30 == 0)) {
                    Log.info(TAG, "EDT lag " + edtMs + " ms（>60ms 说明主线程被绘制拖住）");
                }
            } finally {
                edtPending.set(false);
            }
        });
    }

    /** 停止 = 暂停画面（不拆 SDK 连接）。
     *  拆连接会让设备端 uitest 服务被销毁，之后再连 SDK 以为它还活着，
     *  结果 Image channel 立刻结束、收不到任何帧 —— 这个坑已实测出现过。 */
    private void stopCapture() {
        stopped = true;
        if (spark != null) SwingUtilities.invokeLater(spark::clear);          // 只冻结帧更新，不动 captureGen
                                 // （暂停/继续用同一个会话代数，否则继续后帧会被 myGen 校验丢弃）
        capturing = false;
        connecting = false;
        if (enterBtn != null) enterBtn.setText("继续投屏");
        screenView.setHint("已暂停（连接保留）。换设备可直接双击左侧设备；彻底断开请关窗重开");
        Log.info(TAG, "paused capture (connection kept alive)");
    }

    /** 真正拆连接：只在切换设备 / 关窗时用 */
    private void teardownCapture() {
        stopped = true;
        captureGen++;
        stopCaptureQuietly();
        capturing = false;
        connecting = false;
    }

    private void savePrefs() {
        try {
            PREF.putInt("w", getWidth());
            PREF.putInt("h", getHeight());
            PREF.putInt("x", getX());
            PREF.putInt("y", getY());
            if (modeBox != null) PREF.putInt("mode", modeBox.getSelectedIndex());
            PREF.putBoolean("touch", touchMode);
            PREF.putBoolean("pcClick", pcClick);
            PREF.flush();
        } catch (Exception ex) {
            Log.info(TAG, "ignored: " + ex);
        }
    }

    private void loadPrefs() {
        try {
            int w = PREF.getInt("w", 1280), h = PREF.getInt("h", 820);
            if (w > 400 && h > 300) setSize(w, h);
            int x = PREF.getInt("x", Integer.MIN_VALUE), y = PREF.getInt("y", Integer.MIN_VALUE);
            if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) setLocation(x, y);
            else setLocationRelativeTo(null);
            if (modeBox != null) modeBox.setSelectedIndex(PREF.getInt("mode", 0));
            touchMode = PREF.getBoolean("touch", true);
            pcClick = PREF.getBoolean("pcClick", false);
            if (pcClick) touchMode = false;
            updateModeUi();
            Log.info(TAG, "sun version = " + VERSION);
        Log.info(TAG, "prefs loaded size=" + w + "x" + h + " touch=" + touchMode);
        } catch (Exception ex) {
            Log.info(TAG, "ignored: " + ex);
        }
    }

    /** 静默停流（关窗 / 切设备时调用） */
    private void stopCaptureQuietly() {
        HosRemoteDevice dev = device;
        device = null;                 // 避免复用已关闭的连接
        if (dev == null) return;
        try {
            dev.stopImageScreenCapture();   // 只停图片流
        } catch (Exception ex) {
            Log.info(TAG, "ignored: " + ex);
        }
        try {
            dev.stopCaptureScreen();        // 视频流（若在跑）
        } catch (Exception ex) {
            Log.info(TAG, "ignored: " + ex);
        }
        stopDecodeThread();
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
        // 注意：不要调 stopCaptureScreen()（视频流）。我们没启动过视频流，
        // 调它会让设备端 uitest 服务重启，导致下一次图片流刚建立就 "Image channel finished"。
    }

    /** 发按键命令：executeShellCommand 会阻塞数秒，必须离开 EDT 执行 */
    /** 直接按"镜像帧坐标"注入一次点击/拖动（小窗、纯享模式复用） */
    private void injectAlone(int imgX, int imgY, int x2, int y2, boolean drag) {
        HosRemoteDevice dev = device;
        if (dev == null) {
            // ★ 不要在这里 startControlChannel()：它会把 device 换成 5fps 的控制设备，
            //   视频会直接起不来（0.136.22 已踩过）。等采集恢复后反控自然可用。
            screenView.setHint(capturing ? "反控未就绪，稍等一下…" : "先进入投屏，再点画面");
            return;
        }
        int[] a = toDeviceCoord(imgX, imgY);
        int[] b = toDeviceCoord(x2, y2);
        final int ax = a[0], ay = a[1], bx = b[0], by = b[1];
        // ★ 和主画面走同一套：能原生就原生（跟手），原生不可用才 shell
        if (!useShellTouch) {
            touchExec.submit(() -> {
                try {
                    if (drag) {
                        if (callTouch(dev, "onTouchDown", ax, ay)) {
                            callTouch(dev, "onTouchMove", bx, by);
                            callTouch(dev, "onTouchUp", bx, by);
                            Log.info(TAG, "float native drag " + ax + "," + ay + " -> " + bx + "," + by);
                            return;
                        }
                    } else {
                        if (callTouch(dev, "onTouchDown", bx, by)
                                && callTouch(dev, "onTouchUp", bx, by)) {
                            Log.info(TAG, "float native tap " + bx + "," + by);
                            return;
                        }
                    }
                    // 原生失败 → 退回 shell
                    String cmd = drag
                            ? ("uitest uiInput swipe " + ax + " " + ay + " " + bx + " " + by + " 800")
                            : ("uitest uiInput click " + bx + " " + by);
                    Log.info(TAG, "float fallback: " + cmd + " -> " + shellViaHdc(cmd));
                } catch (Throwable t) {
                    Log.info(TAG, "float touch failed: " + t);
                }
            });
            return;
        }
        touchExec.submit(() -> {
            try {
                String cmd = drag
                        ? ("uitest uiInput swipe " + ax + " " + ay + " " + bx + " " + by + " 800")
                        : ("uitest uiInput click " + bx + " " + by);
                String r = shellViaHdc(cmd);
                Log.info(TAG, "float touch: " + cmd + " -> " + r);
            } catch (Throwable t) {
                Log.info(TAG, "float touch failed: " + t);
            }
        });
    }

    /** 电脑键盘打字 → 直接输入到手机（多屏协同那样） */
    private void showKeyboardBar() {
        JTextField field = new JTextField(28);
        field.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        JLabel tip = new JLabel("输入后回车 → 发到手机；Esc 关闭");
        tip.setForeground(FG_DIM);
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(tip, BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        JDialog dlg = new JDialog(this, "键盘输入到设备", false);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        field.addActionListener(e -> {
            String text = field.getText();
            if (text != null && !text.isEmpty()) sendTextToDevice(text);
            field.setText("");
        });
        field.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "close");
        field.getActionMap().put("close", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { dlg.dispose(); }
        });
        dlg.setVisible(true);
        field.requestFocusInWindow();
    }

    /** 把文本发到当前焦点输入框（走 uitest uiInput text） */
    private void sendTextToDevice(String text) {
        HosRemoteDevice dev = device;
        if (dev == null) { screenView.setHint("先连设备再输入"); return; }
        final String safe = text.replace("'", "'\\''");
        bgExec.submit(() -> {
            try {
                dev.executeShellCommand("uitest uiInput text '" + safe + "'", 10);
                Log.info(TAG, "typed to device: " + safe);
                SwingUtilities.invokeLater(() -> screenView.setHint("已输入：" + text));
            } catch (Throwable t) {
                Log.info(TAG, "type failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("输入失败：" + t));
            }
        });
    }

    /** 浮窗三大金刚键：返回=2 / 桌面=3 / 最近任务=1(菜单) */
    private final java.util.concurrent.ExecutorService cmdExec =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sun-cmd");
                t.setDaemon(true);
                return t;
            });

    private void floatNav(int keyCode, String name) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        // ★ 走独立线程池：keyEvent/aa dump 这类 shell 命令可能阻塞数秒，
        //   放在 touchExec 里会把后面的点击全部堵住（"点了没反应，几秒后才动"）。
        cmdExec.submit(() -> {
            try {
                boolean pcLike = isPcLike();   // 二合一 / 笔记本：没有导航栏和手势
                // 只有平板/PC 才查布局（getLayout 1~3 秒）；手机直接发键值
                boolean tapDeviceNavBar = false;
                if (pcLike) {
                    tapDeviceNavBar = resolveNavBarFromLayout(dev) && navBarCache != null
                            && (navBarCache[0] != null || navBarCache[1] != null || navBarCache[2] != null);
                }
                switch (name) {
                    case "back":
                        if (tapDeviceNavBar && tapDeviceNav(dev, "back")) return;
                        // 和原版一致：直接发系统返回，不再从屏幕边缘滑（边缘滑会误触、还慢）
                        runKeyChain(dev, new int[]{2}, "back");
                        break;
                    case "home":
                        if (tapDeviceNavBar && tapDeviceNav(dev, "home")) return;
                        runKeyChain(dev, new int[]{3, 1}, "home");
                        break;
                    case "recents":
                        if (tapDeviceNavBar && tapDeviceNav(dev, "recents")) return;
                        // 手机：2210 实测会打开别的应用；手势导航用"上滑+停顿"
                        if (!pcLike && swipeUpForRecents(dev)) return;
                        runKeyChain(dev, new int[]{2717}, "recents");
                        break;
                    default:
                        runKeyChain(dev, new int[]{keyCode}, name);
                }
            } catch (Throwable t) {
                Log.info(TAG, "float nav " + name + " failed: " + t);
            }
        });
    }

    /** 取当前前台应用 bundle（aa dump -a 里第一个 state #FOREGROUND 前面的 bundle name） */
    private String foregroundBundle(HosRemoteDevice dev) {
        try {
            String out = dev.executeShellCommand("aa dump -a", 5);
            if (out == null) return null;
            int idx = out.indexOf("state #FOREGROUND");
            if (idx < 0) return null;
            String head = out.substring(0, idx);
            int b = head.lastIndexOf("bundle name [");
            if (b < 0) return null;
            int s = b + "bundle name [".length();
            int e = head.indexOf(']', s);
            return e > s ? head.substring(s, e) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前设备的形态（优先用 SN 查已识别结果；都不认识再退回 lastDeviceKind） */
    private String currentKind() {
        String sn = null;
        try { if (device != null) sn = device.getSn(); } catch (Throwable ignored) { }
        if (sn == null && deviceBox != null) {
            Object o = deviceBox.getSelectedItem();
            if (o instanceof Device) sn = ((Device) o).getSn();
        }
        if (sn != null) {
            for (java.util.Map.Entry<String, String> e : kindBySn.entrySet()) {
                String key = e.getKey().split(":")[0];
                if (!key.isEmpty() && sn.startsWith(key)) return e.getValue();
            }
        }
        return lastDeviceKind;
    }

    /** 是否 PC/二合一形态（这类设备没有底部导航栏、也不吃底部上滑手势） */
    private boolean isPcLike() {
        String kind = currentKind();
        if (kind == null) return false;
        String k = kind.toLowerCase();
        return k.contains("2in1") || k.contains("pc") || k.contains("pad") || k.contains("tablet");
    }

    /** 依次尝试多个键码，第一个"看起来成功"的就停 */
    private void runKeyChain(HosRemoteDevice dev, int[] codes, String name) {
        for (int c : codes) {
            if (tryKey(dev, c, name)) return;
        }
    }

    private boolean tryKey(HosRemoteDevice dev, int code, String name) {
        // 官方支持：Back / Home / Power / 数字 KeyCode（见 uitest-guidelines）
        String semantic = "back".equals(name) ? "Back" : "home".equals(name) ? "Home" : "";
        String[] cmds = semantic.isEmpty()
                ? new String[]{"uitest uiInput keyEvent " + code,
                               "uinput -K -d " + code + " -u " + code}
                : new String[]{"uitest uiInput keyEvent " + semantic,     // ★ 语义键优先（数字键码会回 No Error 但不动作）
                               "uitest uiInput keyEvent " + code,
                               "uinput -K -d " + code + " -u " + code};
        for (String c : cmds) {
            try {
                String out = dev.executeShellCommand(c, 5);
                Log.info(TAG, "float nav " + name + " [" + c + "] -> "
                        + (out == null ? "null" : out.trim()));
                if (out != null && !out.contains("Fail") && !out.contains("error")
                        && !out.contains("not found") && !out.contains("Illegal")) {
                    return true;
                }
            } catch (Throwable t) {
                Log.info(TAG, "float nav " + name + " [" + c + "] 异常: " + t);
            }
        }
        return false;
    }

    /** 手势导航：从底部上滑 = 回桌面 */
    private boolean swipeUpHome(HosRemoteDevice dev) {
        if (useShellTouch || devScreenW <= 0 || devScreenH <= 0) return false;
        try {
            int cx = devScreenW / 2;
            int y0 = devScreenH - 6;
            int y1 = devScreenH / 3;
            callTouch(dev, "onTouchDown", cx, y0);
            for (int i = 1; i <= 8; i++) {
                callTouch(dev, "onTouchMove", cx, y0 + (y1 - y0) * i / 8);
                Thread.sleep(8);
            }
            callTouch(dev, "onTouchUp", cx, y1);
            Log.info(TAG, "nav home via 底部上滑 (" + cx + "," + y0 + "->" + y1 + ")");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手势导航设备：从屏幕左边缘向右滑 = 返回（比任何键盘码都可靠） */
    private boolean swipeBackFromEdge(HosRemoteDevice dev) {
        if (useShellTouch || devScreenW <= 0 || devScreenH <= 0) return false;
        try {
            int y = devScreenH / 2;
            int x0 = 4;
            int x1 = Math.max(120, devScreenW / 4);
            callTouch(dev, "onTouchDown", x0, y);
            for (int i = 1; i <= 6; i++) {
                callTouch(dev, "onTouchMove", x0 + (x1 - x0) * i / 6, y);
                Thread.sleep(8);
            }
            callTouch(dev, "onTouchUp", x1, y);
            Log.info(TAG, "nav back via 左边缘右滑 (" + x0 + "->" + x1 + ", y=" + y + ")");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void runKey(HosRemoteDevice dev, int code, String name) {
        // 依次尝试：uitest 语义键 → uinput 数字键（哪个成功用哪个）
        String[] cmds = {
                "uitest uiInput keyEvent " + ("back".equals(name) ? "Back"
                        : "home".equals(name) ? "Home" : "Menu"),
                "uinput -K -d " + code + " -u " + code
        };
        for (String c : cmds) {
            try {
                String out = dev.executeShellCommand(c, 5);
                Log.info(TAG, "float nav " + name + " [" + c + "] -> "
                        + (out == null ? "null" : out.trim()));
                if (out != null && !out.contains("Fail") && !out.contains("error")
                        && !out.contains("not found")) {
                    return;
                }
            } catch (Throwable t) {
                Log.info(TAG, "float nav " + name + " [" + c + "] 异常: " + t);
            }
        }
    }

    private boolean swipeUpForRecents(HosRemoteDevice dev) {
        if (useShellTouch || devScreenW <= 0 || devScreenH <= 0) return false;
        try {
            int cx = devScreenW / 2;
            int y0 = devScreenH - 12;
            // 参数可调：-Dsun.recents.up=200 -Dsun.recents.back=25
            int upPx = Integer.getInteger("sun.recents.up", 200);
            int backPx = Integer.getInteger("sun.recents.back", 25);
            int holdMs = Integer.getInteger("sun.recents.hold", 0);
            int midY = Math.max(120, devScreenH - upPx);
            callTouch(dev, "onTouchDown", cx, y0);
            for (int i = 1; i <= 10; i++) {
                callTouch(dev, "onTouchMove", cx, y0 + (midY - y0) * i / 10);
                Thread.sleep(18);
            }
            if (holdMs > 0) {
                // ★ 停顿时必须持续补"同点 move"，否则 SDK 触摸流会超时，
                //   系统把手势判成取消 → 应用被拉到一半又弹回原界面。
                long endHold = System.currentTimeMillis() + holdMs;
                while (System.currentTimeMillis() < endHold) {
                    callTouch(dev, "onTouchMove", cx, midY);
                    Thread.sleep(16);
                }
            }
            callTouch(dev, "onTouchMove", cx, midY + backPx);   // 回拉
            Thread.sleep(20);
            callTouch(dev, "onTouchUp", cx, midY + backPx);
            Log.info(TAG, "float nav recents up=" + upPx + " back=" + backPx + " hold=" + holdMs);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 SDK 的控件树里找底部导航栏三个键的真实坐标（比猜键码/猜坐标可靠） */
    private int[][] navBarCache;      // [back][home][recents] -> {x,y}
    private boolean resolveNavBarFromLayout(HosRemoteDevice dev) {
        if (navBarCache != null) return true;
        try {
            String json = dev.getLayout();
            if (json == null || json.length() < 10) return false;
            // 先把 JSON 里的花括号/方括号统一成空格，再用简单正则取 4 个数字（避免转义地狱）
            String flat = json.replace('{', ' ').replace('}', ' ')
                    .replace('[', ' ').replace(']', ' ')
                    .replace('"', ' ');
            java.util.List<int[]> boxes = new java.util.ArrayList<>();
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("bounds\\s*:\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)")
                    .matcher(flat);
            java.util.List<String> names = new java.util.ArrayList<>();
            while (mm.find()) {
                int x1 = Integer.parseInt(mm.group(1)), y1 = Integer.parseInt(mm.group(2));
                int x2 = Integer.parseInt(mm.group(3)), y2 = Integer.parseInt(mm.group(4));
                String around = flat.substring(Math.max(0, mm.start() - 200), mm.end());
                boxes.add(new int[]{(x1 + x2) / 2, (y1 + y2) / 2, y2 - y1});
                names.add(around.toLowerCase());
            }
            Log.info(TAG, "layout nodes=" + boxes.size());
            int[][] found = new int[3][];
            for (int i = 0; i < boxes.size(); i++) {
                String n = names.get(i);
                int[] box = boxes.get(i);
                if (box[2] > devScreenH / 10) continue;              // 排除大块容器
                // 不再限制位置：横屏时导航栏可能贴在侧边
                if (box[2] > devScreenH / 8) continue;
                if (found[0] == null && (n.contains("back") || n.contains("返回") || n.contains("nav_back"))) found[0] = box;
                if (found[1] == null && (n.contains("home") || n.contains("桌面") || n.contains("nav_home"))) found[1] = box;
                if (found[2] == null && (n.contains("recent") || n.contains("多任务")
                        || n.contains("task") || n.contains("overview"))) found[2] = box;
            }
            if (found[0] != null || found[1] != null || found[2] != null) {
                navBarCache = found;
                Log.info(TAG, "nav bar resolved: back=" + (found[0] == null ? "-" : found[0][0] + "," + found[0][1])
                        + " home=" + (found[1] == null ? "-" : found[1][0] + "," + found[1][1])
                        + " recents=" + (found[2] == null ? "-" : found[2][0] + "," + found[2][1]));
                return true;
            }
        } catch (Throwable t) {
            Log.info(TAG, "resolveNavBar failed: " + t);
        }
        return false;
    }

    /** 点设备自身导航栏对应位置（按当前横竖屏自动换算） */
    private boolean tapDeviceNav(HosRemoteDevice dev, String name) {
        if (useShellTouch || devScreenW <= 0 || devScreenH <= 0) return false;
        try {
            // 优先用控件树里导航栏的真实坐标
            if (resolveNavBarFromLayout(dev) && navBarCache != null) {
                int idx = "back".equals(name) ? 0 : "home".equals(name) ? 1 : 2;
                int[] b = navBarCache[idx];
                if (b != null) {
                    boolean ok2 = callTouch(dev, "onTouchDown", b[0], b[1])
                            && callTouch(dev, "onTouchUp", b[0], b[1]);
                    Log.info(TAG, "nav " + name + " via layout (" + b[0] + "," + b[1] + ") ok=" + ok2);
                    return ok2;
                }
            }
            boolean land = devScreenW > devScreenH;
            // ★ 实测（截图量出来）：这台手机的导航栏就在画面底部且可见
            //    □ 多任务 ≈ 30%   ○ 桌面 ≈ 49%   ◁ 返回 ≈ 69%   y ≈ 97.5%
            final int y = (int) (devScreenH * 0.975);
            double fx = name.equals("back") ? 0.69 : name.equals("home") ? 0.49 : 0.30;
            final int x = (int) (devScreenW * fx);
            boolean ok = callTouch(dev, "onTouchDown", x, y) && callTouch(dev, "onTouchUp", x, y);
            Log.info(TAG, "float nav " + name + " via native tap " + x + "," + y + " ok=" + ok
                    + " land=" + land + " dev=" + devScreenW + "x" + devScreenH);
            return ok;
        } catch (Throwable t) {
            Log.info(TAG, "tapDeviceNav failed: " + t);
            return false;
        }
    }

    /** 浮窗：按下（真·触摸按下，不再是松手才点） */
    /** 鼠标悬停：把 PC 的鼠标移动转成设备的"无按键鼠标移动"（SDK 里 button 非三个按键会走 null 分支）。
     *  原版多屏协同靠这个触发 HarmonyOS 的悬停效果（图标放大、设置项预选中）。 */
    private volatile long lastHoverAt = 0;
    private volatile int hoverSent = 0;

    // ---- 鼠标移动合流（解决"鼠标模式不跟手"）----
    private final java.util.concurrent.atomic.AtomicReference<int[]> mouseTarget =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile String mouseTargetBtn = null;
    private volatile boolean mousePumpOn = false;

    /** 只保留最新坐标，单线程按 SDK 实际速度外发；中间位置直接丢弃。
     *  旧实现是"每个 mouseMoved 都 submit"，touchExec 单线程排队 → SDK 一慢就积压，
     *  表现为鼠标越动越滞后（不跟手）。 */
    private void queueMouseMove(String btn, int x, int y) {
        mouseTargetBtn = btn;
        mouseTarget.set(new int[]{x, y});
        if (mousePumpOn) return;
        mousePumpOn = true;
        touchExec.submit(() -> {
            try {
                while (true) {
                    int[] t = mouseTarget.getAndSet(null);
                    if (t == null) break;
                    HosRemoteDevice dv = device;
                    if (dv == null) break;
                    try {
                        dv.onMouseMove(mouseTargetBtn, t[0], t[1]);
                    } catch (Throwable ignored) { }
                }
            } finally {
                mousePumpOn = false;
                int[] rest = mouseTarget.get();
                if (rest != null) queueMouseMove(mouseTargetBtn, rest[0], rest[1]);   // 收尾竞态
            }
        });
    }

    /** 悬停效果默认关闭：SDK 的 hover = 注入鼠标移动 = 手机会画指针，二者不可分。
     *  需要时用 -Dsun.hover=true 打开（指针会一起出现）。 */
    private static final boolean HOVER_ON = Boolean.getBoolean("sun.hover");

    private void hoverMove(int imgX, int imgY) {
        // hover = onMouseMove(null) 就是"不按键的鼠标移动"，鼠标/PC点击模式都必须发：
        //   不按按键时只有走这条路，设备指针才会动，滚轮也才有"当前位置"可作用。
        if (!HOVER_ON && touchMode) return;
        HosRemoteDevice dev = device;
        if (dev == null || !capturing) return;
        long now = System.currentTimeMillis();
        long gap = pcClick ? 30 : 10;          // 鼠标模式要跟手：10ms 上限
        if (now - lastHoverAt < gap) return;
        lastHoverAt = now;
        int[] c = toDeviceCoord(imgX, imgY);
        // 走同一个"合流泵"，避免 hover 和拖拽互相排队
        queueMouseMove(null, c[0], c[1]);
        if ((++hoverSent % 120) == 1) Log.info(TAG, "hover " + c[0] + "," + c[1]);
    }

    private void floatPress(int imgX, int imgY) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        int[] c = toDeviceCoord(imgX, imgY);
        final int x = c[0], y = c[1];
        lastSentX = x; lastSentY = y;
        smoothCurX = x; smoothCurY = y;
        stopSmoothSender();
        final long tPress = System.nanoTime();
        touchExec.submit(() -> {
            try {
                long wait = (System.nanoTime() - tPress) / 1_000_000;
                if (wait > 150) Log.info(TAG, "input queue wait " + wait + "ms (press)");
                callTouch(dev, "onTouchDown", x, y);
                lastTouchNs = System.nanoTime();     // 记下注入时刻：下一帧到达 = 端到端响应
                if (System.getProperty("sun.touchdebug") != null) {
                    Log.info(TAG, "float press " + x + "," + y + "  [img " + imgX + "," + imgY
                            + "  stream " + lastFrameW + "x" + lastFrameH
                            + "  dev " + devScreenW + "x" + devScreenH + "]");
                } else {
                    Log.info(TAG, "float press " + x + "," + y);
                }
            } catch (Throwable t) { Log.info(TAG, "float press failed: " + t); }
        });
    }

    private void floatDrag(int imgX, int imgY) {
        int[] c = toDeviceCoord(imgX, imgY);
        lastSentX = c[0]; lastSentY = c[1];
        ensureSmoothSender();          // 交给平滑线程匀速外发
    }

    private void floatRelease(int imgX, int imgY) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        int[] c = toDeviceCoord(imgX, imgY);
        final int x = c[0], y = c[1];
        stopSmoothSender();
        final long tRelease = System.nanoTime();
        touchExec.submit(() -> {
            try {
                long wait = (System.nanoTime() - tRelease) / 1_000_000;
                if (wait > 150) Log.info(TAG, "input queue wait " + wait + "ms (release)");
                callTouch(dev, "onTouchUp", x, y);
                Log.info(TAG, "float release " + x + "," + y);
            } catch (Throwable t) { Log.info(TAG, "float release failed: " + t); }
        });
    }

    /** 浮窗滚轮：复用主画面的平滑滑动逻辑 */
    private java.util.Timer floatWheelStopTimer;
    private long floatWheelAt = 0;
    private int floatWheelSent = 0;
    private int floatWheelX = 0, floatWheelY = 0;
    private final java.util.concurrent.atomic.AtomicInteger wheelAccumTouch = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean wheelTouchRunning = false;
    private volatile long lastWheelProbeAt = 0;
    private volatile boolean uitestProbeDone = false;
    private volatile boolean axisBurstDone = false;

    // ---- 滚轮改走 shell（uinput -M -s）：SDK 的 AxisUp/Down 在 PC 形态是空操作 ----
    private final java.util.concurrent.atomic.AtomicInteger wheelAccum =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean wheelPumpOn = false;
    private volatile int wheelShellSent = 0;

    /** 滚轮：notches>0 向下、<0 向上；合并短时间内的滚动量，一次 shell 发完 */
    private void queueWheel(int notches) {
        wheelAccum.addAndGet(notches);
        if (wheelPumpOn) return;
        wheelPumpOn = true;
        bgExec.submit(() -> {
            try {
                while (true) {
                    int n = wheelAccum.getAndSet(0);
                    if (n == 0) break;
                    HosRemoteDevice dv = device;
                    if (dv == null) break;
                    int v = Math.max(-2500, Math.min(2500, n * 500));
                    String out = dv.executeShellCommand("uinput -M -m 0 0 -s " + v, 3);
                    if ((++wheelShellSent % 4) == 1) {
                        Log.info(TAG, "wheel shell " + v + " -> " + (out == null ? "null" : out.trim()));
                    }
                    Thread.sleep(45);
                }
            } catch (Throwable t) {
                Log.info(TAG, "wheel shell failed: " + t);
            } finally {
                wheelPumpOn = false;
                if (wheelAccum.get() != 0) queueWheel(0);
            }
        });
    }

    /** 反射拿 SDK 内部的 UitestServer（com.huawei.hosscrcpy.api.b） */
    private Object uitestServer(HosRemoteDevice dv) {
        try {
            for (java.lang.reflect.Field f : dv.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("com.huawei.hosscrcpy.api.b")) {
                    f.setAccessible(true);
                    return f.get(dv);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 诊断：把滚动命令直接发给设备端 uitest agent，并把它的回包打出来。
     *  SDK 自己发完命令会丢弃回包 → 失败时完全静默；这里借它的 socket 看一眼真实错误。 */
    private void probeWheelAgent(int x, int y) {
        long now = System.currentTimeMillis();
        if (now - lastWheelProbeAt < 3000) return;
        lastWheelProbeAt = now;
        try {
            HosRemoteDevice dv = device;
            if (dv == null) return;
            Object server = null;
            for (java.lang.reflect.Field f : dv.getClass().getDeclaredFields()) {
                if (f.getType().getName().equals("com.huawei.hosscrcpy.api.b")) {
                    f.setAccessible(true);
                    server = f.get(dv);
                    break;
                }
            }
            if (server == null) {
                Log.info(TAG, "uitest probe: 未找到内部 UitestServer 字段");
                return;
            }
            java.lang.reflect.Method m = server.getClass().getDeclaredMethod("c", String.class);
            m.setAccessible(true);
            String json = "{\"module\":\"com.ohos.devicetest.hypiumApiHelper\",\"method\":\"Gestures\","
                    + "\"params\":{\"api\":\"AxisStop\",\"args\":{\"x\":" + x + ",\"y\":" + y + "}}}";
            Object r = m.invoke(server, json);
            Log.info(TAG, "uitest probe AxisStop @" + x + "," + y + " reply=" + r);
        } catch (Throwable t) {
            Log.info(TAG, "uitest probe failed: " + t);
        }
    }

    /** 浮窗滚轮：触控模式用短滑动；鼠标/PC点击模式走 shell 的 uinput -M -s（真正系统滚轮） */
    private void floatWheel(int imgX, int imgY, boolean up) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        int[] c = toDeviceCoord(imgX, imgY);
        final int x = c[0], y = c[1];
        floatWheelAt = System.currentTimeMillis();
        if (!touchMode) {
            // 鼠标模式：先把指针放到滚动点（触控模式不动指针）
            touchExec.submit(() -> {
                try { dev.onMouseMove(null, x, y); } catch (Throwable ignored) { }
            });
        }
        // 触控模式：用一次短滑动代替滚轮（不产生鼠标指针）
        // ★ 必须夹在安全区内：从屏幕边缘起滑会被系统当成"上滑/下拉手势"，
        //   桌面上下滑甚至会把设备锁屏（用户实测"滚轮把设备弄黑屏"）。
        // 手机：`uinput -M` 是鼠标滚轮，会弹出鼠标指针 → 手机必须用"慢速触摸滑动"（无指针）
        if (!isPcLike()) {
            wheelAccumTouch.addAndGet(up ? 1 : -1);
            if (!wheelTouchRunning) {
                wheelTouchRunning = true;
                final int sx = Math.max(60, Math.min(x, devScreenW - 60));
                new Thread(() -> {
                    try {
                        while (true) {
                            int n = wheelAccumTouch.getAndSet(0);
                            if (n == 0) break;
                            n = Math.max(-4, Math.min(4, n));
                            int dist = Math.min(160, Math.max(60, Math.abs(n) * 40));
                            int cy = Math.max(200, Math.min(devScreenH - 220, y));
                            boolean upDir = n > 0;
                            int fromY = upDir ? cy + dist : cy - dist;
                            int toY = cy;
                            if (callTouch(dev, "onTouchDown", sx, fromY)) {
                                for (int i = 1; i <= 8; i++) {
                                    callTouch(dev, "onTouchMove", sx, fromY + (toY - fromY) * i / 8);
                                    Thread.sleep(30);          // 慢滑：不触发甩动
                                }
                                callTouch(dev, "onTouchUp", sx, toY);
                            }
                            if ((++floatWheelSent % 5) == 1) {
                                Log.info(TAG, "wheel(touch slow) n=" + n + " " + sx + " " + fromY + "->" + toY);
                            }
                            Thread.sleep(100);
                        }
                    } catch (Throwable t) {
                        Log.info(TAG, "float wheel failed: " + t);
                    } finally {
                        wheelTouchRunning = false;
                    }
                }, "wheel-touch").start();
            }
            return;
        }
        floatWheelX = x;
        floatWheelY = y;
        floatWheelAt = System.currentTimeMillis();
        queueWheel(up ? -1 : 1);
        if (floatWheelStopTimer == null) {
            floatWheelStopTimer = new java.util.Timer("float-wheel-stop", true);
        }
        try { floatWheelStopTimer.cancel(); } catch (Throwable ignored) { }
        floatWheelStopTimer = new java.util.Timer("float-wheel-stop", true);
        floatWheelStopTimer.schedule(new java.util.TimerTask() {
            @Override public void run() {
                if (System.currentTimeMillis() - floatWheelAt < 120) return;
                HosRemoteDevice dv = device;
                if (dv == null) return;
                try { dv.executeShellCommand("uinput -M -m 0 0 -s 0", 2); } catch (Throwable ignored) { }
            }
        }, 150);
        if ((++floatWheelSent % 5) == 1) {
            Log.info(TAG, "float wheel(shell) " + (up ? "up" : "down") + " " + x + "," + y);
        }
    }

    /** 浮窗右键：SDK 鼠标右键按下+抬起（和主画面同一条路） */
    private void injectRightClick(int imgX, int imgY) {
        HosRemoteDevice dev = device;
        if (dev == null) return;
        int[] c = toDeviceCoord(imgX, imgY);
        final int x = c[0], y = c[1];
        cmdExec.submit(() -> {
            try {
                if (touchMode) {
                    // 触控模式：右键当"返回"，避免设备显示鼠标指针（shell 可能几百 ms，放 cmdExec）
                    dev.executeShellCommand("uitest uiInput keyEvent Back", 5);
                    Log.info(TAG, "float right click -> Back (touch mode)");
                    return;
                }
                dev.onMouseDown(HosRemoteDevice.MOUSE_RIGHT, x, y);
                dev.onMouseUp(HosRemoteDevice.MOUSE_RIGHT, x, y);
                Log.info(TAG, "float right click " + x + "," + y);
            } catch (Throwable t) {
                Log.info(TAG, "float right click failed: " + t);
            }
        });
    }

    /** 主动命令设备转屏（SDK 官方 API，语义是"设置设备方向"，不是"跟随"） */
    private void forceRotate(boolean landscape) {
        HosRemoteDevice dev = device;
        if (dev == null) {
            screenView.setHint("先连上设备再转屏");
            return;
        }
        bgExec.submit(() -> {
            try {
                if (landscape) dev.setRotationHorizontal();
                else dev.setRotationVertical();
                Log.info(TAG, "force rotate -> " + (landscape ? "horizontal" : "vertical") + " (ok)");
                SwingUtilities.invokeLater(() ->
                        screenView.setHint("已命令设备转" + (landscape ? "横屏" : "竖屏") + "（应用需支持）"));
            } catch (Throwable t) {
                Log.info(TAG, "force rotate failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("转屏失败：" + t));
            }
        });
    }

    /** dump 当前页面控件结构（SDK getLayout），显示在可搜索的窗口里 */
    private void showLayoutTree() {
        HosRemoteDevice dev = device;
        if (dev == null) {
            screenView.setHint("先连上设备（＋ → 连接），再点结构树");
            return;
        }
        screenView.setHint("正在读取控件结构…");
        bgExec.submit(() -> {
            String json = null;
            try {
                // 强类型直调（编译期校验，避免反射 NoSuchMethod 风险）
                json = dev.getLayout();
                Log.info(TAG, "getLayout -> " + (json == null ? "null" : json.length() + " 字符"));
            } catch (Throwable t) {
                Log.info(TAG, "getLayout failed: " + t);
            }
            final String text = (json == null || json.isEmpty())
                    ? "读取失败：SDK 返回空（需要先 startCaptureScreen 成功）" : prettyJson(json);
            SwingUtilities.invokeLater(() -> showTextWindow("控件结构树（getLayout）", text));
        });
    }

    /** 简单 JSON 美化（不依赖额外库） */
    private String prettyJson(String src) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Object o = m.readValue(src, Object.class);
            return m.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Throwable t) {
            return src;   // 解析失败就原样显示
        }
    }

    /** 弹一个可搜索、可复制的文本框窗口 */
    private void showTextWindow(String title, String text) {
        JFrame f = new JFrame(title);
        f.setSize(820, 720);
        f.setLocationRelativeTo(this);
        JPanel top = new JPanel(new java.awt.BorderLayout(8, 0));
        JTextField search = new JTextField();
        search.setToolTipText("输入关键字（如 text、bounds）回车定位");
        top.add(new JLabel("搜索："), java.awt.BorderLayout.WEST);
        top.add(search, java.awt.BorderLayout.CENTER);
        JTextArea area = new JTextArea(text);
        area.setFont(new java.awt.Font("Consolas", java.awt.Font.PLAIN, 13));
        area.setEditable(false);
        JScrollPane sp = new JScrollPane(area);
        JPanel bottom = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        JButton copy = new JButton("复制全部");
        copy.addActionListener(e -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
            screenView.setHint("结构树已复制到剪贴板");
        });
        bottom.add(copy);
        search.addActionListener(e -> {
            String kw = search.getText().trim();
            if (kw.isEmpty()) return;
            int idx = text.indexOf(kw);
            if (idx < 0) { screenView.setHint("没找到：" + kw); return; }
            area.setCaretPosition(idx);
            area.select(idx, idx + kw.length());
            area.requestFocus();
        });
        f.getContentPane().add(top, java.awt.BorderLayout.NORTH);
        f.getContentPane().add(sp, java.awt.BorderLayout.CENTER);
        f.getContentPane().add(bottom, java.awt.BorderLayout.SOUTH);
        f.setVisible(true);
        Log.info(TAG, "layout window shown, " + text.length() + " 字符");
    }

    /** 触摸校准：调 scale/offset，点画面看落点是否对得上 */
    private void calibrateTouch() {
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 2, 8, 8));
        JSpinner sx = new JSpinner(new SpinnerNumberModel(calScaleX, 0.5, 2.0, 0.001));
        JSpinner sy = new JSpinner(new SpinnerNumberModel(calScaleY, 0.5, 2.0, 0.001));
        JSpinner ox = new JSpinner(new SpinnerNumberModel((int) calOffX, -500, 500, 1));
        JSpinner oy = new JSpinner(new SpinnerNumberModel((int) calOffY, -500, 500, 1));
        panel.add(new JLabel("X 比例"));  panel.add(sx);
        panel.add(new JLabel("Y 比例"));  panel.add(sy);
        panel.add(new JLabel("X 偏移"));  panel.add(ox);
        panel.add(new JLabel("Y 偏移"));  panel.add(oy);
        JSpinner gx = new JSpinner(new SpinnerNumberModel(dragGainX, 0.8, 3.0, 0.05));
        JSpinner gy = new JSpinner(new SpinnerNumberModel(dragGainY, 0.8, 3.0, 0.05));
        JSpinner lg = new JSpinner(new SpinnerNumberModel(landscapeGain, 1.0, 3.0, 0.05));
        panel.add(new JLabel("拖动加速X")); panel.add(gx);
        panel.add(new JLabel("拖动加速Y")); panel.add(gy);
        panel.add(new JLabel("横屏额外加速")); panel.add(lg);
        panel.add(new JLabel("（比例 1.0 / 偏移 0 / 加速 1.0 = 不校准）"));

        int ok = JOptionPane.showConfirmDialog(this, panel, "触摸校准（点画面测试落点）",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        calScaleX = ((Number) sx.getValue()).doubleValue();
        calScaleY = ((Number) sy.getValue()).doubleValue();
        calOffX = ((Number) ox.getValue()).doubleValue();
        calOffY = ((Number) oy.getValue()).doubleValue();
        dragGainX = ((Number) gx.getValue()).doubleValue();
        dragGainY = ((Number) gy.getValue()).doubleValue();
        landscapeGain = ((Number) lg.getValue()).doubleValue();
        PREF.putDouble("landscapeGain", landscapeGain);
        PREF.putDouble("dragGainX", dragGainX);
        PREF.putDouble("dragGainY", dragGainY);
        PREF.putDouble("calScaleX", calScaleX);
        PREF.putDouble("calScaleY", calScaleY);
        PREF.putDouble("calOffX", calOffX);
        PREF.putDouble("calOffY", calOffY);
        Log.info(TAG, "calibration = scale(" + calScaleX + "," + calScaleY
                + ") offset(" + calOffX + "," + calOffY
                + ") dragGain(" + dragGainX + "," + dragGainY + ")");
        screenView.setHint("已应用校准：X×" + calScaleX + " Y×" + calScaleY);
    }

    /** 一键上传日志：打包 logs 目录 → POST 到服务器（带 X-Filename） */
    private void uploadLogs() {
        screenView.setHint("正在打包上传日志…");
        bgExec.submit(() -> {
            java.io.File logDir = new java.io.File(System.getProperty("hoscrcpy.logdir",
                    System.getProperty("user.dir") + java.io.File.separator + "logs"));
            try {
                java.io.File out = java.io.File.createTempFile("sunlog-", ".zip");
                try (java.util.zip.ZipOutputStream zos =
                             new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(out))) {
                    java.io.File[] files = logDir.listFiles();
                    if (files == null || files.length == 0) {
                        Log.info(TAG, "upload logs: no log files in " + logDir);
                        SwingUtilities.invokeLater(() -> screenView.setHint("没有日志文件：" + logDir));
                        return;
                    }
                    java.util.List<String> skipped = new java.util.ArrayList<>();
                    for (java.io.File f : files) {
                        if (!f.isFile()) continue;
                        if (f.getName().endsWith(".lck")) continue;   // 锁文件跳过
                        boolean added = false;
                        for (int attempt = 0; attempt < 3 && !added; attempt++) {
                            try (java.io.InputStream is = new java.io.FileInputStream(f)) {
                                zos.putNextEntry(new java.util.zip.ZipEntry("logs/" + f.getName()));
                                byte[] tmp = new byte[64 * 1024];
                                int n;
                                while ((n = is.read(tmp)) > 0) zos.write(tmp, 0, n);
                                zos.closeEntry();
                                added = true;
                            } catch (Throwable e) {
                                if (attempt == 2) {
                                    skipped.add(f.getName());
                                    Log.info(TAG, "skip locked log " + f.getName() + ": " + e);
                                    try { zos.closeEntry(); } catch (Throwable ignored) { }
                                } else {
                                    try { Thread.sleep(120); } catch (InterruptedException ignored) { }
                                }
                            }
                        }
                    }
                    if (!skipped.isEmpty()) {
                        Log.info(TAG, "upload logs: skipped " + skipped);
                    }
                }
                byte[] data = java.nio.file.Files.readAllBytes(out.toPath());
                String name = "sunlog-" + new java.text.SimpleDateFormat("HHmmss").format(new java.util.Date()) + ".zip";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:8001/upload").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(15000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("X-Filename", name);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                try (java.io.OutputStream os = c.getOutputStream()) { os.write(data); }
                int code = c.getResponseCode();
                String resp = "";
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream()))) {
                    String l;
                    while ((l = br.readLine()) != null) resp += l;
                } catch (Throwable ignored) { }
                Log.info(TAG, "upload logs -> HTTP " + code + " " + resp + " (" + data.length + " bytes)");
                final String msg = "HTTP " + code + " " + resp.trim();
                SwingUtilities.invokeLater(() -> screenView.setHint("日志上传：" + msg));
                out.delete();
            } catch (Throwable t) {
                Log.info(TAG, "upload logs failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("日志上传失败：" + t));
            }
        });
    }

    /** A/B 路自动切换：横屏 → B 路（转屏快）；竖屏 → A 路（跟手）
     *  ★ 切之前先探 B 路端口：Sender 没在推流时如果先停 A 再连 B，
     *    结果就是黑屏且回不来（0.134 及以前的行为）。探测失败就保持 A 路。 */
    private void maybeAutoSwitchPath(boolean landscape) {
        if (!autoPathSwitch) return;
        if (System.currentTimeMillis() - lastPathSwitchMs < 4000) return;   // 冷却，防抖动
        final String host = (streamHost == null || streamHost.isEmpty())
                ? PREF.get("streamHostLast", "") : streamHost;
        if (host == null || host.isEmpty()) {
            Log.info(TAG, "auto switch: no known host, skip");
            return;
        }
        final boolean onTcp = tcpStreaming;
        if (landscape && !onTcp) {
            lastPathSwitchMs = System.currentTimeMillis();
            bgExec.submit(() -> {
                if (!tcpReachable(host, 6000, 1200)) {
                    Log.info(TAG, "auto switch: B 路 " + host + ":6000 未推流，保持 A 路");
                    hintLater("横屏：B 路未推流（" + host + ":6000），继续用 A 路");
                    return;
                }
                Log.info(TAG, "auto switch: landscape -> B path (tcp " + host + ":6000)");
                try { if (device != null) device.stopCaptureScreen(); } catch (Throwable ignored) { }
                capturing = false;
                tcpStreaming = false;
                connectTcpDirect(host, 6000);
            });
        } else if (!landscape && onTcp) {
            lastPathSwitchMs = System.currentTimeMillis();
            Log.info(TAG, "auto switch: portrait -> A path (sdk capture)");
            bgExec.submit(() -> {
                stopTcpStream();
                resetAllStreamState();
                capturing = false;
                if (device != null) startCapture();   // 内部自己起线程；不要在 EDT 里做 hdc 调用
            });
        }
    }

    /** 纯探测：B 路（Sender 推流）端口通不通，避免"先拆 A 再发现 B 没开" */
    private static boolean tcpReachable(String host, int port, int timeoutMs) {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /** 不走对话框，直接连 TCP 推流（自动切换用） */
    private void connectTcpDirect(String host, int port) {
        Log.info(TAG, "connectTcpDirect " + host + ":" + port);
        resetAllStreamState();
        stopped = false;
        capturing = true;
        final int myGen = ++captureGen;
        if (decoder != null) { try { decoder.close(); } catch (Exception ex) { Log.info(TAG, "ignored: " + ex); } }
        decoder = new FfmpegDecoder();
        decoder.initAsHevc();
        auIsHevc = true;
        codecLabel = "H.265 推流";
        auBuf.clear(); auHasVcl = false; psVps = null; psSps = null; psPps = null;
        needParamSets = true; waitingIdr = true;
        startDecodeThread(decoder, myGen);
        tcpStreaming = true;
        lastDataAt = System.currentTimeMillis();
        final int fport = port;
        tcpThread = new Thread(() -> {
            byte[] buf = new byte[64 * 1024];
            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            try (java.net.Socket sock = new java.net.Socket()) {
                tcpSocket = sock;
                sock.connect(new java.net.InetSocketAddress(host, fport), 5000);
                streamHost = host;
                sock.setTcpNoDelay(true);
                sock.setReceiveBufferSize(1 << 18);
                Log.info(TAG, "direct tcp connected " + host + ":" + fport);
                int n;
                while (!stopped && capturing && myGen == captureGen
                        && (n = sock.getInputStream().read(buf)) > 0) {
                    acc.write(buf, 0, n);
                    splitAndFeedNals(acc, myGen);
                    lastDataAt = System.currentTimeMillis();
                }
            } catch (Throwable t) {
                Log.info(TAG, "direct tcp ended: " + t);
            }
        }, "tcp-direct");
        tcpThread.setDaemon(true);
        tcpThread.start();
    }

    /** 当前画面是否横屏（按最新帧尺寸判断） */
    private boolean isLandscapeNow() {
        java.awt.image.BufferedImage img = screenView == null ? null : screenView.getShownImage();
        return img != null && img.getWidth() > img.getHeight();
    }

    /** 平滑发送线程：每 8ms 把"当前目标点"按均匀步长推给设备（约 125Hz，比鼠标事件更密更匀） */
    private volatile boolean smoothRunning = false;
    private volatile int smoothTargetX = 0, smoothTargetY = 0;
    private volatile int smoothCurX = 0, smoothCurY = 0;

    private Thread smoothThread;

    private void ensureSmoothSender() {
        if (smoothRunning) return;
        smoothRunning = true;
        smoothCurX = lastSentX;
        smoothCurY = lastSentY;
        // ★ 必须用独立线程：touchExec 是单线程，被这个循环占住后
        //   onTouchDown/onTouchUp 全都发不出去 → 滑动完全失效 ✗
        smoothThread = new Thread(() -> {
            HosRemoteDevice dev = device;
            try {
                while (smoothRunning && dev != null) {
                    int tx = lastSentX, ty = lastSentY;
                    int dx = tx - smoothCurX, dy = ty - smoothCurY;
                    if (dx == 0 && dy == 0) {
                        // ★ 按住不动 → 每 16ms 补一个"同点"事件，保持按压不超时
                        //   （否则设备端会把长按判成"抬起/取消"，按住拖动就失效 ✗）
                        callTouch(dev, "onTouchMove", smoothCurX, smoothCurY);
                        Thread.sleep(16);
                        continue;
                    }
                    // 每帧走 1/4 剩余距离（平滑收敛，不跳点）
                    int stepX = smoothCurX + Math.max(1, Math.abs(dx) / 4) * Integer.signum(dx);
                    int stepY = smoothCurY + Math.max(1, Math.abs(dy) / 4) * Integer.signum(dy);
                    if (Math.abs(tx - stepX) < 2) stepX = tx;
                    if (Math.abs(ty - stepY) < 2) stepY = ty;
                    callTouch(dev, "onTouchMove", stepX, stepY);
                    smoothCurX = stepX; smoothCurY = stepY;
                    Thread.sleep(8);
                }
            } catch (Throwable t) {
                Log.info(TAG, "smooth sender exit: " + t);
            } finally {
                smoothRunning = false;
            }
        }, "smooth-touch");
        smoothThread.setDaemon(true);
        smoothThread.start();
    }

    private void stopSmoothSender() {
        smoothRunning = false;
    }

    /** 把"镜像帧坐标"换算成"设备真实屏幕坐标"（分辨率不一致时修正） */
    private volatile int lastFrameW = 0, lastFrameH = 0;   // 最近一帧的流分辨率（触摸换算用）

    private int[] toDeviceCoord(int x, int y) {
        // ★ 触摸换算必须用"最近一帧的真实尺寸"，不能用 screenView 里那张显示图：
        //   纯享模式下主窗口不再更新 image（0.136.28 的优化），切档后尺寸会变成旧值 →
        //   50% 档少了 ×2 的换算，点哪儿都偏左上（平板实测踩到）。
        if (calScaleX != 1.0 || calScaleY != 1.0 || calOffX != 0 || calOffY != 0) {
            x = (int) Math.round(x * calScaleX + calOffX);
            y = (int) Math.round(y * calScaleY + calOffY);
        }
        int fw = lastFrameW, fh = lastFrameH;
        if (fw > 0 && fh > 0 && haveDeviceSize && devScreenW > 0 && devScreenH > 0) {
            if (fw != devScreenW || fh != devScreenH) {
                int nx = (int) Math.round(x * (double) devScreenW / fw);
                int ny = (int) Math.round(y * (double) devScreenH / fh);
                return new int[]{Math.max(0, Math.min(devScreenW - 1, nx)),
                                 Math.max(0, Math.min(devScreenH - 1, ny))};
            }
        }
        return new int[]{x, y};
    }

    /** 直接用我们自己的 hdc 执行 shell 命令（不经过 SDK，避免 hdc 服务不一致） */
    private volatile boolean hdcTconnDone = false;
    private final java.util.concurrent.atomic.AtomicBoolean shellBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private String shellViaHdc(String cmd) {
        // 上一条还没回来就跳过：拖动时最多同时 1 个 hdc 进程，避免进程堆积卡死
        if (!shellBusy.compareAndSet(false, true)) return "";
        try {
            return shellViaHdcInner(cmd);
        } finally {
            shellBusy.set(false);
        }
    }

    private String shellViaHdcInner(String cmd) {
        // ★ 先试官方 API（不起进程，快）
        String nativeOut = execShellNative(cmd);
        if (nativeOut != null) return nativeOut;
        String hdc = findHdc();
        String snCalc = controlSn;
        if ((snCalc == null || snCalc.isEmpty()) && streamHost != null && !streamHost.isEmpty()) {
            snCalc = streamHost + ":37911";
        }
        if (snCalc == null || snCalc.isEmpty()) {
            String last = PREF.get("streamAddr", "");
            if (last == null || last.isEmpty()) last = PREF.get("lastConnectAddress", "");
            int c = last.indexOf(':');
            String ip = (c > 0 ? last.substring(0, c) : last);
            ip = ip == null ? "" : ip.trim();
            if (!ip.isEmpty()) {
                snCalc = ip + ":37911";
                Log.info(TAG, "shellViaHdc: fallback snCalc=" + snCalc);
            }
        }
        if (hdc == null || snCalc == null || snCalc.isEmpty()) {
            Log.info(TAG, "shellViaHdc: no hdc(" + hdc + ") or snCalc(" + snCalc + "), skip");
            return "";
        }
        final String hdcF = hdc;
        final String snF = snCalc;
        controlSn = snF;
        if (!hdcTconnDone) {
            hdcTconnDone = true;
            try {
                utils.ProcessExecutor ex0 = new utils.ProcessExecutor();
                String out = ex0.callProcess(new String[]{hdcF, "tconn", snF}, 8);
                Log.info(TAG, "hdc tconn(" + snF + ") -> " + (out == null ? "null" : out.trim()));
            } catch (Throwable t) {
                Log.info(TAG, "hdc tconn failed: " + t);
            }
        }
        utils.ProcessExecutor ex = new utils.ProcessExecutor();
        String r = ex.callProcess(new String[]{hdcF, "-t", snF, "shell", cmd}, 6);
        Log.info(TAG, "hdc shell[" + cmd + "] -> " + (r == null ? "null" : r.trim()));
        return r == null ? "" : r;
    }

    /** 按键注入：优先 uitest uiInput keyEvent，失败再退回 uinput */
    private void keyPress(int code) {
        final HosRemoteDevice dev = device;
        if (dev == null) {
            // ★ 不要在这里 startControlChannel()：它会把 device 换成 5fps 的控制设备，
            //   视频直接起不来（0.136.22/0.136.42 都踩过）。设备没连上就先提示。
            screenView.setHint(capturing ? "设备通道重建中，稍后再按" : "先进入投屏再操作设备");
            Log.info(TAG, "key " + code + ": device null，忽略（未进入投屏）");
            return;
        }
        new Thread(() -> {
            String[] cmds = {
                "uitest uiInput keyEvent " + code,
                "uinput -K -d " + code + " -u " + code
            };
            for (String c : cmds) {
                try {
                    String hr = shellViaHdc(c);
                    Object r = (hr != null && !hr.isEmpty()) ? hr : dev.executeShellCommand(c, 3);
                    Log.info(TAG, "key " + code + " via [" + c + "] -> " + r);
                    if (r != null && !r.toString().trim().isEmpty()) {
                        SwingUtilities.invokeLater(() -> screenView.setHint("已发送按键 " + code));
                        return;
                    }
                } catch (Throwable ex) {
                    Log.info(TAG, "key cmd failed [" + c + "]: " + ex);
                }
            }
            SwingUtilities.invokeLater(() ->
                    screenView.setHint("按键 " + code + " 下发无响应（可能缺少输入注入权限）"));
        }, "key").start();
    }

    private void shell(String cmd) {
        final HosRemoteDevice dev = device;
        if (dev == null) {
            screenView.setHint("未连接设备，先在左侧选择设备并点【进入投屏】");
            return;
        }
        new Thread(() -> {
            try {
                dev.executeShellCommand(cmd, 3);
            } catch (Exception ex) {
                Log.error(TAG, "shell failed: " + cmd, ex);
            }
        }, "shell").start();
    }

    // ---------------- 浏览器投屏（局域网 WebSocket） ----------------
    private WebMirrorServer webServer;

    private void toggleWebMirror() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
            screenView.setHint("浏览器投屏已停止");
            Log.info(TAG, "web mirror stopped");
            return;
        }
        try {
            WebMirrorServer s = new WebMirrorServer(() -> frontBuf, new WebMirrorServer.Commands() {
                @Override public void onTap(int x, int y) { injectAlone(x, y, x, y, false); }
                @Override public void onSwipe(int x1, int y1, int x2, int y2) { injectAlone(x1, y1, x2, y2, true); }
                @Override public void onWheel(int x, int y, boolean up) {
                    if (touchMode) floatWheel(x, y, up);
                    else queueWheel(up ? -1 : 1);
                }
                @Override public void onKey(String name) {
                    if ("power".equals(name)) keyPress(18);
                    else if ("back".equals(name)) floatNav(2, "back");
                    else if ("home".equals(name)) floatNav(3, "home");
                    else if ("recents".equals(name)) floatNav(1, "recents");
                }
            }, 8765);
            s.start();
            webServer = s;
            String u = s.url();
            try {
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(u), null);
            } catch (Throwable ignored) { }
            screenView.setHint("浏览器投屏：" + u + "（已复制到剪贴板）");
            Log.info(TAG, "web mirror started: " + u);
            // 还没进入投屏就顺手开一下，否则浏览器只能看到黑屏
            if (!capturing && deviceBox != null && deviceBox.getItemCount() > 0) {
                Log.info(TAG, "web mirror: auto start capture");
                bgExec.submit(this::startCapture);
            }
        } catch (Throwable t) {
            Log.info(TAG, "web mirror start failed: " + t);
            screenView.setHint("浏览器投屏启动失败：" + t);
        }
    }

    // ---------------- 设备工具：应用启停 / 文件推拉 / 熄屏 ----------------
    private String hdcShell(String cmd) {
        try {
            String hb = findHdc();
            if (hb == null) return "hdc not found";
            String out = new ProcessExecutor().callProcess(new String[]{hb, "shell", cmd}, 10);
            return out == null ? "" : out.trim();
        } catch (Throwable t) {
            return "err: " + t;
        }
    }

    private String hdcFile(String op, String from, String to) {
        try {
            String hb = findHdc();
            if (hb == null) return "hdc not found";
            String out = new ProcessExecutor().callProcess(new String[]{hb, "file", op, from, to}, 60);
            return out == null ? "" : out.trim();
        } catch (Throwable t) {
            return "err: " + t;
        }
    }

    private void showDeviceTools() {
        final JDialog dlg = new JDialog(this, "设备工具", false);
        JPanel p = new JPanel(new GridLayout(0, 1, 8, 8));
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        final JLabel fgLabel = new JLabel("前台应用：查询中…");
        final JTextField bundle = new JTextField("", 24);
        final JLabel result = new JLabel(" ");

        JPanel appRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton refresh = new JButton("刷新前台");
        JButton startB = new JButton("启动");
        JButton stopB = new JButton("停止");
        appRow.add(refresh);
        appRow.add(startB);
        appRow.add(stopB);
        JPanel bundleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bundleRow.add(new JLabel("Bundle:"));
        bundleRow.add(bundle);

        JPanel sysRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton power = new JButton("熄屏/亮屏");
        JButton push = new JButton("推文件到设备");
        JButton pull = new JButton("从设备拉文件");
        sysRow.add(power);
        sysRow.add(push);
        sysRow.add(pull);

        refresh.addActionListener(e -> bgExec.submit(() -> {
            String b = foregroundBundle(device);
            SwingUtilities.invokeLater(() -> {
                fgLabel.setText("前台应用：" + (b == null ? "查询失败" : b));
                if (bundle.getText().trim().isEmpty() && b != null) bundle.setText(b);
            });
        }));

        startB.addActionListener(e -> {
            String b = bundle.getText().trim();
            if (b.isEmpty()) { result.setText("先填 Bundle 名"); return; }
            bgExec.submit(() -> {
                String out = hdcShell("aa start -b " + b);
                SwingUtilities.invokeLater(() -> result.setText("启动：" + out));
            });
        });
        stopB.addActionListener(e -> {
            String b = bundle.getText().trim();
            if (b.isEmpty()) { result.setText("先填 Bundle 名"); return; }
            bgExec.submit(() -> {
                String out = hdcShell("aa force-stop -b " + b);
                SwingUtilities.invokeLater(() -> result.setText("停止：" + out));
            });
        });
        power.addActionListener(e -> keyPress(18));

        push.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("选择要推到设备的文件");
            if (fc.showOpenDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            java.io.File f = fc.getSelectedFile();
            String remote = "/data/local/tmp/" + f.getName();
            result.setText("推送中：" + f.getName());
            bgExec.submit(() -> {
                String out = hdcFile("send", f.getAbsolutePath(), remote);
                SwingUtilities.invokeLater(() -> result.setText("已推送 → " + remote + "  " + out));
            });
        });

        pull.addActionListener(e -> {
            String remote = JOptionPane.showInputDialog(dlg, "设备上的文件路径：", "/data/local/tmp/");
            if (remote == null || remote.trim().isEmpty()) return;
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("保存到本地");
            fc.setSelectedFile(new java.io.File(remote.substring(remote.lastIndexOf('/') + 1)));
            if (fc.showSaveDialog(dlg) != JFileChooser.APPROVE_OPTION) return;
            java.io.File f = fc.getSelectedFile();
            result.setText("拉取中…");
            bgExec.submit(() -> {
                String out = hdcFile("recv", remote.trim(), f.getAbsolutePath());
                SwingUtilities.invokeLater(() -> result.setText("已拉取 → " + f.getName() + "  " + out));
            });
        });

        p.add(fgLabel);
        p.add(appRow);
        p.add(bundleRow);
        p.add(new JSeparator());
        p.add(sysRow);
        p.add(result);
        dlg.getContentPane().add(p);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
        bgExec.submit(() -> {
            String b = foregroundBundle(device);
            SwingUtilities.invokeLater(() -> {
                fgLabel.setText("前台应用：" + (b == null ? "查询失败" : b));
                if (bundle.getText().trim().isEmpty() && b != null) bundle.setText(b);
            });
        });
    }

    private PhoneWindow phoneWin;

    /** 纯享模式：弹出无边框透明浮窗（透出桌面、可拖动、滚轮缩放、双击/Esc 关闭） */
    private void togglePureMode() {
        if (phoneWin != null) {
            phoneWin.dispose();
            phoneWin = null;
            setVisible(true);
            requestFocus();
            Log.info(TAG, "pure mode off");
            return;
        }
        // 配对校验对话框还在时不要开纯享：它会把对话框顶掉，导致"用户取消配对校验"→释放连接
        if (authPending) {
            Log.info(TAG, "pure mode skipped: 配对校验进行中");
            screenView.setHint("先完成配对校验，再进多屏协同");
            return;
        }
        try {
            phoneWin = new PhoneWindow(() -> frontBuf, () -> {
                PhoneWindow cur = phoneWin;
                try {
                    if (cur != null) {
                        PREF.put("pureBounds", cur.getX() + "," + cur.getY() + "," + cur.getWidth() + "," + cur.getHeight());
                    }
                } catch (Throwable ignored) { }
                phoneWin = null;
                setVisible(true);
                toFront();
                requestFocus();
                Log.info(TAG, "pure mode closed by user");
            }, true);   // plain=true：照系统"多屏协同"窗口画同一个外框
            final PhoneWindow pw = phoneWin;
            phoneWin.setHudEnabled("true".equals(PREF.get("pureHud", "false")));
            phoneWin.setLocalHover(!touchMode);
            // 平板/PC 形态：系统没有三大金刚（用任务栏），强制隐藏
            boolean pcLike = isPcLike();
            boolean showNav = !pcLike;
            phoneWin.setNavBarVisible(showNav);
            if (pcLike) Log.info(TAG, "pure mode: PC/平板形态，三大金刚已隐藏");
            // 形态识别有时比开窗晚：1.5 秒后再强制校正一次
            javax.swing.Timer navFix = new javax.swing.Timer(1500, ev -> {
                ((javax.swing.Timer) ev.getSource()).stop();
                PhoneWindow w = phoneWin;
                if (w != null && isPcLike()) w.setNavBarVisible(false);
            });
            navFix.setRepeats(false);
            navFix.start();
            if (lastHudText != null) phoneWin.setHudText(lastHudText);
            phoneWin.setTouchTap(new PhoneWindow.TouchTap() {
                @Override public void onTap(int x, int y) { injectAlone(x, y, x, y, false); }
                @Override public void onSwipe(int x1, int y1, int x2, int y2) { injectAlone(x1, y1, x2, y2, true); }
                @Override public void onRightClick(int x, int y) { floatNav(2, "back"); }   // 右键=返回（原版）
                @Override public void onMiddleClick() { floatNav(3, "home"); }              // 中键=桌面（原版）
                @Override public void onPress(int x, int y) { floatPress(x, y); }
                @Override public void onDrag(int x, int y) { floatDrag(x, y); }
                @Override public void onRelease(int x, int y) { floatRelease(x, y); }
                @Override public void onWheel(int x, int y, boolean up) { floatWheel(x, y, up); }
                @Override public void onNavBack() { floatNav(2, "back"); }
                @Override public void onNavHome() { floatNav(3, "home"); }
                @Override public void onNavRecents() { floatNav(1, "recents"); }
                @Override public void onHover(int x, int y) { hoverMove(x, y); }
            });
            phoneWin.setMenuActions(new PhoneWindow.MenuActions() {
                @Override public void onHdcKill() { forceHdcDisconnect(); }
                @Override public void onQuality(String q) {
                    SwingUtilities.invokeLater(() -> { if (qualityBox != null) qualityBox.setSelectedItem(q); });
                }
                @Override public void onDisconnect() {
                    SwingUtilities.invokeLater(() -> {
                        PhoneWindow w = phoneWin;
                        if (w != null) w.closeWindow();
                        stopCapture();
                    });
                }
                @Override public String currentQuality() {
                    return qualityBox == null ? null : String.valueOf(qualityBox.getSelectedItem());
                }
                @Override public void onHudChanged(boolean on) {
                    PREF.put("pureHud", on ? "true" : "false");
                }
                @Override public void onInputMode(String m) {
                    SwingUtilities.invokeLater(() -> setInputMode(m));
                }
                @Override public String currentInputMode() {
                    return touchMode ? "touch" : (pcClick ? "pc" : "mouse");
                }
                @Override public void onNavBarChanged(boolean on) {
                    PREF.put("pureNav", on ? "true" : "false");
                }
            });
            boolean restored = false;
            String savedBounds = PREF.get("pureBounds", "");
            if (savedBounds != null && !savedBounds.trim().isEmpty()) {
                try {
                    String[] a = savedBounds.split(",");
                    if (a.length == 4) {
                        int sx = Integer.parseInt(a[0].trim()), sy = Integer.parseInt(a[1].trim());
                        int sw = Integer.parseInt(a[2].trim()), sh = Integer.parseInt(a[3].trim());
                        if (sw >= 200 && sh >= 300) { phoneWin.restoreBounds(sx, sy, sw, sh); restored = true; }
                    }
                } catch (Throwable ignored) { }
            }
            final boolean restoredPos = restored;
            if (restoredPos) Log.info(TAG, "pure mode: restore saved bounds " + savedBounds);
            setVisible(false);   // 主窗口收起，只剩浮窗漂在桌面上
            Log.info(TAG, "pure mode on (floating window)");
            // 关键：如果系统"多屏协同"正在运行，直接套用它的位置和尺寸 → 外框和它一模一样
            if (!restoredPos) bgExec.submit(() -> {
                java.awt.Rectangle r = queryMirrorWindow();
                if (r == null) {
                    Log.info(TAG, "pure mode: 系统多屏协同窗口没找到，用默认大小");
                    return;
                }
                Log.info(TAG, "pure mode: adopt mirror window " + r.x + "," + r.y + " " + r.width + "x" + r.height);
                SwingUtilities.invokeLater(() -> {
                    if (phoneWin != null && phoneWin == pw) phoneWin.adoptMirrorBounds(r);
                });
            });
        } catch (Throwable t) {
            Log.info(TAG, "pure window failed: " + t);
            if (phoneWin != null) { phoneWin.dispose(); phoneWin = null; }
            setVisible(true);
            screenView.setHint("当前系统不支持透明浮窗");
        }
    }

    /** 推流地址输入：历史下拉 + 可手输（记住最近 8 个） */
    private String askStreamAddr() {
        java.util.ArrayList<String> hist = new java.util.ArrayList<>();
        String raw = PREF.get("streamAddrHistory", "");
        for (String x : raw.split("\\|")) {
            if (x != null && !x.trim().isEmpty()) hist.add(x.trim());
        }
        // 固定候选（调试常用的那台手机）
        for (String preset : new String[]{"192.168.1.100:37911", "192.168.1.100:38387"}) {
            if (!hist.contains(preset)) hist.add(preset);
        }
        String last = PREF.get("streamAddr", "");
        if (last != null && !last.isEmpty() && !hist.contains(last)) hist.add(0, last);

        javax.swing.JComboBox<String> combo = new javax.swing.JComboBox<>(hist.toArray(new String[0]));
        combo.setEditable(true);
        combo.setPreferredSize(new java.awt.Dimension(300, 30));
        if (!hist.isEmpty()) combo.setSelectedIndex(0);

        javax.swing.JTextField codeField = new javax.swing.JTextField(PREF.get("authCode", ""), 8);
        codeField.setPreferredSize(new java.awt.Dimension(120, 28));
        javax.swing.JPanel codeRow = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        codeRow.add(new javax.swing.JLabel("配对码(留空=不校验)"));
        codeRow.add(codeField);
        javax.swing.JPanel box = new javax.swing.JPanel(new java.awt.GridLayout(3, 1, 0, 6));
        box.add(new javax.swing.JLabel("输入推流端地址 IP:端口"));
        box.add(combo);
        box.add(codeRow);
        Object[] msg = {box};
        int ok = JOptionPane.showConfirmDialog(this, msg, "直连推流",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;
        PREF.put("authCode", codeField.getText().trim());
        Object v = combo.getEditor().getItem();
        return v == null ? null : v.toString();
    }

    /** 记住用过的推流地址（最多 8 个，最新在前） */
    private void rememberStreamAddr(String addr) {
        if (addr == null || addr.trim().isEmpty()) return;
        String a = addr.trim();
        PREF.put("streamAddr", a);
        java.util.LinkedList<String> list = new java.util.LinkedList<>();
        list.add(a);
        String raw = PREF.get("streamAddrHistory", "");
        for (String x : raw.split("\\|")) {
            if (x == null) continue;
            String t = x.trim();
            if (t.isEmpty() || list.contains(t)) continue;
            list.add(t);
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String t : list) {
            if (n++ >= 8) break;
            if (sb.length() > 0) sb.append("|");
            sb.append(t);
        }
        PREF.put("streamAddrHistory", sb.toString());
    }

    /** 找到 hdc 可执行文件：优先程序目录，其次 PATH */
    private String findHdc() {
        String[] names = {"hdc.exe", "hdc"};
        java.util.List<String> dirs = new java.util.ArrayList<>();
        dirs.add(System.getProperty("user.dir"));
        dirs.add(System.getProperty("user.dir") + java.io.File.separator + "target");
        for (String d : dirs) {
            for (String n : names) {
                java.io.File f = new java.io.File(d, n);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    /** 发起反控：hdc tconn → 直接用 IP 建 HosRemoteDevice → 拉起 uitest 守护进程 → 亮绿点 */
    private void startControlChannel() {
        Log.info(TAG, "startControlChannel() 进入, streamHost=" + streamHost
                + " hdc=" + findHdc() + " device=" + (device != null));
        String host = streamHost;
        if (host == null || host.isEmpty()) {
            String last = PREF.get("streamAddr", "");
            if (last == null || last.isEmpty()) last = PREF.get("lastConnectAddress", "");
            int c = last.indexOf(':');
            if (c > 0) last = last.substring(0, c);
            host = last.trim();
        }
        if (host == null || host.isEmpty()) {
            screenView.setHint("还没有推流地址：先用 📡 连一次（或点 ＋ 连接设备）");
            Log.info(TAG, "control channel: no host, abort");
            return;
        }
        final String h = host;
        streamHost = h;
        String snSaved = streamSn;
        if (snSaved == null || !snSaved.contains(":")) snSaved = PREF.get("streamSn", "");
        final String targetSn = (snSaved != null && snSaved.contains(":")) ? snSaved : (h + ":37911");
        final String hdcBin = findHdc();
        if (hdcBin == null) {
            screenView.setHint("反控需要 hdc.exe：请把它放到程序目录");
            Log.info(TAG, "control channel: hdc.exe not found");
            return;
        }
        if (controlBootstrapping) {
            long now = System.currentTimeMillis();
            if (controlBootstrappingSince == 0) controlBootstrappingSince = now;
            if (now - controlBootstrappingSince > 20000) {
                Log.info(TAG, "control bootstrap stuck >20s, force retry");
                controlBootstrapping = false;
                controlBootstrappingSince = 0;
            } else {
                Log.info(TAG, "control bootstrap already running, ignore");
                return;
            }
        }
        controlBootstrappingSince = System.currentTimeMillis();
        controlBootstrapping = true;
        screenView.setHint("正在建立反控通道…");
        Log.info(TAG, "control channel: host=" + h + " sn=" + targetSn + " hdc=" + hdcBin);
        new Thread(() -> {
            try {
                try {
                    Process p = new ProcessBuilder(hdcBin, "tconn", targetSn)
                            .redirectErrorStream(true).start();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.info(TAG, "hdc: " + line);
                    }
                    boolean done = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (!done) {
                        p.destroyForcibly();
                        Log.info(TAG, "hdc tconn timeout(8s) -> killed");
                    } else {
                        Log.info(TAG, "hdc tconn exit=" + p.exitValue());
                    }
                } catch (Throwable t) {
                    Log.info(TAG, "hdc tconn failed: " + t);
                }

                // 直接用 IP 建控制设备（和 H.264 模式同一套做法，不依赖设备列表）
                final String snFinal = targetSn;
                controlSn = snFinal;
                com.huawei.hosscrcpy.api.HosRemoteConfig cfg =
                        new com.huawei.hosscrcpy.api.HosRemoteConfig(snFinal);
                cfg.setIp(h);
                cfg.setImageScaleSize(1.0f);
                cfg.setFrameRate(5);
                cfg.setBitRate(2 * 1024 * 1024);
                cfg.setIFrameInterval(2000);
                // 官方文档：setHdcPath 传 hdc 可执行文件的【完整路径】
                try {
                    String hb = findHdc();
                    if (hb != null) {
                        cfg.setHdcPath(new java.io.File(hb).getAbsolutePath());
                        Log.info(TAG, "control config hdcPath=" + new java.io.File(hb).getAbsolutePath());
                    }
                } catch (Throwable t) {
                    Log.info(TAG, "setHdcPath failed: " + t);
                }
                Log.info(TAG, "control channel: creating HosRemoteDevice snFinal=" + snFinal);
                HosRemoteDevice dev = new HosRemoteDevice(cfg);
                device = dev;
                Log.info(TAG, "control channel: HosRemoteDevice created");
                // ★ 复刻 H.264 路径的成功做法：先 stop 一次再 start（清掉残留的 daemon）
                try {
                    dev.stopCaptureScreen();
                    Log.info(TAG, "control channel: stopCaptureScreen done");
                } catch (Throwable t) {
                    Log.info(TAG, "control channel: stopCaptureScreen ignored: " + t);
                }
                try { Thread.sleep(400); } catch (InterruptedException ignored) { }
                try {
                    // 官方 API：getScreenSize(boolean needUpDate) → com.huawei.hosscrcpy.api.Size{width,height}
                    com.huawei.hosscrcpy.api.Size sz = dev.getScreenSize(false);
                    if (sz != null) {
                        devScreenW = sz.width;
                        devScreenH = sz.height;
                        haveDeviceSize = true;      // ★ 控制通道里也标记
                    }
                    Log.info(TAG, "device screen = " + devScreenW + "x" + devScreenH);
                } catch (Throwable t) {
                    Log.info(TAG, "getScreenSize failed (will fallback): " + t);
                }

                dev.startCaptureScreen(new com.huawei.hosscrcpy.api.ScreenCapCallback() {
                    @Override public void onData(java.nio.ByteBuffer data) {
                        if (data != null) data.position(data.limit());
                    }
                    @Override public void onReady() {
                        Log.info(TAG, "control daemon ready -> switch to native touch, verifying ...");
                        useShellTouch = false;   // ★ 原生触摸可用 → 切过去（实时跟手）
                        Log.info(TAG, "native touch enabled (controlReady)");
                        new Thread(() -> {
                            boolean ok = verifyControlReady();
                            if (!ok) {
                                Log.info(TAG, "control verify failed once, retry in 600ms");
                                try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                                ok = verifyControlReady();
                            }
                            if (ok) {
                                markControlReady();
                            } else {
                                Log.info(TAG, "control verify failed twice -> keep native touch, only warn");
                                // ★ 不再立刻切回 shell（切了会变慢，反而像"掉控"✗）
                                markControlReady();     // 保持原生，日志里提示
                            }
                        }, "ctl-verify").start();
                    }
                    @Override public void onException(Throwable t) {
                        Log.info(TAG, "control daemon exception: " + t);
                        controlBootstrapping = false;
                        useShellTouch = true;    // 原生不可用 → 退回 shell 注入
                        markControlLost(String.valueOf(t.getMessage()));
                    }
                });
                Log.info(TAG, "control channel: startCaptureScreen returned");
            } catch (Throwable t) {
                Log.info(TAG, "control channel failed: " + t);
                SwingUtilities.invokeLater(() -> screenView.setHint("反控通道建立失败：" + t));
            } finally {
                controlBootstrapping = false;   // ★ 无论成败都复位，避免"already running"卡死
                Log.info(TAG, "control bootstrap thread exit, flag reset");
            }
        }, "ctl-bootstrap").start();
    }


    /** 真正校验一次：能在设备上跑通 shell 才算"连上了" */
    private boolean verifyControlReady() {
        try {
            String out = shellViaHdc("echo TAP_OK");
            boolean ok = out != null && out.contains("TAP_OK");
            Log.info(TAG, "control verify: " + (out == null ? "null" : out.trim()) + " -> " + (ok ? "OK" : "FAILED"));
            return ok;
        } catch (Throwable t) {
            Log.info(TAG, "control verify threw: " + t);
            return false;
        }
    }

    /** 反控不可用：熄灭绿点 */
    private void markControlLost(String why) {
        controlReady = false;
        SwingUtilities.invokeLater(() -> {
            for (JLabel l : dotLabels.values()) {
                l.setText("●");
                l.setForeground(FG_DIM);
                l.setToolTipText("在线 · 反控不可用：" + why);
            }
            screenView.setHint("反控不可用：" + why);
        });
    }

    /** 反控就绪：设备卡片亮绿点 */
    private void markControlReady() {
        controlReady = true;
        controlBootstrapping = false;
        SwingUtilities.invokeLater(() -> {
            for (JLabel l : dotLabels.values()) {
                l.setText("●");
                l.setForeground(new Color(0x19C37D));
                l.setToolTipText("在线 · 反控可用");
            }
            screenView.setHint("反控已就绪，正在自动连接画面…");
        });
        // 绿灯后自动连画面：不用再手动点「进入投屏」/📡
        bgExec.submit(() -> {
            try {
                Thread.sleep(300);
                if (capturing) {
                    Log.info(TAG, "auto-start video: already capturing");
                    return;
                }
                if (device != null) {
                    Log.info(TAG, "auto-start video (SDK path)");
                    SwingUtilities.invokeLater(() -> {
                        if (!capturing) startCapture();
                    });
                } else if (streamHost != null && !streamHost.isEmpty()) {
                    Log.info(TAG, "auto-start video (tcp path) host=" + streamHost);
                    SwingUtilities.invokeLater(() -> {
                        if (!capturing) promptStreamConnect();
                    });
                } else {
                    Log.info(TAG, "auto-start video: no device and no host, skip");
                }
            } catch (Throwable t) {
                Log.info(TAG, "auto-start video failed: " + t);
            }
        });
    }

    /** 小窗模式：紧凑浮窗，只显示画面（不画手机外壳） */
    private void toggleMiniWindow() {
        if (miniWin != null) {
            miniWin.dispose();
            miniWin = null;
            Log.info(TAG, "mini window off");
            return;
        }
        try {
            miniWin = new PhoneWindow(() -> frontBuf, () -> {
                miniWin = null;
                Log.info(TAG, "mini window closed by user");
            }, true);   // true = 不画外壳
            miniWin.setTouchTap(new PhoneWindow.TouchTap() {
                @Override public void onTap(int x, int y) { injectAlone(x, y, x, y, false); }
                @Override public void onSwipe(int x1, int y1, int x2, int y2) { injectAlone(x1, y1, x2, y2, true); }
                @Override public void onRightClick(int x, int y) { floatNav(2, "back"); }   // 右键=返回（原版）
                @Override public void onMiddleClick() { floatNav(3, "home"); }              // 中键=桌面（原版）
                @Override public void onPress(int x, int y) { floatPress(x, y); }
                @Override public void onDrag(int x, int y) { floatDrag(x, y); }
                @Override public void onRelease(int x, int y) { floatRelease(x, y); }
                @Override public void onWheel(int x, int y, boolean up) { floatWheel(x, y, up); }
                @Override public void onNavBack() { floatNav(2, "back"); }
                @Override public void onNavHome() { floatNav(3, "home"); }
                @Override public void onNavRecents() { floatNav(1, "recents"); }
                @Override public void onHover(int x, int y) { hoverMove(x, y); }
            });
            Log.info(TAG, "mini window on");
        } catch (Throwable t) {
            Log.info(TAG, "mini window failed: " + t);
            if (miniWin != null) { miniWin.dispose(); miniWin = null; }
            screenView.setHint("当前系统不支持浮窗");
        }
    }

    /** 真全屏：优先独占全屏；驱动不支持时（如 VM 的 Mesa/SVGA）用"无边框铺满"兜底。Esc 退出。 */
    private void toggleFullScreen() {
        GraphicsDevice gd = getGraphicsConfiguration().getDevice();
        // 已经在全屏 → 退出
        if (fullScreen) {
            gd.setFullScreenWindow(null);
            fullScreen = false;
            setVisible(true);
            return;
        }
        if (borderless) {
            exitBorderless();
            return;
        }
        // 进入：默认走"无边框铺满"（VM/虚拟显卡上独占全屏经常黑屏）
        // 想要独占全屏可用 -Dsun.exclusiveFullscreen=true
        boolean wantExclusive = "true".equalsIgnoreCase(System.getProperty("sun.exclusiveFullscreen"));
        if (wantExclusive && gd.isFullScreenSupported()) {
            setVisible(true);
            gd.setFullScreenWindow(this);
            fullScreen = true;
            Log.info(TAG, "exclusive fullscreen");
        } else {
            enterBorderless(gd);
            Log.info(TAG, "borderless fullscreen (exclusive not supported)");
        }
    }

    /** 无边框全屏：JFrame 必须先 dispose 才能 setUndecorated */
    private void enterBorderless(GraphicsDevice gd) {
        restoreBounds = getBounds();
        dispose();
        setUndecorated(true);
        java.awt.Rectangle b = gd.getDefaultConfiguration().getBounds();
        setBounds(b);
        setVisible(true);
        borderless = true;
        Log.info(TAG, "enter borderless fullscreen " + b.width + "x" + b.height);
    }

    private void exitBorderless() {
        dispose();
        setUndecorated(false);
        if (restoreBounds != null) {
            setBounds(restoreBounds);
        } else {
            setSize(1280, 820);
            setLocationRelativeTo(null);
        }
        setVisible(true);
        borderless = false;
        Log.info(TAG, "exit borderless fullscreen");
    }

    /** Esc 退出最大化 */
    private void bindEsc() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "exitMax");
        getRootPane().getActionMap().put("exitMax", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (fullScreen) {
                    getGraphicsConfiguration().getDevice().setFullScreenWindow(null);
                    fullScreen = false;
                } else if (borderless) {
                    exitBorderless();
                } else if ((getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                    setExtendedState(Frame.NORMAL);
                }
            }
        });
    }

    // ---------------- 小工具 ----------------
    private JComponent emptyTip(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG_DIM);
        l.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        l.setBorder(new EmptyBorder(8, 6, 8, 6));
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(l, BorderLayout.WEST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return p;
    }

    private void setConnected(boolean on) {
        statusDot.setForeground(on ? OK : FG_DIM);
        statusText.setText(on ? "已连接" : "未连接");
        statusText.setForeground(on ? OK : FG_DIM);
    }

    private JButton iconButton(String glyph, String tip) {
        JButton b = new JButton(glyph);
        b.setFont(new Font("Dialog", Font.PLAIN, 16));
        b.setForeground(FG);
        b.setBackground(BG_HOVER);
        b.setBorder(BorderFactory.createLineBorder(LINE));
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(34, 30));
        b.setToolTipText(tip);
        return b;
    }

    private JButton barButton(String glyph, String text, Runnable onClick) {
        JButton b = new JButton(glyph + " " + text);
        b.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        b.setForeground(FG);
        b.setBackground(BG_HOVER);
        b.setBorder(BorderFactory.createLineBorder(LINE));
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(86, 34));
        b.setToolTipText(text);
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(new Color(0x2F3542)); }
            @Override public void mouseExited(MouseEvent e) { b.setBackground(BG_HOVER); }
        });
        b.addActionListener(e -> onClick.run());
        return b;
    }

    private JButton primaryButton(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        b.setForeground(new Color(0x06121F));
        b.setBackground(ACCENT);
        b.setBorder(new EmptyBorder(8, 18, 8, 18));
        b.setFocusPainted(false);
        return b;
    }

    // ---------------- 画面组件 ----------------
    /** 手机外壳尺寸：竖屏按高度、横屏按宽度；（返回 {w,h}） */
    /** 手机外壳尺寸：竖屏按高度、横屏按宽度；（返回 {w,h}） */
    private static int[] shellDims(double ar, int viewW, int viewH) {
        double dw, dh;
        if (ar >= 1.0) {
            dw = Math.max(Math.min(viewW * 0.62, 1180), 420);
            dh = dw / ar;
            if (dh > viewH - 100) {
                dh = viewH - 100;
                dw = dh * ar;
            }
        } else {
            dh = Math.max(Math.min(viewH * 0.62, 980), 420);
            dw = dh * ar;
            if (dw > viewW - 120) {
                dw = viewW - 120;
                dh = dw / ar;
            }
        }
        if (dw < 1) dw = 1;
        if (dh < 1) dh = 1;
        return new int[]{(int) dw, (int) dh};
    }

    private static class ScreenView extends JPanel {
        private java.awt.image.BufferedImage image;
        private String hint = "";
        private final java.awt.Rectangle imgRect = new java.awt.Rectangle();
        private TouchListener touchListener;
        private boolean phoneShell = false;
        void setPhoneShell(boolean on) { this.phoneShell = on; repaint(); }
        private java.awt.image.BufferedImage pending;
        private volatile boolean repaintPending = false;

        /** 触控回调：坐标已换算成设备像素 */
        interface TouchListener {
            void onDown(int dx, int dy, String button);
            void onMove(int dx, int dy, String button);
            void onUp(int dx, int dy, String button);
            void onWheel(int dx, int dy, boolean up);
            default void onHover(int dx, int dy) { }
        }

        void setTouchListener(TouchListener l) {
            this.touchListener = l;
        }

        ScreenView() {
            setBackground(new Color(0x0E1014));
            setBorder(BorderFactory.createLineBorder(LINE, 1, true));

            MouseAdapter ma = new MouseAdapter() {
                private String buttonOf(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) return HosRemoteDevice.MOUSE_LEFT;
                    if (SwingUtilities.isRightMouseButton(e)) return HosRemoteDevice.MOUSE_RIGHT;
                    if (SwingUtilities.isMiddleMouseButton(e)) return HosRemoteDevice.MOUSE_MIDDLE;
                    return null;
                }
                private int[] toDevice(Point p) {
                    if (image == null || imgRect.width <= 0) return null;
                    if (!imgRect.contains(p)) return null;
                    double sx = (double) image.getWidth() / imgRect.width;
                    double sy = (double) image.getHeight() / imgRect.height;
                    return new int[]{(int) ((p.x - imgRect.x) * sx), (int) ((p.y - imgRect.y) * sy)};
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (touchListener == null) {
                        Log.info(TAG, "no touch listener");
                        return;
                    }
                    int[] d = toDevice(e.getPoint());
                    if (d == null) {
                        Log.info(TAG, "click outside image: p=" + e.getPoint()
                                + " imgRect=" + imgRect + " image="
                                + (image == null ? "null" : image.getWidth() + "x" + image.getHeight()));
                        return;
                    }
                    touchListener.onDown(d[0], d[1], buttonOf(e));
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (touchListener == null) return;
                    int[] d = toDevice(e.getPoint());
                    if (d != null) touchListener.onUp(d[0], d[1], buttonOf(e));
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (touchListener == null) return;
                    int[] d = toDevice(e.getPoint());
                    if (d != null) touchListener.onMove(d[0], d[1], buttonOf(e));
                }
                @Override public void mouseMoved(MouseEvent e) {
                    if (touchListener == null) return;
                    int[] d = toDevice(e.getPoint());
                    if (d != null) {
                        // ★ 屏幕上必须留一个可见指针：设备端指针不一定画进画面，
                        //   隐藏本机指针后用户会"看不到自己在指哪"（2026-10-29 实测）。
                        setCursor(Cursor.getDefaultCursor());
                        touchListener.onHover(d[0], d[1]);
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
                @Override public void mouseExited(MouseEvent e) {
                    setCursor(Cursor.getDefaultCursor());
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    if (touchListener == null) return;
                    int[] d = toDevice(e.getPoint());
                    if (d != null) touchListener.onWheel(d[0], d[1], e.getWheelRotation() < 0);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
            setFocusable(true);   // 让画面区能接键盘
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { requestFocusInWindow(); }
            });
        }

        /** 帧合并：EDT 忙时只保留最新一帧，避免把 EDT 淹死（最大化时卡顿的主因）*/
        private java.awt.Rectangle fastRect;

        java.awt.image.BufferedImage getShownImage() { return image; }

        private java.awt.image.BufferedImage displayImage;
        private volatile boolean hideLocalCursor;

        void setHideLocalCursor(boolean b) { hideLocalCursor = b; }

        void setDisplay(java.awt.image.BufferedImage img, java.awt.Rectangle r) {
            this.displayImage = img;
            this.fastRect = r;
        }

        void setImage(java.awt.image.BufferedImage img) {
            this.pending = img;
            this.hint = "";
            if (repaintPending) return;
            repaintPending = true;
            SwingUtilities.invokeLater(() -> {
                repaintPending = false;
                if (pending != null) image = pending;
                repaint();
            });
        }

        /** 返回一份拷贝：解码器会复用同一张 BufferedImage，直接写盘可能截到半张 */
        java.awt.image.BufferedImage getImage() {
            if (image == null) return null;
            java.awt.image.BufferedImage copy = new java.awt.image.BufferedImage(
                    image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_3BYTE_BGR);
            java.awt.Graphics2D g = copy.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return copy;
        }

        void setHint(String h) {
            this.hint = h == null ? "" : h;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            // 双线性：降采样时比最近邻清晰得多；配合 SPEED 渲染提示，38fps 也扛得住
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

            if (image != null && phoneShell) {
                // ---- 纯享模式：像"小窗"一样悬浮的实体手机，不铺满屏幕、不画挖孔 ----
                double ar = 1.0 * image.getWidth() / image.getHeight();

                // 尺寸：竖屏按高度的 62%，横屏按宽度的 62%（横屏适配）
                int[] dims = shellDims(ar, getWidth(), getHeight());
                final int w = dims[0];
                final int h = dims[1];
                int bezel = Math.max(6, w / 45);
                int radius = Math.max(14, w / 13);
                int ox = (getWidth() - w) / 2;
                int oy = (getHeight() - h) / 2;

                // 背景：柔和径向渐变，避免"一大块死黑"
                java.awt.RadialGradientPaint bg = new java.awt.RadialGradientPaint(
                        new java.awt.geom.Point2D.Float(getWidth() / 2f, getHeight() / 2f),
                        Math.max(getWidth(), getHeight()) * 0.75f,
                        new float[]{0f, 1f},
                        new Color[]{new Color(0x151A22), new Color(0x06080B)});
                g2.setPaint(bg);
                g2.fillRect(0, 0, getWidth(), getHeight());

                // 手机投影（软阴影，制造"浮起来"的感觉）
                for (int i = 14; i >= 1; i--) {
                    g2.setColor(new Color(0, 0, 0, 6 + i));
                    g2.fillRoundRect(ox - bezel - i, oy - bezel - i + 6,
                            w + bezel * 2 + i * 2, h + bezel * 2 + i * 2,
                            radius + bezel + i, radius + bezel + i);
                }
                // 外壳
                g2.setColor(new Color(0x0A0C11));
                g2.fillRoundRect(ox - bezel, oy - bezel, w + bezel * 2, h + bezel * 2,
                        radius + bezel, radius + bezel);
                // 金属边
                g2.setColor(new Color(0x3A4152));
                g2.setStroke(new BasicStroke(2f));
                g2.drawRoundRect(ox - bezel, oy - bezel, w + bezel * 2, h + bezel * 2,
                        radius + bezel, radius + bezel);
                // 侧键（电源键 + 音量键）—— 只在竖屏画，横屏不画
                g2.setColor(new Color(0x2C3240));
                if (ar < 1.0) {
                g2.fillRoundRect(ox - bezel - 3, oy + (int) (h * 0.20), 4, (int) (h * 0.09), 2, 2);
                g2.fillRoundRect(ox + w + bezel - 1, oy + (int) (h * 0.16), 4, (int) (h * 0.06), 2, 2);
                g2.fillRoundRect(ox + w + bezel - 1, oy + (int) (h * 0.25), 4, (int) (h * 0.10), 2, 2);
                }
                // 屏幕（圆角裁切）
                java.awt.Shape old = g2.getClip();
                g2.setClip(new java.awt.geom.RoundRectangle2D.Float(ox, oy, w, h, radius, radius));
                g2.drawImage(image, ox, oy, w, h, null);
                g2.setClip(old);
                imgRect.setBounds(ox, oy, w, h);
                // 只保留底部 home 条，不要挖孔
                int hbw = (int) (w * 0.26);
                g2.setColor(new Color(0xB8C0CE));
                g2.fillRoundRect(ox + (w - hbw) / 2, oy + h - Math.max(5, bezel / 2) - 3, hbw, 3, 3, 3);
                g2.dispose();
                return;
            }
            if (displayImage != null && fastRect != null) {
                // 快速路径：解码线程已经缩放好，这里 1:1 贴图（无缩放开销，不撕裂）
                imgRect.setBounds(fastRect);
                g2.drawImage(displayImage, fastRect.x, fastRect.y, null);
            } else if (image != null) {
                double aspect = 1.0 * image.getWidth() / image.getHeight();
                int w = (int) Math.min(getWidth() - 16, aspect * (getHeight() - 16));
                int h = (int) (w / aspect);
                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;
                imgRect.setBounds(x, y, w, h);
                g2.drawImage(image, x, y, w, h, null);
            } else if (!hint.isEmpty()) {
                g2.setColor(FG_DIM);
                g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(hint,
                        (getWidth() - fm.stringWidth(hint)) / 2,
                        getHeight() / 2);
            }
        }
    }
}
