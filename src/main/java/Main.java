import com.formdev.flatlaf.FlatDarkLaf;
import ui.SunFrame;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** sun 版入口：单实例锁 + 深色主题 + 全局字体 */
public class Main {

    private static FileLock lock;
    private static RandomAccessFile lockFile;

    public static void main(String[] args) {
        // ---- 单实例锁：同一台机器只允许开一个，避免同设备被抢资源 ----
        if (!acquireSingleInstanceLock()) {
            JOptionPane.showMessageDialog(null,
                    "HOScrcpy 已经在运行中。\n\n" +
                    "同一台设备被两个界面同时连接会互相抢资源，\n" +
                    "请先关闭已打开的窗口。",
                    "已有一个实例在运行", JOptionPane.WARNING_MESSAGE);
            System.exit(1);
        }

        FlatDarkLaf.install();
        String font = "Microsoft YaHei";
        if (!hasFont(font)) font = "Dialog";
        UIManager.put("defaultFont", new Font(font, Font.PLAIN, 13));
        UIManager.put("Button.font", new Font(font, Font.PLAIN, 13));
        UIManager.put("Label.font", new Font(font, Font.PLAIN, 13));
        UIManager.put("ComboBox.font", new Font(font, Font.PLAIN, 13));
        UIManager.put("ToolTip.font", new Font(font, Font.PLAIN, 13));

        SwingUtilities.invokeLater(() -> {
            SunFrame f = new SunFrame();
            f.setVisible(true);
        });
    }

    /** 用文件锁做单实例；进程退出时锁自动释放 */
    private static boolean acquireSingleInstanceLock() {
        try {
            File f = new File(System.getProperty("user.dir"), "HOScrcpy.lock");
            lockFile = new RandomAccessFile(f, "rw");
            lock = lockFile.getChannel().tryLock();
            if (lock == null) {
                return false;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { lock.release(); } catch (Exception ignored) { }
                try { lockFile.close(); } catch (Exception ignored) { }
            }));
            return true;
        } catch (Exception e) {
            // 锁文件不可写时不阻塞启动
            return true;
        }
    }

    private static boolean hasFont(String name) {
        for (String n : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
            if (n.equals(name)) return true;
        }
        return false;
    }
}
