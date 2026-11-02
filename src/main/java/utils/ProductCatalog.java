package utils;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置产品信息库：型号 → 产品名 / 类型 / 屏幕尺寸 / 分辨率。
 *
 * 为什么不用"运行时联网抓取"：
 *  · 华为官网没有公开的产品 API，抓页面属于反向工程，随时会变 ✗
 *  · 抓页面还可能 403 / 被风控 ✗ 运行时依赖网络会拖慢启动 ✗
 *  → 改为内置常见型号（数据来自官网规格页），未命中的型号按 devicetype 兜底 ✓
 */
public final class ProductCatalog {

    public static final class Info {
        public final String model;      // PLU-AL10
        public final String product;    // HUAWEI Mate 70 RS 非凡大师
        public final String kind;       // phone / tablet / 2in1
        public final double inch;       // 6.9
        public final int w, h;          // 2832 x 1316（物理像素）

        Info(String model, String product, String kind, double inch, int w, int h) {
            this.model = model; this.product = product; this.kind = kind;
            this.inch = inch; this.w = w; this.h = h;
        }

        @Override public String toString() {
            return product + " · " + (inch > 0 ? fmt(inch) + "\" · " : "")
                    + Math.max(w, h) + "×" + Math.min(w, h);
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d);
    }

    private static final Map<String, Info> DB = new HashMap<>();
    static {
        // 手机
        add("PLU-AL10", "HUAWEI Mate 70 RS 非凡大师", "phone", 6.9, 2832, 1316);
        // 折叠屏（横阔折）：内屏 7.7" 2584×1828 / 外屏 5.4" 1848×1264
        add("HOP-AL00", "HUAWEI Pura X Max", "fold", 7.7, 2584, 1828);
        add("HOP-AL10", "HUAWEI Pura X Max 典藏版", "fold", 7.7, 2584, 1828);
        add("PLR-AL00", "HUAWEI Mate 70 Pro",         "phone", 6.7, 2832, 1316);
        add("PLA-AL00", "HUAWEI Mate 70",             "phone", 6.5, 2688, 1216);
        add("ALN-AL00", "HUAWEI Mate 60 Pro",         "phone", 6.82, 2720, 1260);
        add("NOH-AN00", "HUAWEI Mate 40 Pro",         "phone", 6.76, 2772, 1344);
        add("LIO-AN00", "HUAWEI Mate 30 Pro",         "phone", 6.53, 2400, 1176);
        // 平板 / 二合一
        add("GRL-W00",  "HUAWEI MatePad Pro 13.2",    "tablet", 13.2, 2880, 1920);
        add("GOT-AL09", "HUAWEI MatePad Pro 11",      "tablet", 11.0, 2560, 1600);
        add("DBY-W09",  "HUAWEI MatePad 11",          "tablet", 11.0, 2560, 1600);
        add("BAH4-W09", "HUAWEI MatePad Pro 10.8",    "tablet", 10.8, 2560, 1600);
        add("MRX-AL09", "HUAWEI MatePad 10.8",        "tablet", 10.8, 2560, 1600);
        // MatePad Edge（14.2 英寸 OLED 二合一）实测型号 QXS-W10
        add("QXS-W10",  "HUAWEI MatePad Edge",        "2in1",  14.2, 3120, 2080);
        add("EDGE-W00", "HUAWEI MatePad Edge",        "2in1",  14.2, 3120, 2080);
        add("EDG-W00",  "HUAWEI MatePad Edge",        "2in1",  14.2, 3120, 2080);
        // 鸿蒙 PC
        add("MATEBOOK-E", "HUAWEI MateBook E",        "2in1",  12.6, 2560, 1600);
    }

    private static void add(String model, String product, String kind, double inch, int w, int h) {
        DB.put(model.toUpperCase(), new Info(model, product, kind, inch, w, h));
    }

    /** 产品库没收录时：用 devicetype / 型号后缀 / 产品名 / 屏幕比例 综合判断手机还是平板 */
    public static String classify(String devicetype, String model, String productName, int w, int h) {
        String t = devicetype == null ? "" : devicetype.toLowerCase();
        if (t.contains("2in1") || t.contains("pc") || t.contains("desktop")) return "2in1";
        if (t.contains("tablet") || t.contains("pad")) return "tablet";
        if (t.contains("phone") || t.contains("mobile") || t.contains("default")) return "phone";

        String n = productName == null ? "" : productName.toLowerCase();
        if (n.contains("matepad") || n.contains("matebook") || n.contains("pad")) return "tablet";

        // 型号后缀规律（华为）：平板常见 W00 / W09 / AL09 / W10；手机常见 AL00 / AL10 / AN00 / AL80
        String m = model == null ? "" : model.toUpperCase();
        if (m.startsWith("HOP-")) return "fold";        // Pura X 系列折叠屏
        if (m.matches(".*(W00|W09|W10|AL09|W19|W29|W39|W49)$")) return "tablet";
        if (m.matches(".*(AL00|AL10|AL20|AL80|AN00|AN10|AN20|TL00|Tl00)$")) return "phone";

        // 最后用屏幕比例兜底：接近方形/宽屏 → 平板；明显细长 → 手机
        if (w > 0 && h > 0) {
            double ar = (double) Math.max(w, h) / Math.min(w, h);
            if (ar < 1.45) return "tablet";     // 4:3 / 16:10 一类
            return "phone";                      // 19.5:9 一类
        }
        return "unknown";
    }

    /** 按型号查（不区分大小写）；查不到返回 null */
    public static Info byModel(String model) {
        if (model == null) return null;
        String m = model.trim().toUpperCase();
        Info i = DB.get(m);
        if (i != null) return i;
        // 前缀匹配（型号后常带后缀，如 PLU-AL10A）
        for (Map.Entry<String, Info> e : DB.entrySet()) {
            if (m.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** 按产品名模糊查（含 MatePad / Edge / MateBook 等关键字） */
    public static Info byProductName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.contains("pura x max")) return DB.get("HOP-AL00");
        if (n.contains("matepad edge")) return DB.get("QXS-W10");
        if (n.contains("matepad pro 13")) return DB.get("GRL-W00");
        if (n.contains("matepad")) return DB.get("GOT-AL09");
        if (n.contains("matebook") || n.contains("edge")) return DB.get("MATEBOOK-E");
        if (n.contains("mate 70 rs")) return DB.get("PLU-AL10");
        if (n.contains("mate 70")) return DB.get("PLR-AL00");
        if (n.contains("mate 60")) return DB.get("ALN-AL00");
        return null;
    }

    private ProductCatalog() { }
}
