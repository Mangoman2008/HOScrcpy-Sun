package utils;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVCodecParserContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.HashMap;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

/**
 * H.264 解码器（bytedeco FFmpeg）。
 * 逻辑来自原 HOScrcpy 的 decodeH264，抽出成独立类便于维护与复用。
 *
 * 用法：
 *   FfmpegDecoder d = new FfmpegDecoder();
 *   d.init();
 *   BufferedImage img = d.decode(h264Bytes);   // 可能返回 null（还没出完整帧）
 *   d.close();
 */
public class FfmpegDecoder {

    private static final String TAG = "FfmpegDecoder";

    private AVCodecContext codecCtx;
    static {
        try {
            // 屏蔽 FFmpeg 原生层的 [h264] error 之类刷屏日志（不影响解码，解码器会自行掩盖）
            // 想看：-Dsun.ffmpeglog=true
            if (!"true".equalsIgnoreCase(System.getProperty("sun.ffmpeglog"))) {
                org.bytedeco.ffmpeg.global.avutil.av_log_set_level(
                        org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET);
            }
        } catch (Throwable ignored) { }
    }

    private AVCodecParserContext parser;
    private int parserDiag = 0;
    private int parserFrames = 0;
    private volatile boolean parserBroken = false;
    private AVFrame frame;
    private AVFrame frameRGB;
    private SwsContext swsContext;
    private AVPacket packet;
    private BufferedImage img;

    private final HashMap<String, BufferedImage> imgMap = new HashMap<>();
    private boolean hadBuffer = false;
    private boolean hadSetSws = false;
    private int errCount = 0;
    private long rotationStartMs = 0;
    private int pendingW = 0, pendingH = 0, pendingCount = 0;   // 待确认的新尺寸（连续 3 帧才认）
    // ── 硬件解码（D3D11VA）──
    private boolean hwActive = false;
    private boolean hwSelfChecked = false;
    /** 运行时开关：null=按 -Dsun.hw 决定；true/false=强制 */
    public static volatile Boolean hwForceOn = null;
    private AVBufferRef hwDeviceRef = null;
    private AVFrame hwFrame = null;
    private boolean hadSizeChange = false;
    private int diagCount = 0;   // 前若干包打印内部细节用于排错

    private boolean hevc = false;

    public void init() {
        initWithCodec(AV_CODEC_ID_H264, false);
    }

    /** 直接以 H.265 初始化（用于接收 HarmonyOS-Sender 的 HEVC 推流） */
    public void initAsHevc() {
        initWithCodec(AV_CODEC_ID_HEVC, true);
    }

    /** 切到 H.265 解码器（数据真是 HEVC 时用） */
    public void switchToHevc() {
        Log.info(TAG, "switch decoder to HEVC");
        close();
        initWithCodec(AV_CODEC_ID_HEVC, true);
    }

    /** 当前是否在用硬件解码 */
    public boolean isHwActive() { return hwActive; }

    public boolean isHevc() {
        return hevc;
    }

    private void initWithCodec(int codecId, boolean isHevc) {
        hevc = isHevc;
        AVCodec codec = avcodec_find_decoder(codecId);
        if (codec == null) {
            Log.info(TAG, "codec not found");
            return;
        }
        codecCtx = avcodec_alloc_context3(codec);
        if (codecCtx == null) {
            Log.info(TAG, "could not alloc codec context");
            return;
        }
        parser = av_parser_init(codecId);
        // 默认软解（硬解在这台机器上会闪退）；需要时加 -Dsun.hw=true 打开
        // 默认软解；想测硬解：顶栏 ⚡ 或 -Dsun.hw=true
        // （注意：本机 AMD RX550 + Intel UHD630 + Oray 虚拟屏三驱动共存，硬解可能蓝屏）
        boolean hwWanted = hwForceOn != null
                ? hwForceOn.booleanValue()
                : "true".equalsIgnoreCase(System.getProperty("sun.hw"));
        // H.264 与 H.265 都可硬解（实测 D3D11VA 在本机稳定）。
        // 想只对 H.265 硬解：-Dsun.hwavc=false
        boolean allowAvcHw = !"false".equalsIgnoreCase(System.getProperty("sun.hwavc"));
        hwActive = hwWanted && (isHevc || allowAvcHw) && initHwDecode();
        Log.info(TAG, "hw decision: wanted=" + hwWanted + " isHevc=" + isHevc + " active=" + hwActive);
        // 注意：不要随便给解码器加 LOW_DELAY/多线程/flags2，
        // 实测在 bytedeco 绑定下会触发 avcodec_receive_frame 内的崩溃（EXCEPTION_ACCESS_VIOLATION）
        avcodec_open2(codecCtx, codec, (PointerPointer<Pointer>) null);
        frame = av_frame_alloc();
        frameRGB = av_frame_alloc();
        packet = av_packet_alloc();
        av_new_packet(packet, 3000);
        Log.info(TAG, "decoder ready codec=" + (isHevc ? "HEVC/H.265" : "H264"));
    }

