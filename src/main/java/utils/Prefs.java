package utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Properties;

/**
 * 轻量配置存储：写在文件里（%APPDATA%\\sun\\ui-prefs.properties），完全不碰 Windows 注册表。
 *
 * 背景：java.util.prefs 在 Windows 上走注册表，遇到权限受限（比如键是管理员建的、
 * 或进程在受限沙箱里跑）就会报
 *   "Could not open windows registry node Software\\JavaSoft\\Prefs\\ui ... Access denied"
 * 放到文件里就没有这个问题，也方便绿色版拷贝。
 */
public final class Prefs {

    private final Properties p = new Properties();
    private final File file;
    private final Object lock = new Object();

    public Prefs(String node, Class<?> owner) {
        File dir = chooseDir();
        this.file = new File(dir, node + "-prefs.properties");
        load();
    }

    /** 优先 %APPDATA%\sun；不可写（受限账号/沙箱/绿色版）时退到程序目录下的 sun-data */
    private static File chooseDir() {
        String base = System.getenv("APPDATA");
        if (base != null && !base.trim().isEmpty()) {
            File d = new File(base, "sun");
            if (writable(d)) return d;
        }
        File d2 = new File(System.getProperty("user.dir", "."), "sun-data");
        if (writable(d2)) return d2;
        File d3 = new File(System.getProperty("user.home", "."), ".sun");
        writable(d3);
        return d3;
    }

    private static boolean writable(File d) {
        try {
            if (!d.isDirectory() && !d.mkdirs()) return false;
            File probe = new File(d, ".probe");
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(probe)) { f.write(1); }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            p.load(r);
        } catch (Throwable ignored) { }
    }

    private void save() {
        synchronized (lock) {
            try {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8")) {
                    p.store(w, "sun prefs");
                }
            } catch (Throwable ignored) { }
        }
    }

    public String get(String key, String def) {
        synchronized (lock) {
            return p.getProperty(key, def);
        }
    }

    public void put(String key, String value) {
        synchronized (lock) {
            if (value == null) p.remove(key); else p.setProperty(key, value);
        }
        save();
    }

    public boolean getBoolean(String key, boolean def) {
        String v = get(key, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void putBoolean(String key, boolean value) {
        put(key, Boolean.toString(value));
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        try { return v == null ? def : Integer.parseInt(v); } catch (Throwable t) { return def; }
    }

    public void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    public double getDouble(String key, double def) {
        String v = get(key, null);
        try { return v == null ? def : Double.parseDouble(v); } catch (Throwable t) { return def; }
    }

    public void putDouble(String key, double value) {
        put(key, Double.toString(value));
    }

    /** 兼容 Preferences.flush()：写文件时已经即时保存，这里空实现 */
    public void flush() { save(); }
}