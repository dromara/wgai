package org.jeecg.modules.demo.video;

import org.bytedeco.javacv.*;
import org.jeecg.modules.demo.video.util.VideoDecodePolicy;
import org.jeecg.modules.demo.video.util.VideoFrameGate;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;

/** Eight local H.264 readers; verifies decoded pixels and clone ownership without contacting cameras. */
public class VideoDecodeCheck {
    public static void main(String[] args) throws Exception {
        checkGate();
        checkOptions();
        Path file = Paths.get("target/subscription-check/decode-fixture.mp4");
        Files.createDirectories(file.getParent());
        writeFixture(file);
        List<Integer> expected = readClip(file, "reference");
        check(expected.size() == 36, "all generated frames decoded");
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<Integer>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                final String id = "camera-" + i;
                results.add(workers.submit(() -> readClip(file, id)));
            }
            for (Future<List<Integer>> result : results) {
                check(expected.equals(result.get(30, TimeUnit.SECONDS)), "concurrent decoding pixels differ");
            }
        } finally { workers.shutdownNow(); }
        System.out.println("PASS: keyframe gate/reset, decoder option placement, 8 concurrent H.264 readers, owned-clone pixels stable");
    }

    private static void checkGate() {
        VideoFrameGate gate = new VideoFrameGate("gate-test");
        try (Frame frame = new Frame(16, 16, Frame.DEPTH_UBYTE, 3)) {
            check(!gate.accept(frame), "reject initial non-keyframe");
            frame.keyFrame = true; check(gate.accept(frame), "accept first keyframe");
            frame.keyFrame = false; check(gate.accept(frame), "accept subsequent inter-frame");
            gate.reset(); check(!gate.accept(frame), "reconnect requires another keyframe");
        }
    }

    private static void checkOptions() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("unused")) {
            grabber.setOption("flags2", "fast"); grabber.setOption("err_detect", "compliant");
            grabber.setOption("fflags", "nobuffer+flush_packets+discardcorrupt");
            VideoDecodePolicy.apply(grabber);
            check(!grabber.getOptions().containsKey("err_detect"), "codec option must not be in demux options");
            check(grabber.getVideoOptions().get("err_detect").contains("explode"), "decoder checks enabled");
            check("discardcorrupt".equals(grabber.getOptions().get("fflags")), "retain decoder startup buffering");
            check("tcp".equals(grabber.getOptions().get("rtsp_transport")), "RTSP uses TCP");
        }
    }

    private static void writeFixture(Path path) throws Exception {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toString(), 160, 96);
             Frame frame = new Frame(160, 96, Frame.DEPTH_UBYTE, 3)) {
            recorder.setFormat("mp4"); recorder.setVideoCodec(AV_CODEC_ID_H264);
            recorder.setFrameRate(12); recorder.setGopSize(12); recorder.setMaxBFrames(0);
            recorder.setVideoOption("threads", "1");
            recorder.start();
            ByteBuffer buffer = (ByteBuffer) frame.image[0];
            for (int f = 0; f < 36; f++) {
                for (int y = 0; y < 96; y++) for (int x = 0; x < 160; x++) {
                    int offset = y * frame.imageStride + x * 3;
                    buffer.put(offset, (byte) (x + f));
                    buffer.put(offset + 1, (byte) (y + f * 2));
                    buffer.put(offset + 2, (byte) (f * 5));
                }
                recorder.record(frame);
            }
            recorder.stop();
        }
    }

    private static List<Integer> readClip(Path path, String id) throws Exception {
        List<Integer> hashes = new ArrayList<>();
        List<Frame> snapshots = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path.toString())) {
            VideoDecodePolicy.start(grabber);
            VideoFrameGate gate = new VideoFrameGate(id);
            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                if (!gate.accept(frame)) continue;
                hashes.add(hash(frame));
                snapshots.add(frame.clone());
            }
            // Decode further frames first, then read snapshots: catches accidentally shared image memory.
            for (int i = 0; i < snapshots.size(); i++) check(hash(snapshots.get(i)) == hashes.get(i), "clone changed after next grab");
            return hashes;
        } finally { snapshots.forEach(Frame::close); }
    }

    private static int hash(Frame frame) {
        ByteBuffer bytes = (ByteBuffer) frame.image[0];
        int hash = 1;
        for (int y = 0; y < frame.imageHeight; y++) for (int x = 0; x < frame.imageWidth * 3; x++) {
            hash = 31 * hash + bytes.get(y * frame.imageStride + x);
        }
        return hash;
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