    /**
     * 喂一段 H.264 数据，返回解码出的画面；还没出帧时返回 null。
     *
     * 这里**不使用 av_parser_parse2** —— 实测它在 Java 侧永远返回 0（一个包都攒不出来），
     * 而 FFmpeg 的 H.264 解码器本身能识别 Annex-B 起始码，所以直接把整段数据塞进
     * AVPacket 交给解码器即可。
     */
    /**
     * 按原版 HOScrcpy（MainForm.decodeH264）的思路：用 av_parser_parse2 把字节流
     * 解析成完整访问单元再喂解码器。AVC 的裸流必须这样组帧，否则首包只有 SPS 片段，
     * 解码器会一直报 INVALIDDATA、解不出帧。
     */
    /** 从解码器取出所有可用帧，转成 BufferedImage（取最后一个） */
    private BufferedImage drainFrames() {
        if (frame == null) return null;
        BufferedImage out = null;
        try {
            while (true) {
                int recvRet = avcodec_receive_frame(codecCtx, frame);
                if (recvRet != 0) break;
                AVFrame useFrame = frame;
                if (hwActive && frame.format() == AV_PIX_FMT_D3D11) {
                    if (hwFrame == null) hwFrame = av_frame_alloc();
                    if (av_hwframe_transfer_data(hwFrame, frame, 0) < 0) continue;
                    useFrame = hwFrame;
                }
                int fw = useFrame.width() > 0 ? useFrame.width() : codecCtx.width();
                int fh = useFrame.height() > 0 ? useFrame.height() : codecCtx.height();
                if (!hadBuffer) {
                    if (frameRGB == null) frameRGB = av_frame_alloc();
                    frameRGB.format(AV_PIX_FMT_BGR24);
                    frameRGB.width(fw);
                    frameRGB.height(fh);
                    av_image_alloc(frameRGB.data(), frameRGB.linesize(), fw, fh, frameRGB.format(), 1);
                    hadBuffer = true;
                    hadSetSws = false;
                }
                buildSwsContext(useFrame.format());
                sws_scale(swsContext, useFrame.data(), useFrame.linesize(), 0,
                        useFrame.height(), frameRGB.data(), frameRGB.linesize());
                if (useFrame == hwFrame) av_frame_unref(hwFrame);
                BufferedImage target = targetImage();
                if (target != null) {
                    java.awt.image.DataBufferByte buffer =
                            (java.awt.image.DataBufferByte) target.getRaster().getDataBuffer();
                    frameRGB.data(0).get(buffer.getData());
                    out = target;
                }
            }
        } catch (Throwable t) {
            Log.info(TAG, "drainFrames failed: " + t);
        }
        return out;
    }

