package utils;

import org.apache.commons.lang3.StringUtils;
import utils.entity.Device;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class HdcUtil {
    private static final String TAG = "HdcUtil";
    /**
     * 从 hdc list targets 的一行输出中提取序列号。
     * 未授权/已连接时输出形如 "<你的设备SN>\tUnauthorized" 或 "SN\tUSB\tConnected"，
     * 需要截掉状态列，否则会把状态当成 SN 传给 -t 参数导致所有命令失败。
     */
    private static String extractSn(String line) {
        if (StringUtils.isEmpty(line)) {
            return "";
        }
        String trimmed = line.trim();
        if (StringUtils.isEmpty(trimmed) || trimmed.startsWith("[")) {
            return "";
        }
        String[] parts = trimmed.split("\\s+");
        return parts.length == 0 ? "" : parts[0];
    }

    public static List<Device> getRemoteDevice() {
        List<Device> list = new ArrayList<>();
        HashSet<String> remoteIp = SettingUtil.getRemoteIp();
        for (String ip : remoteIp) {
            ProcessExecutor processExecutor = new ProcessExecutor();
            String result = processExecutor.callProcess(new String[]{hdcBin(), "list", "targets"}, 10);
            Log.info(TAG, "hdc out: " + (result == null ? "null" : result.trim().replace("\n", " | ")));
            if (StringUtils.isNotEmpty(result) && !result.contains("Empty")) {
                if (result.contains("failed")) {
                    continue;
                }
                for (String line : result.split(System.lineSeparator())) {
                    // hdc list targets 输出可能带状态列（如 "SN\tUnauthorized"、"SN\tUSB\tConnected"），只取第一列作为 SN
                    String sn = extractSn(line);
                    if (StringUtils.isEmpty(sn)) {
                        continue;
                    }
                    Device device = new Device(ip, sn);
                    list.add(device);
                }
            }

        }
        return list;
    }

    /** 找到可用的 hdc 可执行文件（优先工作目录 / target 下那个）
     *  所有 hdc 调用都必须走这里：PATH 里可能有别的版本（11.128 上就有 6 个），
     *  混用会出现"list targets 有设备，但 -t 查参数说找不到设备"。 */
    public static String hdcBin() {
        String[] dirs = {System.getProperty("user.dir"),
                System.getProperty("user.dir") + java.io.File.separator + "target"};
        for (String d : dirs) {
            for (String n : new String[]{"hdc.exe", "hdc"}) {
                java.io.File f = new java.io.File(d, n);
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        if (!hdcFallbackLogged) {
            hdcFallbackLogged = true;
            Log.info(TAG, "程序目录没有 hdc，改用 PATH 里的 hdc（版本可能与 SDK 不匹配）");
        }
        return "hdc";
    }

    private static volatile boolean hdcFallbackLogged = false;

    public static List<Device> getLocalDevices() {
        List<Device> list = new ArrayList<>();
        // ★ 不再用 -s 127.0.0.1:8710（那是老版本 HOScrcpy 的本地中转端口，
        //   现在 hdc 直接连设备端口，加了 -s 反而查不到设备 ✗）
        ProcessExecutor processExecutor = new ProcessExecutor();
        String result = processExecutor.callProcess(new String[]{hdcBin(), "list", "targets"}, 10);
        Log.info(TAG, "hdc out: " + (result == null ? "null" : result.trim().replace("\n", " | ")));
        if (StringUtils.isNotEmpty(result) && !result.contains("Empty")) {
            if (result.contains("failed")) {
                return list;
            }
            for (String line : result.split(System.lineSeparator())) {
                String sn = extractSn(line);
                if (StringUtils.isEmpty(sn)) {
                    continue;
                }
                Device device = new Device(sn);
                list.add(device);
            }
        }
        return list;
    }

    /** 缓存：SN -> 设备类型（phone/tablet/2in1/wearable/tv/car） */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> TYPE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    /** 缓存：SN -> 产品名（如 HUAWEI MatePad Edge） */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> NAME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 查询设备类型。失败返回空串。 */
    public static String queryDeviceType(String sn) {
        if (sn == null || sn.isEmpty()) return "";
        if (TYPE_CACHE.containsKey(sn)) return TYPE_CACHE.get(sn);
        String type = "";
        try {
            ProcessExecutor ex = new ProcessExecutor();
            String out = ex.callProcess(new String[]{hdcBin(), "-t", sn,
                    "shell", "param get const.product.devicetype"}, 6);
            if (StringUtils.isNotEmpty(out)) {
                type = out.trim().split("\\s+")[0];
            }
        } catch (Exception ignored) {
        }
        TYPE_CACHE.put(sn, type);
        return type;
    }

    /** 查询产品名。失败返回空串。 */
    /** 型号，如 PLU-AL10 */
    public static String queryDeviceModel(String sn) {
        return queryParam(sn, "const.product.model");
    }

    /** 品牌，如 HUAWEI */
    public static String queryDeviceBrand(String sn) {
        return queryParam(sn, "const.product.brand");
    }

    private static final java.util.Map<String, String> PARAM_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 通用：读取设备 param（带缓存、6 秒超时） */
    public static String queryParam(String sn, String key) {
        if (sn == null || sn.isEmpty()) return "";
        String ck = sn + "|" + key;
        if (PARAM_CACHE.containsKey(ck)) return PARAM_CACHE.get(ck);
        String val = "";
        try {
            ProcessExecutor ex = new ProcessExecutor();
            String out = ex.callProcess(new String[]{hdcBin(), "-t", sn,
                    "shell", "param get " + key}, 6);
            if (StringUtils.isNotEmpty(out)) val = out.trim().replace("\r", "");
        } catch (Exception ignored) {
        }
        PARAM_CACHE.put(ck, val);
        return val;
    }

    public static String queryDeviceName(String sn) {
        if (sn == null || sn.isEmpty()) return "";
        if (NAME_CACHE.containsKey(sn)) return NAME_CACHE.get(sn);
        String name = "";
        try {
            ProcessExecutor ex = new ProcessExecutor();
            String out = ex.callProcess(new String[]{hdcBin(), "-t", sn,
                    "shell", "param get const.product.name"}, 6);
            if (StringUtils.isNotEmpty(out)) {
                name = out.trim().replace("\r", "");
            }
        } catch (Exception ignored) {
        }
        NAME_CACHE.put(sn, name);
        return name;
    }


    /** 缓存：SN -> 是否具备 H.264 编码能力（设备需要有 libCPHMediaEngine.z.so） */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> VIDEO_CAP_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 探测设备能否编码 H.264。
     * 依据：设备端 /system/lib64/libCPHMediaEngine.z.so 是否存在。
     * 缺这个库时视频流会"连上但一个字节都没有"，SDK 还会每 15 秒 kill 一次 uitest。
     */
    public static boolean canVideoStream(String sn) {
        if (sn == null || sn.isEmpty()) return false;
        Boolean cached = VIDEO_CAP_CACHE.get(sn);
        if (cached != null) return cached;
        boolean ok = false;
        try {
            ProcessExecutor ex = new ProcessExecutor();
            String out = ex.callProcess(new String[]{hdcBin(), "-t", sn,
                    "shell", "ls /system/lib64/libCPHMediaEngine.z.so"}, 6);
            ok = out != null && out.contains("libCPHMediaEngine");
        } catch (Exception ignored) {
        }
        VIDEO_CAP_CACHE.put(sn, ok);
        Log.info(TAG, "device " + sn + " canVideoStream=" + ok);
        return ok;
    }

}
