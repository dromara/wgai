package org.jeecg.modules.demo.video.util;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;

/** Keep decoder reference frames intact; sample only after decoding. */
public final class VideoDecodePolicy {
    private static final java.util.concurrent.Semaphore STARTUP = new java.util.concurrent.Semaphore(2, true);
    private VideoDecodePolicy() { }

    public static void start(FFmpegFrameGrabber grabber) throws Exception {
        boolean acquired = false;
        try {
            STARTUP.acquire();
            acquired = true;
            apply(grabber);
            grabber.start();
        } catch (Exception e) {
            try { grabber.release(); } catch (Exception releaseError) { e.addSuppressed(releaseError); }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (acquired) STARTUP.release();
        }
    }

    public static void apply(FFmpegFrameGrabber grabber) {
        // These are codec/CLI options, not input-format options. Do not silently pass them to avformat.
        for (String key : new String[]{"flags", "flags2", "err_detect", "skip_frame", "framedrop", "r", "threads", "loglevel"}) {
            grabber.getOptions().remove(key);
        }
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("fflags", "discardcorrupt");
        grabber.setOption("buffer_size", "1048576");
        grabber.setOption("stimeout", "10000000");
        grabber.setOption("rw_timeout", "10000000");
        grabber.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        grabber.setFrameRate(0); // Source timestamps/fps must remain intact.
        grabber.setVideoOption("threads", "1");
        grabber.setVideoOption("err_detect", "crccheck+bitstream+buffer+explode");
        grabber.setVideoOption("flags", "-output_corrupt");
        grabber.setVideoOption("flags2", "-fast");
        grabber.setVideoOption("skip_frame", "default");
    }
}
