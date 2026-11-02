package utils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 简易授权码：
 *   机器码 = 本机 UUID/主机名的哈希（16 位十六进制，可读给用户）
 *   授权码 = HMAC-SHA256(机器码, 内置密钥) 的前 12 位（大写，分组显示）
 * 用户把机器码发给我们 → 我们用同样算法算一个授权码发回去 → 程序校验通过才启动。
 */
public final class License {

    private static final String SECRET = "Sun-HOScrcpy-2026-TestBuild-9f3a7c";

    /** 生成机器码（16 位十六进制） */
    public static String machineCode() {
        String raw = rawMachineId();
        long h1 = fnv(raw, 0xcbf29ce484222325L);
        long h2 = fnv(raw + "|salt", 0x84222325cbf29ce4L);
        return String.format("%016X", h1 ^ h2);
    }

    /** 32 进制字母表（去掉容易看错的 0/O/1/I/L） */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    /** 由机器码算授权码：4 位短码（形如 K7M3） */
    public static String keyFor(String machineCode) {
        try {
            String mc = machineCode == null ? "" : machineCode.trim().toUpperCase();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(mc.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET[(out[i] & 0xFF) % ALPHABET.length]);
            }
            return sb.toString();
        } catch (Throwable t) {
            return "XXXX";
        }
    }

    /** 校验用户输入的授权码 */
    public static boolean verify(String machineCode, String input) {
        if (input == null) return false;
        String a = input.trim().toUpperCase().replace(" ", "");
        String b = keyFor(machineCode).replace(" ", "");
        return a.length() > 0 && a.equals(b);
    }

    private static String rawMachineId() {
        String uuid = null;
        try {
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_ComputerSystemProduct).UUID").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "GBK"));
            uuid = r.readLine();
            p.waitFor();
        } catch (Throwable ignored) { }
        if (uuid == null || uuid.trim().isEmpty()) uuid = "nowmic";
        String host = "unknown";
        try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Throwable ignored) { }
        return uuid.trim() + "|" + host;
    }

    private static long fnv(String s, long basis) {
        long h = basis;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        return h;
    }

    private License() { }
}
