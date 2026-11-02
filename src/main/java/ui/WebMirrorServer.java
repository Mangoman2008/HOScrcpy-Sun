package ui;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * 浏览器投屏 + 操控（局域网）。
 * 纯 JDK 实现：HttpServer 太重，这里直接用 ServerSocket 做最小 HTTP + WebSocket（RFC6455）。
 * 浏览器打开 http://<本机IP>:8765/ 即可看到画面，鼠标点击/拖动/滚轮会转成设备触控。
 */
public class WebMirrorServer {

    public interface Commands {
        void onTap(int imgX, int imgY);
        void onSwipe(int x1, int y1, int x2, int y2);
        void onWheel(int imgX, int imgY, boolean up);
        void onKey(String name);
    }

    private final Supplier<BufferedImage> frames;
    private final Commands cmds;
    private final int port;
    private ServerSocket server;
    private volatile boolean running = false;
    private Thread acceptThread;
    private Thread pumpThread;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile byte[] lastJpeg;
    private volatile int lastW = 0, lastH = 0;
    private volatile String lastError;

    public WebMirrorServer(Supplier<BufferedImage> frames, Commands cmds, int port) {
        this.frames = frames;
        this.cmds = cmds;
        this.port = port;
    }

    public int getPort() { return port; }
    public String getLastError() { return lastError; }
    public int clientCount() { return clients.size(); }

    public String url() {
        return "http://" + lanIp() + ":" + port + "/";
    }

    public void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "web-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        pumpThread = new Thread(this::pumpLoop, "web-pump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    public void stop() {
        running = false;
        for (Client c : clients) c.close();
        clients.clear();
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
    }