    private BufferedImage decodeWithParser(byte[] data) {
        if (parser == null || codecCtx == null || packet == null) return null;
        BufferedImage out = null;
        try {
            BytePointer bp = new BytePointer(data.length);
            bp.put(data, 0, data.length);
            IntPointer outSize = new IntPointer(1);
            PointerPointer<BytePointer> outBuf = new PointerPointer<>(1);
            int offset = 0;
            while (offset < data.length) {
                outSize.put(0);
                int consumed = av_parser_parse2(parser, codecCtx, outBuf, outSize,
                        bp.position(offset), data.length - offset,
                        AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
                BytePointer outPtr = outBuf.get(BytePointer.class, 0);
                if (consumed < 0) break;
                offset += consumed;
                int auSize = outSize.get();
                if (auSize <= 0 || outPtr == null || outPtr.isNull()) {
                    if (consumed == 0) break;   // 攒不够，等下一段数据
                    continue;
                }
                if (parserDiag < 3) {
                    parserDiag++;
                    Log.info(TAG, "parser au#" + parserDiag + " size=" + auSize);
                }
                packet.data(outPtr);
                packet.size(auSize);
                if (avcodec_send_packet(codecCtx, packet) < 0) continue;
                BufferedImage got = drainFrames();
                if (got != null) out = got;
            }
            if (parserDiag >= 3 && parserFrames == 0 && offset >= data.length) {
                // 解析器始终不出帧 → 退回直喂模式
                if (parserDiag == 3) {
                    parserDiag = 4;
                    Log.info(TAG, "parser yields nothing, fallback to direct feeding");
                }
                parserBroken = true;
            }
        } catch (Throwable t) {
            Log.info(TAG, "decodeWithParser failed: " + t);
        }
        return out;
    }

    /** 最近一次 decode() 的耗时（毫秒），HUD/日志用来区分"解码慢"还是"链路抖" */
    private volatile double lastDecodeMs = 0;
    /** 解码耗时指数滑动平均（α=0.15，约等于最近 7 次的有效值） */
    private volatile double decodeMsEwma = 0;

    public double getDecodeMs() { return decodeMsEwma; }

    public double getLastDecodeMs() { return lastDecodeMs; }

    public synchronized BufferedImage decode(byte[] h264) {
        long t0 = System.nanoTime();
        try {
            return decodeInner(h264);
        } finally {
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            lastDecodeMs = ms;
            decodeMsEwma = decodeMsEwma <= 0 ? ms : decodeMsEwma * 0.85 + ms * 0.15;
        }
    }

    private synchronized BufferedImage decodeInner(byte[] h264) {
        if (codecCtx == null || packet == null || h264 == null || h264.length == 0) {
            return null;
        }
        // AVC(H.264)：优先用 av_parser 组帧（原版验证过的路径）
        boolean parserOn = "true".equalsIgnoreCase(System.getProperty("sun.parser"));
        if (!hevc && parserOn && !parserBroken) {
            BufferedImage parsed = decodeWithParser(h264);
            if (parsed != null) {
                parserFrames++;
                return parsed;
            }
        }
        try {
            int len = h264.length;
            av_new_packet(packet, len);
            BytePointer bp = packet.data();
            if (bp == null) {
                return null;
            }
            bp.put(h264, 0, len);
            packet.size(len);

            int sendRet = avcodec_send_packet(codecCtx, packet);
            if (diagCount < 3) {
                diagCount++;
                Log.info(TAG, "direct#" + diagCount + " in=" + len + " send=" + sendRet
                        + " ctx=" + codecCtx.width() + "x" + codecCtx.height());
            }
            if (sendRet < 0) {
                return null;
            }

            BufferedImage out = null;
            while (true) {
                int recvRet = avcodec_receive_frame(codecCtx, frame);
                if (recvRet != 0) {
                    break;
                }
                // 硬解帧在显存里：先拷回系统内存再转 BGR24
                AVFrame useFrame = frame;
                if (hwActive && frame.format() == AV_PIX_FMT_D3D11) {
                    if (hwFrame == null) hwFrame = av_frame_alloc();
                    int tr = av_hwframe_transfer_data(hwFrame, frame, 0);
                    if (tr < 0) {
                        if (diagCount < 5) { diagCount++; Log.info(TAG, "hw transfer failed ret=" + tr); }
                        continue;
                    }
                    useFrame = hwFrame;
                }
                int fw = useFrame.width() > 0 ? useFrame.width() : codecCtx.width();
                int fh = frame.height() > 0 ? frame.height() : codecCtx.height();
                // 分辨率变化（横竖屏切换）：重建 frameRGB 与缩放上下文，否则画面会被拉伸/错乱
                if (hadBuffer && (frameRGB.width() != fw || frameRGB.height() != fh)) {
                    // 连续 3 帧同一尺寸才认（防止偶发一帧尺寸抖动导致来回重建）
                    if (fw == pendingW && fh == pendingH) {
                        pendingCount++;
                    } else {
                        pendingW = fw; pendingH = fh; pendingCount = 1;
                    }
                    if (pendingCount < 2) return null;
                    pendingCount = 0;
                    rotationStartMs = System.currentTimeMillis();
                    Log.info(TAG, "resolution changed " + frameRGB.width() + "x" + frameRGB.height()
                            + " -> " + fw + "x" + fh + " (realloc buffers only)");
                    // 只重建"输出缓冲 + 缩放上下文"，不动解码器本身
                    // （之前整体 close()+init 会留下悬空指针/尺寸错配，导致横屏第一帧后黑屏 ✗）
                    try {
                        if (swsContext != null) { sws_freeContext(swsContext); swsContext = null; }
                        if (frameRGB != null) {
                            try { av_freep(frameRGB.data()); } catch (Throwable ignored) { }
                            frameRGB = null;
                        }
                        hadBuffer = false;
                        hadSetSws = false;
                        // 精确回退到 v0.39.1（12:40 实测能旋转）的做法：
                        // 只重分配输出缓冲 + 缩放上下文，不 flush、不动 parser ✓
                    } catch (Throwable e) {
                        Log.info(TAG, "realloc on size change failed: " + e);
                    }
                    return null;   // 下一帧按新尺寸分配缓冲
                }
                if (!hadBuffer) {
                    if (frameRGB == null) frameRGB = av_frame_alloc();   // ★ 尺寸变化时可能已被释放
                    frameRGB.format(AV_PIX_FMT_BGR24);
                    frameRGB.width(fw);
                    frameRGB.height(fh);
                    av_image_alloc(frameRGB.data(), frameRGB.linesize(),
                            frameRGB.width(), frameRGB.height(), frameRGB.format(), 1);
                    hadBuffer = true;
                    hadSetSws = false;
                }
                buildSwsContext(useFrame.format());
                sws_scale(swsContext, useFrame.data(), useFrame.linesize(), 0, useFrame.height(),
                        frameRGB.data(), frameRGB.linesize());
                if (useFrame == hwFrame) {
                    av_frame_unref(hwFrame);      // 释放这块拷回的帧，避免泄漏
                }
                BufferedImage target = targetImage();
                if (target != null) {
                    DataBufferByte buffer = (DataBufferByte) target.getRaster().getDataBuffer();
                    frameRGB.data(0).get(buffer.getData());
                    img = target;
                    out = target;
                }
            }
            return out;
        } catch (Exception ex) {
            Log.info(TAG, "decode error: " + ex);
            return null;
        }
    }

    /** 尝试启用 D3D11VA 硬解；任何一步失败都自动退回软解 */
    private boolean initHwDecode() {
        try {
            PointerPointer<AVBufferRef> dev = new PointerPointer<>(1);
            int ret = av_hwdevice_ctx_create(dev, AV_HWDEVICE_TYPE_D3D11VA,
                    null, null, 0);
            if (ret < 0) {
                Log.info(TAG, "hw: no d3d11va device (ret=" + ret + "), software decode");
                return false;
            }
            hwDeviceRef = dev.get(AVBufferRef.class);
            codecCtx.hw_device_ctx(av_buffer_ref(hwDeviceRef));
            codecCtx.get_format(new AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                @Override
                public int call(AVCodecContext ctx, IntPointer fmt) {
                    for (int i = 0; fmt.get(i) != AV_PIX_FMT_NONE; i++) {
                        if (fmt.get(i) == AV_PIX_FMT_D3D11) {
                            return AV_PIX_FMT_D3D11;
                        }
                    }
                    return AV_PIX_FMT_NONE;
                }
            });
            hwFrame = av_frame_alloc();
            Log.info(TAG, "hw: D3D11VA decode enabled");
            return true;
        } catch (Throwable t) {
            Log.info(TAG, "hw init failed, software decode: " + t);
            return false;
        }
    }

