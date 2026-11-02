package utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProcessExecutor {
    private static final String TAG = "ProcessExecutor";
    private static final long TIMEOUT = 4;

    /** 以数组形式执行（参数里有空格时用这个，避免被按空格切碎） */
    public String callProcess(String[] argv, long timeOut) {
        return run(argv, null, timeOut);
    }

    public String callProcess(String cmd) {
        return callProcess(cmd, null, TIMEOUT);
    }

    public String callProcess(String cmd, int timeOut) {
        return callProcess(cmd, null, timeOut);
    }

    /** 优先用程序目录自带的 hdc.exe，其次才用 PATH 里的 hdc */
    private static String resolveHdc() {
        String[] dirs = {System.getProperty("user.dir"), System.getProperty("user.dir") + File.separator + "target"};
        String[] names = {"hdc.exe", "hdc"};
        for (String d : dirs) {
            for (String n : names) {
                File f = new File(d, n);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return "hdc";
    }

    /** "hdc ..." 前缀换成程序目录里的绝对路径（含空格时加引号，splitCommand 会去掉） */
    private static String resolveHdcCmd(String cmd) {
        if (cmd == null) return "";
        if (cmd.equals("hdc")) return "\"" + resolveHdc() + "\"";
        if (cmd.startsWith("hdc ")) return "\"" + resolveHdc() + "\"" + cmd.substring(3);
        return cmd;
    }

    /** 按空白切分，但双引号里的内容算一个参数（原来直接 split(" ")，
     *  "C:\Program Files\...\hdc.exe" 这种路径会被切成两段 → 起不来） */
    private static String[] splitCommand(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (!quoted && Character.isWhitespace(ch)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    public String callProcess(String cmd, File file, long timeOut) {
        return run(splitCommand(resolveHdcCmd(cmd)), file, timeOut);
    }

    /** 统一执行：先起读线程再 waitFor。
     *  ★ 顺序不能反：原来先 waitFor 再读输出，hdc 输出多到把 64KB 管道写满时，
     *    子进程卡在写、我们卡在等退出 → 双方互等到超时（白白多等 4-6 秒，日志还是空的）。 */
    private String run(String[] argv, File dir, long timeOut) {
        final StringBuilder sb = new StringBuilder();
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            if (dir != null) pb.directory(dir);
            process = pb.start();
            final Process p = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sb.append(line).append(System.lineSeparator());
                    }
                } catch (Throwable ignored) {
                }
            }, "proc-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeOut, TimeUnit.SECONDS);
            if (!done) {
                Log.info(TAG, "process timeout(" + timeOut + "s) -> killed: " + String.join(" ", argv));
                p.destroyForcibly();
            }
            reader.join(800);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
        } catch (Throwable t) {
            Log.info(TAG, "callProcess failed: " + t);
        }
        return sb.toString();
    }
}
