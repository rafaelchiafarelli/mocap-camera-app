package dev.mocapstudio.camera;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Hardware H.264 via MediaCodec with an input Surface: the camera renders straight into the encoder. */
final class H264Encoder {
    private static final String TAG = "MocapCamera";

    private final int width, height, fps, bitrate;
    private final StreamServer server;
    private final Stats stats;

    private MediaCodec codec;
    private Surface input;
    private Thread drainThread;
    private volatile boolean running;
    private long seq;

    H264Encoder(int width, int height, int fps, int bitrate, StreamServer server, Stats stats) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitrate = bitrate;
        this.server = server;
        this.stats = stats;
    }

    /** Returns the Surface the camera should render into. */
    Surface start() throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        input = codec.createInputSurface();
        codec.start();
        Log.i(TAG, "encoder " + codec.getName());

        running = true;
        drainThread = new Thread(this::drain, "h264-drain");
        drainThread.start();
        return input;
    }

    String name() {
        return codec == null ? "?" : codec.getName();
    }

    void requestKeyFrame() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "sync frame request failed", e);
        }
    }

    void stop() {
        running = false;
        try { if (drainThread != null) drainThread.join(500); } catch (InterruptedException ignored) { }
        try { codec.stop(); } catch (Exception ignored) { }
        try { codec.release(); } catch (Exception ignored) { }
        if (input != null) input.release();
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (running) {
            int idx;
            try {
                idx = codec.dequeueOutputBuffer(info, 10_000);
            } catch (IllegalStateException e) {
                if (running) stats.error = "encoder: " + e.getMessage();
                return;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Some codecs only report SPS/PPS here instead of as a CODEC_CONFIG buffer.
                MediaFormat f = codec.getOutputFormat();
                ByteBuffer sps = f.getByteBuffer("csd-0"), pps = f.getByteBuffer("csd-1");
                if (sps != null && pps != null && !server.hasCodecConfig()) {
                    byte[] cfg = new byte[sps.remaining() + pps.remaining()];
                    sps.get(cfg, 0, sps.remaining());
                    pps.get(cfg, cfg.length - pps.remaining(), pps.remaining());
                    server.setCodecConfig(cfg);
                }
                continue;
            }
            if (idx < 0) continue;

            ByteBuffer buf = codec.getOutputBuffer(idx);
            byte[] data = new byte[info.size];
            buf.position(info.offset);
            buf.get(data, 0, info.size);
            int flags = info.flags;
            long ptsNs = info.presentationTimeUs * 1000;
            codec.releaseOutputBuffer(idx, false);

            if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                server.setCodecConfig(data);
                continue;
            }
            // pts is the camera sensor timestamp, so this is capture→encoded latency.
            long latency = SensorClock.now() - ptsNs;
            if (latency > 0 && latency < 2_000_000_000L) stats.encodeNanosTotal.addAndGet(latency);
            byte[] sei = TimestampSei.build(++seq, ptsNs, ptsNs + SensorClock.unixOffsetNs());
            byte[] au = new byte[sei.length + data.length];
            System.arraycopy(sei, 0, au, 0, sei.length);
            System.arraycopy(data, 0, au, sei.length, data.length);
            data = au;
            stats.jpegBytesTotal.addAndGet(data.length);
            stats.encodedFrames.incrementAndGet();
            server.publish(data, ptsNs, (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
        }
    }
}
