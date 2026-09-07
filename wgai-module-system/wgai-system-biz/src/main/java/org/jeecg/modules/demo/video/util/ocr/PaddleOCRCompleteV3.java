package org.jeecg.modules.demo.video.util.ocr;

import ai.onnxruntime.*;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.*;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * PaddleOCR完整实现 - 支持中文、英文、越南语
 * 包含完整的DBNet后处理和CTC解码
 */
@Slf4j
public class PaddleOCRCompleteV3 {

    private OrtEnvironment env;
    private OrtSession detSession;
    private OrtSession recSessionCh;
    private OrtSession recSessionVi;

    // 字符集
    private List<String> charsCh;
    private List<String> charsVi;

    // 模型文件路径
    private static final String DET_MODEL = "F:\\JAVAAI\\OCR\\Paddle\\ch_ppocr_det.onnx";
    private static final String REC_MODEL_CH = "F:\\JAVAAI\\OCR\\Paddle\\ch_ppocr_rec.onnx";
    private static final String REC_MODEL_LATIN = "F:\\JAVAAI\\OCR\\Paddle\\latin_ppocr_rec.onnx";

    // 字符集文件路径（可选）
    private static final String DICT_CH = "F:\\JAVAAI\\OCR\\Paddle\\ppocr_keys_v1.txt";
    private static final String DICT_LATIN = "F:\\JAVAAI\\OCR\\Paddle\\latin_dict.txt";



    public PaddleOCRCompleteV3() throws OrtException {
        this(DET_MODEL, REC_MODEL_CH, REC_MODEL_LATIN, DICT_CH, DICT_LATIN);
    }

    /**
     * 使用业务表中配置的 PaddleOCR 模型。传入中文识别模型及其字典即可。
     */
    public PaddleOCRCompleteV3(String detModelPath, String recModelChPath, String dictChPath) throws OrtException {
        this(detModelPath, recModelChPath, null, dictChPath, null);
    }

    private PaddleOCRCompleteV3(String detModelPath, String recModelChPath, String recModelViPath,
                                String dictChPath, String dictViPath) throws OrtException {
        env = OrtEnvironment.getEnvironment();

        // 加载模型
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        detSession = env.createSession(detModelPath, opts);
        recSessionCh = env.createSession(recModelChPath, opts);
        if (recModelViPath != null) {
            recSessionVi = env.createSession(recModelViPath, opts);
        }

        // 加载字符集
        charsCh = loadCharacterDict(dictChPath);
        charsVi = dictViPath == null ? charsCh : loadCharacterDict(dictViPath);

        log.info("PaddleOCR模型加载成功，检测模型={}，中文识别模型={}，中文字符集={}个",
                detModelPath, recModelChPath, charsCh.size());
    }