    private static String lanIp() {
        try {
            java.util.Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> a = ni.getInetAddresses();
                while (a.hasMoreElements()) {
                    InetAddress ia = a.nextElement();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) return ia.getHostAddress();
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Thread(() -> handle(s), "web-client").start();
            } catch (Throwable t) {
                if (running) lastError = String.valueOf(t);
                try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void handle(Socket s) {
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String path = "/";
            String[] parts = reqLine.split(" ");
            if (parts.length >= 2) path = parts[1];
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] body = PAGE.getBytes(StandardCharsets.UTF_8);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                s.close();
                return;
            }
            String key = headers.get("sec-websocket-key");
            if ("/ws".equals(path) && key != null) {
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                String resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                        + "Connection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n";
                out.write(resp.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Client c = new Client(s, in, out);
                clients.add(c);
                c.readLoop();
                return;
            }
            byte[] nf = "not found".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 404 Not Found\r\nContent-Length: " + nf.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(nf);
            out.flush();
            s.close();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void pumpLoop() {
        BufferedImage lastSrc = null;
        long lastSentAt = 0;
        long lastStatusAt = 0;
        while (running) {
            try {
                Thread.sleep(100);
                if (clients.isEmpty()) continue;
                long now = System.currentTimeMillis();
                BufferedImage img = frames.get();
                if (img == null) {
                    // 还没进入投屏：给浏览器一句说明，避免一直黑屏
                    if (now - lastStatusAt > 2000) {
                        lastStatusAt = now;
                        for (Client c : clients) {
                            c.sendText("{\"type\":\"status\",\"msg\":\"还没有画面：先在 sun 里点「进入投屏」\"}");
                        }
                    }
                    continue;
                }
                // 同一张缓冲也要定期重发（静止画面时帧对象不变，浏览器会停在旧画面）
                if (img == lastSrc && now - lastSentAt < 800) continue;
                lastSrc = img;
                byte[] jpg = encode(img);
                if (jpg != null && jpg.length > 0) {
                    lastJpeg = jpg;
                    lastSentAt = now;
                    for (Client c : clients) c.sendBinary(jpg);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                lastError = String.valueOf(t);
            }
        }
    }

    private byte[] encode(BufferedImage src) {
        try {
            int sw = src.getWidth(), sh = src.getHeight();
            int maxW = 1000;
            double k = sw > maxW ? (double) maxW / sw : 1.0;
            int w = Math.max(1, (int) Math.round(sw * k));
            int h = Math.max(1, (int) Math.round(sh * k));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // ★ 必须用 MemoryCacheImageOutputStream：默认的 FileCache 会往 java.io.tmpdir 写缓存，
            //   在某些环境（沙箱/无权限 TEMP）直接抛 "Can't create cache file!"，一帧都发不出去。
            ImageOutputStream ios = new javax.imageio.stream.MemoryCacheImageOutputStream(bos);
            wr.setOutput(ios);
            ImageWriteParam p = wr.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.55f);
            wr.write(null, new IIOImage(out, null, null), p);
            ios.close();
            wr.dispose();
            lastW = w; lastH = h;
            return bos.toByteArray();
        } catch (Throwable t) {
            lastError = "encode: " + t;
            return null;
        }
    }

    private final class Client {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        volatile boolean open = true;

        Client(Socket s, InputStream in, OutputStream out) {
            this.socket = s; this.in = in; this.out = out;
        }

        void close() {
            open = false;
            try { socket.close(); } catch (Throwable ignored) { }
            clients.remove(this);
        }

        synchronized void sendBinary(byte[] data) {
            if (!open) return;
            try { writeFrame(out, 0x2, data); } catch (Throwable t) { close(); }
        }

        synchronized void sendText(String s) {
            if (!open) return;
            try { writeFrame(out, 0x1, s.getBytes(StandardCharsets.UTF_8)); } catch (Throwable t) { close(); }
        }

        void readLoop() {
            try {
                sendText("{\"type\":\"hello\",\"w\":" + lastW + ",\"h\":" + lastH + "}");
                while (open) {
                    int b0 = in.read();
                    if (b0 == -1) break;
                    int b1 = in.read();
                    if (b1 == -1) break;
                    int opcode = b0 & 0x0F;
                    int len = b1 & 0x7F;
                    if (len == 126) {
                        len = (in.read() << 8) | in.read();
                    } else if (len == 127) {
                        long l = 0;
                        for (int i = 0; i < 8; i++) l = (l << 8) | in.read();
                        len = (int) l;
                    }
                    boolean masked = (b1 & 0x80) != 0;
                    byte[] mask = new byte[4];
                    if (masked) {
                        for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
                    }
                    byte[] payload = new byte[Math.max(0, len)];
                    int off = 0;
                    while (off < len) {
                        int n = in.read(payload, off, len - off);
                        if (n <= 0) break;
                        off += n;
                    }
                    if (masked) for (int i = 0; i < len; i++) payload[i] ^= mask[i % 4];
                    if (opcode == 0x8) break;                 // close
                    if (opcode == 0x9) { writeFrame(out, 0xA, payload); continue; }
                    if (opcode == 0x1) handleCommand(new String(payload, StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            } finally {
                close();
            }
        }
    }

    /** 浏览器发来的极简命令：tap x y / swipe x1 y1 x2 y2 / wheel x y up|down / key name（坐标 0..1） */
    private void handleCommand(String s) {
        try {
            String[] p = s.trim().split("\\s+");
            if (p.length < 2) return;
            BufferedImage cur = frames.get();
            int fw = cur != null ? cur.getWidth() : lastW;
            int fh = cur != null ? cur.getHeight() : lastH;
            if (fw <= 0 || fh <= 0) { fw = 1000; fh = 2000; }
            switch (p[0]) {
                case "tap": {
                    int x = (int) (Double.parseDouble(p[1]) * fw);
                    int y = (int) (Double.parseDouble(p[2]) * fh);
                    cmds.onTap(x, y);
                    break;
                }
                case "swipe": {
                    int x1 = (int) (Double.parseDouble(p[1]) * fw);
                    int y1 = (int) (Double.parseDouble(p[2]) * fh);
                    int x2 = (int) (Double.parseDouble(p[3]) * fw);
                    int y2 = (int) (Double.parseDouble(p[4]) * fh);
                    cmds.onSwipe(x1, y1, x2, y2);
                    break;
                }
                case "wheel": {
                    int x = (int) (Double.parseDouble(p[1]) * fw);
                    int y = (int) (Double.parseDouble(p[2]) * fh);
                    cmds.onWheel(x, y, "up".equals(p[3]));
                    break;
                }
                case "key":
                    cmds.onKey(p[1]);
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) { }
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] data) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x80 | opcode);
        int len = data.length;
        if (len < 126) {
            b.write(len);
        } else if (len < 65536) {
            b.write(126);
            b.write((len >> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(127);
            long l = len;
            for (int i = 7; i >= 0; i--) b.write((int) ((l >> (8 * i)) & 0xFF));
        }
        b.write(data, 0, len);
        synchronized (out) {
            out.write(b.toByteArray());
            out.flush();
        }
    }

    private static final String PAGE =
            "<!doctype html><html lang=zh><meta charset=utf-8><title>sun 投屏</title>"
            + "<style>body{margin:0;background:#111;color:#ddd;font-family:sans-serif}"
            + "header{padding:8px 12px;background:#1b1e24;display:flex;gap:8px;align-items:center;font-size:13px}"
            + "#c{display:block;margin:0 auto;background:#000;max-width:100vw}"
            + "#hint{position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);color:#9aa4b2;font-size:15px;pointer-events:none}"
            + "button{background:#2a2f3a;color:#ddd;border:1px solid #3a4152;border-radius:6px;padding:4px 10px}"
            + "</style>"
            + "<header><b>sun 投屏</b><span id=s>连接中…</span>"
            + "<button onclick=\"k('back')\">返回</button><button onclick=\"k('home')\">桌面</button>"
            + "<button onclick=\"k('recents')\">多任务</button><button onclick=\"k('power')\">电源</button></header>"
            + "<canvas id=c></canvas><div id=hint>等待画面…</div>"
            + "<script>"
            + "const c=document.getElementById('c'),x=c.getContext('2d'),s=document.getElementById('s'),hint=document.getElementById('hint');"
            + "let ws,down=null,moved=0;"
            + "function conn(){ws=new WebSocket((location.protocol==='https:'?'wss://':'ws://')+location.host+'/ws');"
            + "ws.binaryType='blob';"
            + "ws.onopen=()=>s.textContent='已连接';"
            + "ws.onclose=()=>{s.textContent='断开，重连中…';setTimeout(conn,1500)};"
            + "ws.onmessage=e=>{if(typeof e.data==='string'){try{const m=JSON.parse(e.data);if(m.type==='status')hint.textContent=m.msg}catch(_){}return;}"
            + "createImageBitmap(e.data).then(b=>{hint.textContent='';c.width=b.width;c.height=b.height;x.drawImage(b,0,0)})};}"
            + "function n(e){const r=c.getBoundingClientRect();return[(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]}"
            + "function send(t){if(ws&&ws.readyState===1)ws.send(t)}"
            + "c.onpointerdown=e=>{down=n(e);moved=0;c.setPointerCapture(e.pointerId)};"
            + "c.onpointerup=e=>{if(!down)return;const p=n(e);"
            + "const dx=Math.abs(p[0]-down[0])*c.width,dy=Math.abs(p[1]-down[1])*c.height;"
            + "if(dx<8&&dy<8)send('tap '+down[0]+' '+down[1]);else send('swipe '+down[0]+' '+down[1]+' '+p[0]+' '+p[1]);down=null};"
            + "c.onwheel=e=>{e.preventDefault();const p=n(e);send('wheel '+p[0]+' '+p[1]+' '+(e.deltaY<0?'up':'down'))};"
            + "function k(n){send('key '+n)}"
            + "conn();"
            + "</script></html>";
}