    private int lastSrcFmt = -1;

    private void buildSwsContext(int srcFmt) {
        if (hadSetSws && !hadSizeChange && srcFmt == lastSrcFmt) {
            return;
        }
        if (hadSetSws && srcFmt != lastSrcFmt && swsContext != null) {
            sws_freeContext(swsContext);
            swsContext = null;
        }
        lastSrcFmt = srcFmt;
        if (hadSizeChange && swsContext != null) {
            sws_freeContext(swsContext);
        }
        swsContext = sws_getCachedContext(null,
                frameRGB.width(), frameRGB.height(), srcFmt,
                frameRGB.width(), frameRGB.height(), frameRGB.format(),
                SWS_FAST_BILINEAR, null, null, (DoublePointer) null);
        hadSetSws = true;
    }

    private BufferedImage targetImage() {
        if (frameRGB.width() <= 0 || frameRGB.height() <= 0) {
            return null;
        }
        if (img != null && (img.getWidth() != frameRGB.width() || img.getHeight() != frameRGB.height())) {
            img = null;   // 尺寸变了，换新的
        }
        if (img == null) {
            String key = frameRGB.width() + ";" + frameRGB.height();
            if (!imgMap.containsKey(key)) {
                imgMap.put(key, new BufferedImage(frameRGB.width(), frameRGB.height(),
                        BufferedImage.TYPE_3BYTE_BGR));
            }
            img = imgMap.get(key);
        }
        if (hwActive && !hwSelfChecked) {
            hwSelfChecked = true;
            Log.info(TAG, "hw decode self-check OK (已解出 " + frameRGB.width() + "x" + frameRGB.height() + ")");
        }
        if (rotationStartMs > 0) {
            Log.info(TAG, "rotation recovered in " + (System.currentTimeMillis() - rotationStartMs) + " ms");
            rotationStartMs = 0;
        }
        return img;
    }

    /** 只释放老版验证过可用的那几个 API；其余交给进程退出回收，
     *  避免用到 bytedeco 里"指针的指针"签名导致编译失败。 */
    public void close() {
        try {
            if (swsContext != null) {
                sws_freeContext(swsContext);
                swsContext = null;
            }
            if (frame != null) {
                av_frame_free(frame);
                frame = null;
            }
            if (frameRGB != null) {
                try {
                    av_freep(frameRGB.data());
                } catch (Throwable ignored) {
                }
                frameRGB = null;
            }
            packet = null;
            codecCtx = null;
            parser = null;
            img = null;
            imgMap.clear();
            hadBuffer = false;
            hadSetSws = false;
            hadSizeChange = false;
            Log.info(TAG, "decoder closed");
        } catch (Throwable ex) {
            Log.info(TAG, "close error: " + ex);
        }
    }
}