    /**
     * 加载字符字典
     */
    private List<String> loadCharacterDict(String dictPath) {
        List<String> characters = new ArrayList<>();

        // 添加blank字符
        characters.add("blank");

        try {
            File file = new File(dictPath);
            if (file.exists()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)
                );

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        characters.add(line);
                    }
                }
                reader.close();
                log.info("PaddleOCR字符集加载成功：{}，共{}个字符", dictPath, characters.size() - 1);
            } else {
                log.warn("PaddleOCR字符集文件不存在：{}，使用默认字符集", dictPath);
                characters.addAll(getDefaultCharacters());
            }
        } catch (Exception e) {
            log.warn("PaddleOCR字符集加载失败，使用默认字符集：{}", dictPath, e);
            characters.addAll(getDefaultCharacters());
        }

        return characters;
    }

    /**
     * 默认字符集（数字+字母）
     */
    private List<String> getDefaultCharacters() {
        List<String> chars = new ArrayList<>();

        // 数字
        for (char c = '0'; c <= '9'; c++) {
            chars.add(String.valueOf(c));
        }

        // 大写字母
        for (char c = 'A'; c <= 'Z'; c++) {
            chars.add(String.valueOf(c));
        }

        // 小写字母
        for (char c = 'a'; c <= 'z'; c++) {
            chars.add(String.valueOf(c));
        }

        return chars;
    }

    /**
     * 完整OCR识别
     */
    public List<OCRResult> recognize(String imagePath, String lang) throws Exception {
        List<OCRResult> results = new ArrayList<>();
        Mat img = null;
        try {
            log.info("PaddleOCR开始识别，图片={}，语言={}", imagePath, lang);
            img = Imgcodecs.imread(imagePath);
            if (img.empty()) {
                throw new RuntimeException("无法读取图片: " + imagePath);
            }

            log.info("PaddleOCR图片尺寸={}x{}", img.width(), img.height());

            // 1. 文本检测
            List<TextBox> boxes = detectText(img);
            log.info("PaddleOCR检测到{}个文本区域", boxes.size());

            // 2. 文本识别
            for (int i = 0; i < boxes.size(); i++) {
                TextBox box = boxes.get(i);

                // 裁剪文本区域
                Mat cropped = cropTextRegion(img, box.points);
                try {
                    if (cropped.empty() || cropped.width() <= 3 || cropped.height() <= 3) {
                        continue;
                    }

                    log.info("PaddleOCR文本块{}，检测框={}，裁剪尺寸={}x{}",
                            i + 1, formatBox(box.points), cropped.width(), cropped.height());
                    String text = recognizeText(cropped, lang);
                    OCRResult result = new OCRResult();
                    result.text = text;
                    result.box = box.points;
                    result.score = box.score;
                    results.add(result);
                    log.info("PaddleOCR文本块{}，检测置信度={}，识别文本={}",
                            i + 1, String.format("%.2f%%", box.score * 100), text);
                } finally {
                    cropped.release();
                }
            }
            return results;
        } finally {
            if (img != null) {
                img.release();
            }
        }
    }

    /**
     * 文本检测
     */
    private List<TextBox> detectText(Mat img) throws OrtException {
        // 预处理
        Mat resized = preprocessDetection(img);
        try {
            float[] inputData = matToFloatArray(resized);
            long[] shape = {1, 3, resized.height(), resized.width()};
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
                 OrtSession.Result result = detSession.run(Collections.singletonMap(
                         detSession.getInputNames().iterator().next(), tensor))) {
                return postprocessDetection(result, img.size(), resized.size());
            }
        } finally {
            resized.release();
        }
    }

    /**
     * 文本识别
     */
    private String recognizeText(Mat img, String lang) throws OrtException {
        boolean useVietnameseModel = lang.equals("vi") && recSessionVi != null;
        OrtSession session = useVietnameseModel ? recSessionVi : recSessionCh;
        List<String> characters = useVietnameseModel ? charsVi : charsCh;

        // 预处理
        long[] modelInputShape = getModelInputShape(session);
        int targetHeight = getPositiveDimension(modelInputShape, 2, 48);
        int fixedWidth = getPositiveDimension(modelInputShape, 3, -1);
        Mat resized = preprocessRecognition(img, targetHeight, fixedWidth);
        try {
            if (resized.width() <= 1 || resized.height() <= 1) {
                log.warn("PaddleOCR无效文本区域，跳过识别");
                return "";
            }
            float[] inputData = matToFloatArray(resized);
            long[] shape = {1, 3, targetHeight, resized.width()};
            log.info("PaddleOCR识别模型输入声明={}，实际输入={}",
                    Arrays.toString(modelInputShape), Arrays.toString(shape));
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
                 OrtSession.Result result = session.run(Collections.singletonMap(
                         session.getInputNames().iterator().next(), tensor))) {
                return ctcDecode(result, characters);
            }
        } finally {
            resized.release();
        }
    }

    /**
     * 检测预处理
     */
    private Mat preprocessDetection(Mat img) {
        Mat processed = new Mat();

        int maxSize = 960;
        double ratio = Math.min(maxSize / (double) img.width(), maxSize / (double) img.height());

        int newWidth = ((int) (img.width() * ratio) / 32) * 32;
        int newHeight = ((int) (img.height() * ratio) / 32) * 32;

        if (newWidth < 32) newWidth = 32;
        if (newHeight < 32) newHeight = 32;

        Imgproc.resize(img, processed, new Size(newWidth, newHeight));
        processed.convertTo(processed, CvType.CV_32F, 1.0 / 255.0);
        // 与 PaddleOCR DB 检测模型的 NormalizeImage 保持一致。
        Core.subtract(processed, new Scalar(0.485, 0.456, 0.406), processed);
        Core.divide(processed, new Scalar(0.229, 0.224, 0.225), processed);

        return processed;
    }

    /**
     * 识别预处理
     */
    private Mat preprocessRecognition(Mat img, int targetHeight, int fixedWidth) {
        Mat processed = new Mat();

        int targetWidth = (int) Math.ceil(img.width() * (targetHeight / (double) img.height()));
        // PaddleOCR 中文 CTC 识别的标准推理输入为 [1, 3, H, 320]。
        // 即使 ONNX 的宽度是动态维度，也按官方逻辑补零到320，避免不同宽度影响结果。
        int maxWidth = fixedWidth > 0 ? fixedWidth : 320;
        if (targetWidth > maxWidth) targetWidth = maxWidth;
        if (targetWidth < 16) targetWidth = 16;

        Imgproc.resize(img, processed, new Size(targetWidth, targetHeight));
        processed.convertTo(processed, CvType.CV_32F, 1.0 / 255.0);

        Core.subtract(processed, new Scalar(0.5, 0.5, 0.5), processed);
        Core.divide(processed, new Scalar(0.5, 0.5, 0.5), processed);

        if (targetWidth < maxWidth) {
            Mat padded = new Mat(targetHeight, maxWidth, CvType.CV_32FC3, new Scalar(0.0, 0.0, 0.0));
            Mat targetArea = padded.submat(0, targetHeight, 0, targetWidth);
            try {
                processed.copyTo(targetArea);
            } finally {
                targetArea.release();
                processed.release();
            }
            return padded;
        }
        return processed;
    }

    private long[] getModelInputShape(OrtSession session) throws OrtException {
        NodeInfo inputInfo = session.getInputInfo().values().iterator().next();
        if (!(inputInfo.getInfo() instanceof TensorInfo)) {
            throw new IllegalStateException("PaddleOCR识别模型输入不是Tensor：" + inputInfo.getInfo());
        }
        return ((TensorInfo) inputInfo.getInfo()).getShape();
    }

    private int getPositiveDimension(long[] shape, int index, int defaultValue) {
        return shape.length > index && shape[index] > 0 && shape[index] <= Integer.MAX_VALUE
                ? (int) shape[index] : defaultValue;
    }



    /**
     * Mat转float数组（CHW格式）
     */
    private float[] matToFloatArray(Mat mat) {
        int channels = mat.channels();
        int height = mat.height();
        int width = mat.width();
        float[] data = new float[channels * height * width];

        for (int c = 0; c < channels; c++) {
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < width; w++) {
                    double[] pixel = mat.get(h, w);
                    data[c * height * width + h * width + w] = (float) pixel[c];
                }
            }
        }

        return data;
    }

    /**
     * DBNet后处理 - 完整实现
     */
    private List<TextBox> postprocessDetection(OrtSession.Result result, Size originalSize, Size modelSize) {
        List<TextBox> textBoxes = new ArrayList<>();

        try {
            // 获取输出: [1, 1, H, W]
            float[][][][] output = (float[][][][]) result.get(0).getValue();

            int height = output[0][0].length;
            int width = output[0][0][0].length;

            // 创建概率图
            Mat predMap = new Mat(height, width, CvType.CV_32F);
            for (int h = 0; h < height; h++) {
                for (int w = 0; w < width; w++) {
                    predMap.put(h, w, output[0][0][h][w]);
                }
            }

            // 二值化
            Mat binary = new Mat();
            double thresh = 0.3;
            Core.compare(predMap, new Scalar(thresh), binary, Core.CMP_GT);
            binary.convertTo(binary, CvType.CV_8U);

            // 查找轮廓
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(binary, contours, hierarchy,
                    Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);

            // 处理轮廓
            double scaleX = originalSize.width / modelSize.width;
            double scaleY = originalSize.height / modelSize.height;

            for (MatOfPoint contour : contours) {
                double area = Imgproc.contourArea(contour);
                if (area < 9) continue;

                // 获取最小外接矩形
                RotatedRect rect = Imgproc.minAreaRect(new MatOfPoint2f(contour.toArray()));
                Point[] vertices = new Point[4];
                rect.points(vertices);

                // 计算置信度
                double score = calculateBoxScore(predMap, contour);
                if (score < 0.6) continue;

                // 转换坐标
                float[][] box = new float[4][2];
                for (int i = 0; i < 4; i++) {
                    box[i][0] = (float) (vertices[i].x * scaleX);
                    box[i][1] = (float) (vertices[i].y * scaleY);
                }

                TextBox textBox = new TextBox();
                textBox.points = box;
                textBox.score = score;
                textBoxes.add(textBox);
            }

            predMap.release();
            binary.release();
            hierarchy.release();

        } catch (Exception e) {
            log.error("PaddleOCR检测输出解析失败", e);
            throw new IllegalStateException("PaddleOCR检测输出解析失败", e);
        }

        return textBoxes;
    }

    /**
     * 计算文本框置信度
     */
    private double calculateBoxScore(Mat predMap, MatOfPoint contour) {
        try {
            Mat mask = Mat.zeros(predMap.size(), CvType.CV_8U);
            List<MatOfPoint> contours = Arrays.asList(contour);
            Imgproc.fillPoly(mask, contours, new Scalar(255));

            Scalar meanScalar = Core.mean(predMap, mask);
            mask.release();

            return meanScalar.val[0];
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * CTC解码 - 完整实现
     */
    private String ctcDecode(OrtSession.Result result, List<String> characters) {
        try {
            // 获取输出: [batch, time_steps, num_classes]
            float[][][] output = (float[][][]) result.get(0).getValue();

            int timeSteps = output[0].length;
            int numClasses = output[0][0].length;
            // PaddleOCR 中文模型常用 CTCLabelDecode(use_space_char=true)：
            // blank + ppocr_keys_v1.txt + 空格，共 6625 类。
            if (numClasses == characters.size() + 1) {
                characters.add(" ");
                log.info("PaddleOCR识别模型比字符字典多1个类别，已自动补充空格标签；当前字符字典数={}",
                        characters.size());
            }
            if (numClasses != characters.size()) {
                throw new IllegalStateException("PaddleOCR识别模型类别数(" + numClasses
                        + ")与字符字典数(" + characters.size() + ")不一致；"
                        + "请确认 ai_name_name 的识别ONNX 与 ai_config 的字典来自同一套PaddleOCR模型");
            }

            // 找到每个时间步的最大概率索引
            List<Integer> indices = new ArrayList<>();
            for (int t = 0; t < timeSteps; t++) {
                int maxIndex = 0;
                float maxProb = output[0][t][0];

                for (int c = 1; c < numClasses; c++) {
                    if (output[0][t][c] > maxProb) {
                        maxProb = output[0][t][c];
                        maxIndex = c;
                    }
                }
                indices.add(maxIndex);
            }

            // CTC解码: 去除连续重复 + 去除blank(0)
            StringBuilder text = new StringBuilder();
            int prevIndex = -1;

            for (int index : indices) {
                if (index != prevIndex && index != 0) {
                    if (index < characters.size()) {
                        text.append(characters.get(index));
                    }
                }
                prevIndex = index;
            }

            return text.toString();

        } catch (Exception e) {
            log.error("PaddleOCR CTC文本解码失败", e);
            throw new IllegalStateException("PaddleOCR CTC文本解码失败", e);
        }
    }

    /**
     * 裁剪文本区域
     */
    private Mat cropTextRegion(Mat img, float[][] box) {
        try {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

            for (float[] point : box) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }

            // DB 检测框通常只覆盖笔画的内核区域。直接按框裁剪会截断上下笔画，
            // 例如 UI 截图中的白字会被裁成一条窄带，导致识别结果严重偏差。
            float boxWidth = maxX - minX;
            float boxHeight = maxY - minY;
            int paddingX = Math.max(2, (int) Math.ceil(boxWidth * 0.10D));
            int paddingY = Math.max(2, (int) Math.ceil(boxHeight * 1.30D));

            int x = Math.max(0, (int) Math.floor(minX - paddingX));
            int y = Math.max(0, (int) Math.floor(minY - paddingY));
            int right = Math.min(img.width(), (int) Math.ceil(maxX + paddingX));
            int bottom = Math.min(img.height(), (int) Math.ceil(maxY + paddingY));
            int width = right - x;
            int height = bottom - y;

            if (width <= 0 || height <= 0) {
                return new Mat();
            }

            Rect roi = new Rect(x, y, width, height);
            return new Mat(img, roi);

        } catch (Exception e) {
            log.error("PaddleOCR文本区域裁剪失败，检测框={}", formatBox(box), e);
            return new Mat();
        }
    }

    private String formatBox(float[][] box) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < box.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(String.format("(%.1f,%.1f)", box[i][0], box[i][1]));
        }
        return text.append(']').toString();
    }

    /**
     * 释放每次识别创建的 ONNX Session，避免接口多次调用后堆外内存持续增长。
     * OrtEnvironment 是全局共享对象，不能在此关闭。
     */
    public void close() {
        try {
            if (detSession != null) detSession.close();
            if (recSessionCh != null) recSessionCh.close();
            if (recSessionVi != null) recSessionVi.close();
        } catch (OrtException e) {
            throw new IllegalStateException("关闭PaddleOCR模型失败", e);
        }
    }

    /**
     * 可视化检测结果（调试用）
     */
    public void visualizeDetection(String imagePath, String outputPath) {
        try {
            Mat img = Imgcodecs.imread(imagePath);
            if (img.empty()) return;

            // 检测文本框
            List<TextBox> boxes = detectText(img);

            // 绘制文本框
            for (TextBox box : boxes) {
                Point[] points = new Point[4];
                for (int i = 0; i < 4; i++) {
                    points[i] = new Point(box.points[i][0], box.points[i][1]);
                }

                for (int i = 0; i < 4; i++) {
                    Imgproc.line(img, points[i], points[(i + 1) % 4],
                            new Scalar(0, 255, 0), 2);
                }

                // 显示置信度
                String scoreText = String.format("%.2f", box.score);
                Imgproc.putText(img, scoreText, points[0],
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                        new Scalar(0, 0, 255), 2);
            }

            // 保存结果
            Imgcodecs.imwrite(outputPath, img);
            System.out.println("✅ 可视化结果已保存: " + outputPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 文本框类
     */
    static class TextBox {
        float[][] points;
        double score;
    }

    /**
     * OCR结果类
     */
    public static class OCRResult {
        public String text;
        public float[][] box;
        public double score;

        @Override
        public String toString() {
            return String.format("Text: %s, Score: %.2f%%", text, score * 100);
        }
    }

    public static void main(String[] args) {
        try {
            PaddleOCRCompleteV3 ocr = new PaddleOCRCompleteV3();

            // 测试越南语识别
            System.out.println("\n=== 越南语OCR测试 ===");
            List<OCRResult> results = ocr.recognize("F:\\JAVAAI\\test_image.jpg", "vi");



            // 测试中文识别
 //            System.out.println("\n=== 中文OCR测试 ===");
//             List<OCRResult> results = ocr.recognize("F:\\JAVAAI\\ca6779ded8e8f77e714607e263a8e41.png", "ch");
//            System.out.println("\n最终结果:");
            for (int i = 0; i < results.size(); i++) {
                System.out.println((i + 1) + ". " + results.get(i));
            }
            ocr.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
