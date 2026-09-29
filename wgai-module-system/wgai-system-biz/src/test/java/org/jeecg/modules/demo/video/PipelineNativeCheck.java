package org.jeecg.modules.demo.video;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.jeecg.modules.demo.video.util.FrameMatView;
import org.jeecg.modules.demo.video.util.StreamLagTracker;
import org.jeecg.modules.demo.video.util.identifyTypeNewOnnxTest;
import org.opencv.core.*;
import java.awt.image.DataBufferByte;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Native regression and a local conversion microbenchmark; not a live multi-camera throughput test. */
public class PipelineNativeCheck {
    public static void main(String[] args) throws Exception {
        nu.pattern.OpenCV.loadLocally();
        checkStrideAndOwnership();
        checkPreprocessing();
        checkDnnParsing();
        checkStreamLag();
        benchmarkConversion();
        System.out.println("PASS: zero-copy BGR stride/ownership, RGB CHW normalization equivalence, DNN output equivalence, PTS lag/reset");
    }

    private static void checkStrideAndOwnership() {
        try (Frame frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 3)) {
            ByteBuffer bytes = (ByteBuffer) frame.image[0];
            bytes.put(0, (byte) 10); bytes.put(1, (byte) 20); bytes.put(2, (byte) 30);
            bytes.put(frame.imageStride, (byte) 40);
            Mat view = FrameMatView.bgr(frame);
            try {
                check(view.get(0, 0)[0] == 10 && view.get(0, 0)[2] == 30, "BGR channel order");
                check(view.get(1, 0)[0] == 40, "row padding respected");
                view.put(0, 0, new byte[]{7, 8, 9});
                check(bytes.get(0) == 7, "view shares original memory");
            } finally { view.release(); }
            check(bytes.get(0) == 7, "view release preserves Frame ownership");
        }
    }

    private static void checkPreprocessing() throws Exception {
        Mat rgb = new Mat(640, 640, CvType.CV_8UC3);
        byte[] pixels = new byte[640 * 640 * 3];
        new java.util.Random(42).nextBytes(pixels);
        rgb.put(0, 0, pixels);
        Mat normalized = new Mat();
        List<Mat> channels = new ArrayList<>();
        try {
            rgb.convertTo(normalized, CvType.CV_32F, 1.0 / 255.0);
            Core.split(normalized, channels);
            float[] expected = new float[pixels.length];
            for (int c = 0; c < 3; c++) {
                float[] plane = new float[640 * 640]; channels.get(c).get(0, 0, plane);
                System.arraycopy(plane, 0, expected, c * plane.length, plane.length);
            }
            Method preprocess = identifyTypeNewOnnxTest.class.getDeclaredMethod("preprocessImage", Mat.class);
            preprocess.setAccessible(true);
            float[] actual = (float[]) preprocess.invoke(new identifyTypeNewOnnxTest(), rgb);
            check(actual.length == expected.length, "tensor size");
            for (int i = 0; i < actual.length; i++) {
                if (Math.abs(actual[i] - expected[i]) > 0.000001f) throw new AssertionError("preprocess mismatch at " + i);
            }
        } finally {
            rgb.release(); normalized.release(); channels.forEach(Mat::release);
        }
    }

    private static void checkDnnParsing() throws Exception {
        Method parser = org.jeecg.modules.demo.video.util.identifyTypeNew.class.getDeclaredMethod("appendPredictions",
                Mat.class, boolean.class, int.class, float.class, List.class, List.class, List.class);
        parser.setAccessible(true);
        for (boolean objectness : new boolean[]{false, true}) {
            int rows = 128, classes = 4, firstScore = objectness ? 5 : 4, cols = firstScore + classes;
            Mat predictions = new Mat(rows, cols, CvType.CV_32F);
            float[] input = new float[rows * cols]; java.util.Random random = new java.util.Random(19);
            for (int i = 0; i < input.length; i++) input[i] = random.nextFloat();
            predictions.put(0, 0, input);
            List<Integer> ids = new ArrayList<>(), expectedIds = new ArrayList<>();
            List<Float> confidence = new ArrayList<>(), expectedConfidence = new ArrayList<>();
            List<Rect2d> boxes = new ArrayList<>(), expectedBoxes = new ArrayList<>();
            try {
                for (int i = 0; i < rows; i++) {
                    Mat row = predictions.row(i), scores = row.colRange(firstScore, cols);
                    try {
                        Core.MinMaxLocResult max = Core.minMaxLoc(scores);
                        float score = (float) max.maxVal;
                        if (objectness) score *= (float) row.get(0, 4)[0];
                        if (score > 0.4f) {
                            expectedIds.add((int) max.maxLoc.x); expectedConfidence.add(score);
                            float x = (float) row.get(0, 0)[0], y = (float) row.get(0, 1)[0];
                            float w = (float) row.get(0, 2)[0], h = (float) row.get(0, 3)[0];
                            expectedBoxes.add(new Rect2d(x - w / 2, y - h / 2, w, h));
                        }
                    } finally { scores.release(); row.release(); }
                }
                parser.invoke(null, predictions, objectness, classes, 0.4f, ids, confidence, boxes);
                check(ids.equals(expectedIds), "DNN class order");
                check(confidence.equals(expectedConfidence), "DNN confidence equivalence");
                check(boxes.equals(expectedBoxes), "DNN coordinate equivalence");
            } finally { predictions.release(); }
        }
    }

    private static void checkStreamLag() {
        StreamLagTracker tracker = new StreamLagTracker();
        check(tracker.observe(1000000, 1000000000L) == 0, "initial lag");
        check(tracker.observe(2000000, 2000000000L) == 0, "live stream");
        check(tracker.observe(2100000, 6000000000L) == 3900, "accumulated lag");
        check(tracker.observe(500000, 7000000000L) == 0, "PTS reset");
        tracker.reset();
        check(tracker.observe(9000000, 9000000000L) == 0, "reconnect resets baseline");
    }

    private static void benchmarkConversion() {
        int iterations = 100;
        try (Frame frame = new Frame(1920, 1080, Frame.DEPTH_UBYTE, 3);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            // Warm up native/JIT and converter allocation.
            for (int i = 0; i < 10; i++) { converter.getBufferedImage(frame); FrameMatView.bgr(frame).release(); }
            long begin = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                byte[] pixels = ((DataBufferByte) converter.getBufferedImage(frame).getRaster().getDataBuffer()).getData();
                Mat mat = new Mat(1080, 1920, CvType.CV_8UC3), perModel = new Mat();
                try { mat.put(0, 0, pixels); mat.copyTo(perModel); }
                finally { mat.release(); perModel.release(); }
            }
            long oldNanos = System.nanoTime() - begin;
            begin = System.nanoTime();
            for (int i = 0; i < iterations; i++) { FrameMatView.bgr(frame).release(); }
            long newNanos = System.nanoTime() - begin;
            System.out.printf(java.util.Locale.ROOT, "1080p conversion only: old=%.3f ms/frame, new=%.3f ms/frame (%d iterations)%n",
                    oldNanos / 1e6 / iterations, newNanos / 1e6 / iterations, iterations);
        }
    }

    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
