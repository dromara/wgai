package org.jeecg.modules.tab.AIModel;

import ai.onnxruntime.*;
import com.alibaba.fastjson.JSONObject;
import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import lombok.extern.slf4j.Slf4j;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.face.entity.FaceBox;
import org.jeecg.modules.demo.face.util.GenderAgeResult;
import org.jeecg.modules.demo.tab.entity.PushInfo;

import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.message.websocket.WebSocket;


import org.jeecg.modules.tab.AIModel.V5.VideoReadInfoV5;
import org.jeecg.modules.tab.AIModel.V5.VideoReadV5;
import org.jeecg.modules.tab.AIModel.V5.VideoReadtestV5;
import org.jeecg.modules.tab.AIModel.V5Util.VideoReadInfoV5Util;
import org.jeecg.modules.tab.AIModel.V5Util.VideoReadV5Util;
import org.jeecg.modules.tab.AIModel.V5Util.VideoReadtestV5Util;
import org.jeecg.modules.tab.AIModel.onnx.VideoReadOnnx;
import org.jeecg.modules.tab.AIModel.onnx.VideoReadOnnxPose;
import org.jeecg.modules.tab.AIModel.pose.ActionResult;
import org.jeecg.modules.tab.AIModel.pose.FallDetectionResult;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.util.CharRecognizer;
import org.opencv.core.*;


import org.opencv.core.Point;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;
import org.opencv.objdetect.HOGDescriptor;
import org.opencv.utils.Converters;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.data.redis.core.RedisTemplate;

import java.awt.*;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import static org.jeecg.modules.tab.AIModel.pose.ActionResult.recognizeAction;
import static org.jeecg.modules.tab.AIModel.pose.FallDetectionResult.detectFallOrStand;
import static org.jeecg.modules.tab.controller.AITestController.CommonColors;


/**
 * @author Wang_java
 * @text：
 * @date 2024/3/13 19:29
 */
@Slf4j
public class AIModelYolo3 {

    /**
     * 车牌识别图片
     *
     * @param weight
     * @param picUrl
     * @param saveName
     * @param uploadpath
     * @return
     * @throws Exception
     */
    public String SendPicOpencvCar(String weight, String picUrl, String saveName, String uploadpath) throws Exception {
        Long a = System.currentTimeMillis();
        Tesseract tesseract = new Tesseract();
        // 设置 Tesseract 数据路径（包含 tessdata 文件夹）
        tesseract.setDatapath("F:\\JAVAAI\\tessdata");

        log.info("picUrl地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        // 加载预训练的车牌检测级联分类器
        log.info("weight地址{}", uploadpath + File.separator + weight);
        CascadeClassifier plateDetector = new CascadeClassifier(uploadpath + File.separator + weight);
        // 检测车牌
        MatOfRect plates = new MatOfRect();
        plateDetector.detectMultiScale(image, plates);
        log.info("plates.toArray()地址{}" + plates.toArray());
        // 绘制检测到的车牌
        for (Rect rect : plates.toArray()) {
            // 提取车牌区域
            Mat plate = new Mat(image, rect);

            // 转换为灰度图像
            Imgproc.cvtColor(plate, plate, Imgproc.COLOR_BGR2GRAY);

            // 保存车牌区域以便 OCR 处理
            // 保存结果图像
            String savepath = uploadpath + File.separator + "temp" + File.separator;

            if (StringUtils.isNotBlank(saveName)) {
                savepath += saveName + ".jpg";
            } else {
                saveName = System.currentTimeMillis() + "";
                savepath += saveName + ".jpg";
            }
            log.info("保存路径: " + savepath);
            Imgcodecs.imwrite(savepath, plate);

            try {

                // 识别车牌中的字符
                String result = tesseract.doOCR(new File(savepath));
                log.info("识别内容: " + result);

                // 在原始图像上绘制边界框和识别结果
                Imgproc.rectangle(image, new Point(rect.x, rect.y), new Point(rect.x + rect.width, rect.y + rect.height), new Scalar(0, 255, 0), 2);
                //       Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
                Imgproc.putText(image, result, new Point(rect.x, rect.y - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(0, 255, 0), 2);
            } catch (TesseractException e) {
                e.printStackTrace();
            }
        }


        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }


    /**
     * 车牌识别图片
     *
     * @param weight
     * @param picUrl
     * @param saveName
     * @param uploadpath
     * @return
     * @throws Exception
     */
    public Map<String, Object> SendPicOpencvCarV5(String weight, String picUrl, String saveName, String uploadpath) throws Exception {
        Map<String, Object> map = new HashMap<>();
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
//        List<String> classes = Files.readAllLines(Paths.get(uploadpath+ File.separator +names));
        // 加载YOLOv5模型

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);
        //  net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("图片地址{}", image);

        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);
        log.info("output{}", Arrays.asList(outBlobNames));
        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            map.put("isOk", false);
            return map;
        }


        float confThreshold = 0.3f;
        float nmsThreshold = 0.4f;

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            int index = (int) output.size(0);
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
            System.out.println("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols + " row," + output.row(0) + "index:" + index);
            Mat detectionMat = output.reshape(1, output.size(1));

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                Mat scores = detection.colRange(5, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float confidence = (float) detection.get(0, 4)[0];
                Point classIdPoint = minMaxResult.maxLoc;

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int) classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    //  System.out.println("识别到了");
                }
            }
        }

// 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }
        int c = 0;
        int[] indices_arr = indices.toArray();
        String plateNumber = "";
        String plateColor = "";
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);

            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            double x = box.x;
            double y = box.y;
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double xzb = x * ((double) image.cols() / 640);
            double yzb = y * ((double) image.rows() / 640);
            System.out.println("绘制1" + "x:" + x + "y:" + y + "");
            System.out.println("绘制1" + "width:" + width + "height:" + height + "");
            System.out.println(" image.cols()" + Double.valueOf((double) image.cols() / 640));
            System.out.println(" image.rows()" + Double.valueOf((double) image.rows() / 640));

            Rect rect = new Rect((int) Math.round(xzb), (int) Math.round(yzb), (int) Math.round(width), (int) Math.round(height));
            Mat plaateimage = Imgcodecs.imread(uploadpath + File.separator + picUrl);
            Mat plateMat = new Mat(plaateimage, rect);


            plateNumber += carStr(plateMat, uploadpath) + ";";

            plateColor += carColorStr(plateMat) + ";";

            System.out.println("车牌号码: " + plateNumber);
            System.out.println("车牌颜色: " + plateColor);

            // 在原图上绘制检测结果
            Imgproc.rectangle(image, rect, new Scalar(0, 255, 0), 2);
            Imgproc.putText(image, "plateNumber", new Point(rect.x, rect.y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(0, 255, 0), 2);


            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        map.put("url", saveName + ".jpg");
        map.put("color", plateColor);
        map.put("plate", plateNumber);
        map.put("isOk", true);
        log.info("车牌号码: " + plateNumber);
        log.info("车牌颜色: " + plateColor);
        return map;
    }

    /***
     * 返回车牌识别文字内容
     * @param url
     * @return
     */
    public static String carStr(Mat url, String uploadpath) {
        String savepath = uploadpath + File.separator + "temp" + File.separator;
        savepath += System.currentTimeMillis() + ".jpg";
        Imgcodecs.imwrite(savepath, url);
        InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
        OcrResult ocrResult = engine.runOcr(savepath);
        return ocrResult.getStrRes().trim().replaceAll(" ", "");
    }


    /***
     * 返回图片识别文字内容
     * @param url
     * @return
     */
    public static String imageStr(String url, String path) {

        InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
        OcrResult ocrResult = engine.runOcr(path + File.separator + url);
        return ocrResult.getStrRes().trim().replaceAll(" ", "");
    }

    /***
     * 返回语音识别文字内容
     * @param url
     * @return
     */
    public static String audioStr(String url, String path) {

        InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
        OcrResult ocrResult = engine.runOcr(path + File.separator + url);
        return ocrResult.getStrRes().trim().replaceAll(" ", "");
    }

    /***
     * 返回车牌颜色
     * @param
     * @return
     */
    public static String carColorStr(Mat image) {
        Mat gray = new Mat();
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
        Mat plateRegion = image.clone();
        // 将车牌区域转换为 HSV 颜色空间
        Mat hsv = new Mat();
        Imgproc.cvtColor(plateRegion, hsv, Imgproc.COLOR_BGR2HSV);
        // 定义颜色范围（HSV）
        Scalar lowerBlue = new Scalar(100, 150, 150);
        Scalar upperBlue = new Scalar(140, 255, 255);

        Scalar lowerGreen = new Scalar(35, 100, 100);
        Scalar upperGreen = new Scalar(85, 255, 255);

        Scalar lowerWhite = new Scalar(0, 0, 200);
        Scalar upperWhite = new Scalar(180, 50, 255);

        Scalar lowerYellow = new Scalar(20, 100, 100);
        Scalar upperYellow = new Scalar(30, 255, 255);
        // 创建掩模
        Mat maskBlue = new Mat();
        Core.inRange(hsv, lowerBlue, upperBlue, maskBlue);

        Mat maskGreen = new Mat();
        Core.inRange(hsv, lowerGreen, upperGreen, maskGreen);

        Mat maskWhite = new Mat();
        Core.inRange(hsv, lowerWhite, upperWhite, maskWhite);

        Mat maskYellow = new Mat();
        Core.inRange(hsv, lowerYellow, upperYellow, maskYellow);

        // 计算掩模区域的面积
        double blueArea = Core.countNonZero(maskBlue);
        double greenArea = Core.countNonZero(maskGreen);
        double whiteArea = Core.countNonZero(maskWhite);
        double yellowArea = Core.countNonZero(maskYellow);

        // 判断车牌颜色
        String colorName = getColorName(blueArea, greenArea, whiteArea, yellowArea);
        return colorName;
    }

    /**
     * 车牌识别内容
     *
     * @param picUrl
     * @return
     * @throws Exception
     */
    public static String SendPicOpencvCarStr(String picUrl) throws Exception {


        Mat plate = Imgcodecs.imread(picUrl);

        // 转换为灰度图像
        Imgproc.cvtColor(plate, plate, Imgproc.COLOR_BGR2GRAY);
//        // 二值化处理
        Imgproc.threshold(plate, plate, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
//          //去噪声
//        Imgproc.medianBlur(plate, plate, 3);
//        //. 形态学操作
//        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new org.opencv.core.Size(3, 3));
//        Imgproc.dilate(plate, plate, kernel);
        // 图像增强：调整对比度和亮度
        //    plate.convertTo(plate, -1, 1.2, 30); // 增强对比度和亮度

        // 保存车牌区域以便 OCR 处理
        // 保存结果图像
        String savepath = "F:\\JAVAAI\\tessdata\\";


        String saveName = System.currentTimeMillis() + "";
        savepath += saveName + ".jpg";

        log.info("保存路径: " + savepath);
        Imgcodecs.imwrite(savepath, plate);
        // 识别车牌字符
        String result = "";
        try {
            //  result = carStr(savepath);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        //tesseract.doOCR(new File(picUrl));
        System.out.println("识别结果: " + result);
        return result;
    }

    /**
     * 根据掩模区域的面积判断颜色
     *
     * @param blueArea   蓝色掩模区域的面积
     * @param greenArea  绿色掩模区域的面积
     * @param whiteArea  白色掩模区域的面积
     * @param yellowArea 黄色掩模区域的面积
     * @return 车牌颜色名称
     */
    public static String getColorName(double blueArea, double greenArea, double whiteArea, double yellowArea) {
        double maxArea = Math.max(Math.max(blueArea, greenArea), Math.max(whiteArea, yellowArea));

        if (maxArea == blueArea) {
            return "蓝色";
        } else if (maxArea == greenArea) {
            return "绿色";
        } else if (maxArea == whiteArea) {
            return "白色";
        } else if (maxArea == yellowArea) {
            return "黄色";
        } else {
            return "未知颜色";
        }
    }

    // 锐化图像
    private static void sharpenImage(Mat input, Mat output) {
        Mat kernel = new Mat(3, 3, CvType.CV_32F);
        kernel.put(0, 0, 0, -1, 0);
        kernel.put(1, 0, -1, 5, -1);
        kernel.put(2, 0, 0, -1, 0);
        Imgproc.filter2D(input, output, -1, kernel);
    }

    public static void main(String[] args) throws Exception {
        System.load("F:\\JAVAAI\\opencv481\\opencv\\build\\java\\x64\\opencv_java481.dll");
// 读取图片
        Mat img = Imgcodecs.imread("F:\\logs\\e09875acd34dc8540d67396b890b327.png");

        // 初始化 HOGDescriptor
        HOGDescriptor hog = new HOGDescriptor();
        hog.setSVMDetector(HOGDescriptor.getDefaultPeopleDetector());

        // 检测
        MatOfRect foundLocations = new MatOfRect();
        MatOfDouble foundWeights = new MatOfDouble();
        hog.detectMultiScale(img, foundLocations, foundWeights);

        // 判断是否有人
        Rect[] detections = foundLocations.toArray();
        if (detections.length > 0) {
            System.out.println("✅ 检测到 " + detections.length + " 人");
            // 画框
            for (Rect r : detections) {
                Imgproc.rectangle(img, r, new Scalar(0, 255, 0), 2);
            }
            Imgcodecs.imwrite("F:\\logs\\result.jpg", img);
        } else {
            System.out.println("❌ 没有人");
        }


        // 读取图像
//        Mat src = Imgcodecs.imread("F:\\home\\1740119768303.png");
////
////        String starstr=imageStr("1740110094401.jpg","F:\\home");
////        System.out.println("starstr"+starstr);
//        // 转换为灰度图像
//        // 1. 图像预处理：灰度化、二值化
//        // 1. 图像预处理：灰度化、二值化
//        Mat gray = new Mat();
//        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);
//
//        Mat binary = new Mat();
//        Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
//
//        // 2. 放大图像减少极坐标转换中的锯齿
//        Mat enlargedImage = new Mat();
//        Imgproc.resize(binary, enlargedImage, new Size(binary.cols() * 2, binary.rows() * 2), 0, 0, Imgproc.INTER_CUBIC);
//
//        // 3. 添加字符间的空白（膨胀操作）
//        Mat dilatedImage = new Mat();
//        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, 1));  // 使用一个2x2的矩阵作为膨胀核
//        Imgproc.dilate(enlargedImage, dilatedImage, kernel);
//
//        // 4. 检测轮廓
//        List<MatOfPoint> contours = new ArrayList<>();
//        Mat hierarchy = new Mat();
//        Imgproc.findContours(dilatedImage, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
//
//        // 5. 使用 RotatedRect 检测弧形文字区域
//        for (MatOfPoint contour : contours) {
//            RotatedRect rotatedRect = Imgproc.minAreaRect(new MatOfPoint2f(contour.toArray()));
//
//            // 获取旋转矩形的角点坐标
//            Point[] vertices = new Point[4];
//            rotatedRect.points(vertices);
//
//            // 在原图上绘制矩形
//            for (int j = 0; j < 4; j++) {
//                Imgproc.line(src, vertices[j], vertices[(j + 1) % 4], new Scalar(0, 255, 0), 2);
//            }
//
//            // 6. 极坐标变换矫正弧形文字（使用更高阶的插值方法）
//            Mat polarImg = new Mat();
//            Point center = rotatedRect.center;  // 弧形文字的中心
//            double maxRadius = rotatedRect.size.height / 2.0; // 以高度为半径
//            Imgproc.linearPolar(dilatedImage, polarImg, center, maxRadius, Imgproc.INTER_CUBIC + Imgproc.WARP_FILL_OUTLIERS);
//
//            // 7. 锐化极坐标变换后的图像
//            Mat sharpenKernel = new Mat(3, 3, CvType.CV_32F);
//            float[] kernelData = {
//                    0, -1, 0,
//                    -1, 5, -1,
//                    0, -1, 0
//            };
//            sharpenKernel.put(0, 0, kernelData);
//
//            Mat sharpenedImage = new Mat();
//            Imgproc.filter2D(polarImg, sharpenedImage, -1, sharpenKernel);
//
////            // 8. 细化字体边缘（腐蚀操作）
////            Mat erodedImage = new Mat();
////            Mat erosionKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(1, 1));  // 细化字体边缘
////            Imgproc.erode(sharpenedImage, erodedImage, erosionKernel);
//
//            // 保存锐化和放大后的图像
//            String outputPolarImagePath = "F:\\home\\path_to_output_polar_image.jpg";
//            Imgcodecs.imwrite(outputPolarImagePath, sharpenedImage);
//
//            System.out.println("极坐标变换后的清晰图像已保存: " + outputPolarImagePath);
//
//            // 后续可以将 polarImg 图像传递给 OCR 工具进行识别
//            // 使用如 RapidOCR 等工具来识别展开的直线文字
//        }
//
//        // 保存标注了旋转矩形的原图像
//        String outputImagePath = "F:\\home\\orrected_image.jpg";
//        Imgcodecs.imwrite(outputImagePath, src);
//        // 保存结果
////
//        String endstr=imageStr("path_to_output_polar_image.jpg","F:\\home");
//        System.out.println("endstr"+endstr);


//        SendPicOpencvCarStr("F:\\JAVAAI\\tessdata\\1722241390893.png");

//            InferenceEngine engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V3);
//            OcrResult ocrResult = engine.runOcr("F:\\JAVAAI\\tessdata\\ws.jpg");
//            log.info("当前结果为："+ocrResult.getStrRes().trim().replaceAll(" ",""));
        //   System.out.println( ocrResult.getStrRes().replace("\n", ""));
        // System.load("F:\\JAVAAI\\opencv\\build\\java\\x64\\opencv_java3416.dll");

//        //   SendPicYoloV3("yolov3.weights","yolov3.cfg","coco.names","car.jpg","test","F:\\JAVAAI\\yolo3\\yuanshi");
//            SendPicYoloV5("NBplate.onnx","coco.names","writecat.jpg","","F:\\JAVAAI\\yolov5");
//    //    SendPicYoloV5Car("NBplate.onnx","coco.names","bluecar.jpg","","F:\\JAVAAI\\yolov5");
//        String rtspUrl="rtsp://admin:ch255899@192.168.0.200/Streaming/Channels/102";
//
////        String rtspUrl = "rtsp://[用户名]:[密码]@[IP地址]:[端口]/[码流类型]";
//
//        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl);
//        OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();
//        VideoWriter videoWriter = new VideoWriter();
//        try {
//            grabber.setOption("rtsp_transport", "tcp"); // 使用TCP而不是UDP
//            grabber.start();
//            System.out.println("连接到RTSP流成功");
//            Java2DFrameConverter converter = new Java2DFrameConverter();
//            Frame frame;
//            int a=0;
//
//            FrameRecorder recorder = FrameRecorder.createDefault("F:\\JAVAAI\\model\\test3\\test1.mp4", grabber.getImageWidth(), grabber.getImageHeight());
//            recorder.setVideoCodec(grabber.getVideoCodec());
//            recorder.setFormat("mp4");
//            recorder.start();
//
//            while ((frame = grabber.grab()) != null) {
//
//                // 将Frame转换为JavaCV的Mat
//                int width=frame.imageWidth;
//                int height=frame.imageHeight;
//                System.out.println("成功获取帧宽度"+width+"高度:"+height);
//                Mat opencvMat=bufferedImageToMat(   converter.getBufferedImage(frame));
//                if(a<=200){
//                    // 录制帧 b释放才会保存
//                    Frame processedFrame = converterToFrame(opencvMat);
//                    recorder.record(processedFrame);
////                    Imgcodecs.imwrite("F:\\JAVAAI\\model\\test3\\test"+a+".jpg", opencvMat);
////                    saveVideo( opencvMat, frame, videoWriter,"F:\\JAVAAI\\model\\test3\\test1.mp4");
//
//                    // 将处理后的帧发送给所有连接的客户端
//          //          broadcastFrame(processedFrame);
//                }else {
//                    break;
//                }
//                a++;
//
//
//                // 显示帧（如果您想要显示视频）
//             //   canvasFrame.showImage(frame);
//
//                // 在这里可以对帧进行处理
//                // 例如：保存帧、分析帧内容等
//            }
//
//            grabber.stop();
//            grabber.release();
//
//            recorder.stop();
//            recorder.release();
//       //     canvasFrame.dispose();
//        } catch (Exception e) {
//            System.err.println("发生错误: " + e.getMessage());
//            e.printStackTrace();
//        }


    }

    public static Frame converterToFrame(Mat mat) {
        OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
        return matConverter.convert(mat);
    }

    public static void saveVideo(Mat mat, Frame frame, VideoWriter videoWriter, String outputVideoPath) {
        // 获取视频帧的宽度和高度
        int frameWidth = frame.imageWidth;
        int frameHeight = frame.imageHeight;
        Size frameSize = new Size(frameWidth, frameHeight);


        int fourcc = videoWriter.fourcc('M', 'J', 'P', 'G');
        videoWriter.open(outputVideoPath, fourcc, 30, frameSize, true);
        videoWriter.write(mat);
    }

    private static int max(float[] arr) {
        int maxIdx = 0;
        for (int i = 1; i < arr.length; i++) {
            if (arr[i] > arr[maxIdx]) {
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /***
     * AI模型嵌套模型
     * 需要绝对路径
     * 输入图片
     */
    public static String SendPicYoloV8(String weight, String names, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv8模型

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("图片地址{}", image);

        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);
        System.out.println(Arrays.asList(outBlobNames));
        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }


        float confThreshold = 0.3f;
        float nmsThreshold = 0.4f;

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            int index = (int) output.size(0);
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
            System.out.println("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols + " row," + output.row(0) + "index:" + index);
            Mat detectionMat = output.reshape(1, output.size(1));

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                Mat scores = detection.colRange(5, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float confidence = (float) detection.get(0, 4)[0];
                Point classIdPoint = minMaxResult.maxLoc;

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int) classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    //  System.out.println("识别到了");
                }
            }
        }

// 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }
        int c = 0;
        int[] indices_arr = indices.toArray();
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            double x = box.x;
            double y = box.y;
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double xzb = x * ((double) image.cols() / 640);
            double yzb = y * ((double) image.rows() / 640);
            System.out.println("绘制1" + "x:" + x + "y:" + y + "");
            System.out.println("绘制1" + "width:" + width + "height:" + height + "");
            System.out.println(" image.cols()" + Double.valueOf((double) image.cols() / 640));
            System.out.println(" image.rows()" + Double.valueOf((double) image.rows() / 640));

            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    CommonColors(c), 2);
            String label = classes.get(classId) + ": " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(xzb, yzb - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);
            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }


    /***
     * AI模型嵌套模型
     * 需要绝对路径
     * 输入图片
     */
    public static String SendPicYoloV5(String weight, String names, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv5模型

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("图片地址{}", image);
// 2
        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);
        System.out.println(Arrays.asList(outBlobNames));
        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }


        float confThreshold = 0.45f;
        float nmsThreshold = 0.4f;

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            int index = (int) output.size(0);
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
            System.out.println("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols + " row," + output.row(0) + "index:" + index);
            Mat detectionMat = output.reshape(1, output.size(1));

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                Mat scores = detection.colRange(5, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float confidence = (float) detection.get(0, 4)[0];
                //   log.info("当前的信任阈值{}",Math.round(confidence));
                Point classIdPoint = minMaxResult.maxLoc;

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int) classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    log.info("识别到了");
                }
            }
        }

// 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }
        //// 转换为 MatOfRect2d 和 MatOfFloat
//        MatOfRect2d boxesNMS = new MatOfRect2d();
//        boxesNMS.fromList(boxes);
//
//        MatOfFloat confidencesNMS = new MatOfFloat();
//        confidencesNMS.fromList(confidences.stream().map(Float::valueOf).collect(Collectors.toList()));
//
//// 应用非最大抑制
//        MatOfInt indicesNMS = new MatOfInt();
//        Dnn.NMSBoxes(boxesNMS, confidencesNMS, CONFIDENCE_THRESHOLD, NMS_THRESHOLD, indicesNMS);
// 绘制结果
        //    List<String> classes = Arrays.asList("person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush");
        int c = 0;
        int[] indices_arr = indices.toArray();
        for (int idx : indices_arr) {
            log.info("idx{}-{} i", idx, indices_arr.length);
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            double x = box.x;
            double y = box.y;
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double xzb = x * ((double) image.cols() / 640);
            double yzb = y * ((double) image.rows() / 640);
            System.out.println("绘制1" + "x:" + x + "y:" + y + "");
            System.out.println("绘制1" + "width:" + width + "height:" + height + "");
            System.out.println(image.cols() + " image.cols()" + Double.valueOf((double) image.cols() / 640));
            System.out.println(image.rows() + " image.rows()" + Double.valueOf((double) image.rows() / 640));

            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    CommonColors(c), 2);
            String label = classes.get(classId) + ": " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(xzb, yzb - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);
            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }

    /**
     * yolov11
     *
     * @param weight
     * @param names
     * @param picUrl
     * @param saveName
     * @param uploadpath
     * @return
     * @throws Exception
     */
    public static String SendPicYoloV12(String weight, String names, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv11模型

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("图片地址{}", image);
// 2
        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);
        System.out.println(Arrays.asList(outBlobNames));
        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }


        float confThreshold = 0.3f;
        float nmsThreshold = 0.4f;

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            long dim0 = output.size(0);  // batch size
            long dim1 = output.size(1);  // 通常是84 (4坐标 + 80类别)
            long dim2 = output.size(2);  // 通常是8400 (检测点数量)

            // YOLOv11: Dims: 3, dim1: 84, dim2: 8400
            // YOLOv5:  Dims: 3, dim1: 25200, dim2: 85
            System.out.println("Dims: " + dims + ", dim1: " + dim1 + ", dim2: " + dim2);

            Mat detectionMat;
            int rows, cols;


            // YOLOv11格式 [1, 84, 8400] - 需要转置
            Mat reshaped = output.reshape(1, (int) dim1);  // [84, 8400]
            Mat transposed = new Mat();
            Core.transpose(reshaped, transposed);  // [8400, 84]
            detectionMat = transposed;
            rows = (int) dim2;  // 8400
            cols = (int) dim1;  // 84


            System.out.println("处理后 - Rows: " + rows + ", Cols: " + cols);

            for (int i = 0; i < rows; i++) {
                Mat detection = detectionMat.row(i);

                float confidence;
                Mat scores;
                Point classIdPoint;


                // YOLOv11格式：[x, y, w, h, class0_conf, class1_conf, ...]
                // 前4个是坐标，后面的是各类别置信度
                scores = detection.colRange(4, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                confidence = (float) minMaxResult.maxVal;  // 最大置信度
                classIdPoint = minMaxResult.maxLoc;


                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int) classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    log.info("识别到了");
                }
            }
        }

// 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

// 绘制结果
        int c = 0;
        int[] indices_arr = indices.toArray();
        for (int idx : indices_arr) {
            log.info("idx{}-{} i", idx, indices_arr.length);
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            double x = box.x;
            double y = box.y;
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double xzb = x * ((double) image.cols() / 640);
            double yzb = y * ((double) image.rows() / 640);
            System.out.println("绘制1" + "x:" + x + "y:" + y + "");
            System.out.println("绘制1" + "width:" + width + "height:" + height + "");
            System.out.println(image.cols() + " image.cols()" + Double.valueOf((double) image.cols() / 640));
            System.out.println(image.rows() + " image.rows()" + Double.valueOf((double) image.rows() / 640));

            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    CommonColors(c), 2);
            String label = classes.get(classId) + ": " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(xzb, yzb - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);
            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }


    public static String SendPicYoloV11CVPose(TabAiModel tabAiModel, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.35f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.2f:tabAiModel.getNmsThreshold().floatValue();

        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));

        // 加载YOLOv11 Pose模型
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);

        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        // 使用letterbox预处理
        Mat processedImage = letterboxResize(image, 640, 640);

        // 创建blob
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);

        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }



        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();
        List<float[]> keypoints = new ArrayList<>(); // 存储关键点数据

        for (Mat output : result) {
            int dims = output.dims();
            long dim0 = output.size(0);
            long dim1 = output.size(1);
            long dim2 = output.size(2);

            System.out.println("输出维度: [" + dim0 + ", " + dim1 + ", " + dim2 + "]");

            Mat detectionMat;
            int rows, cols;

            // 判断是否为YOLO11 Pose格式 [1, 56, 8400] (4 + 1 + 51关键点)
            if (dims == 3 && dim1 == 56 && dim2 > 1000) {
                // YOLOv11 Pose格式 [1, 56, 8400]
                System.out.println("检测到YOLOv11 Pose格式");
                Mat reshaped = output.reshape(1, (int) dim1);
                Mat transposed = new Mat();
                Core.transpose(reshaped, transposed);
                detectionMat = transposed;
                rows = (int) dim2;
                cols = (int) dim1;
            } else {
                // 标准格式处理
                System.out.println("检测到标准格式");
                detectionMat = output.reshape(1, (int) output.size(1));
                rows = detectionMat.rows();
                cols = detectionMat.cols();
            }

            System.out.println("处理后矩阵: " + rows + "x" + cols);

            for (int i = 0; i < rows; i++) {
                Mat detection = detectionMat.row(i);

                float confidence;

                if (cols == 56) { // YOLOv11 Pose: [x, y, w, h, conf, 17*3个关键点坐标]
                    // 获取边界框坐标
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];
                    confidence = (float) detection.get(0, 4)[0];

                    if (confidence > confThreshold) {
                        float left = centerX - width / 2;
                        float top = centerY - height / 2;

                        // 提取关键点数据 (17个关键点，每个3个值：x, y, visibility)
                        float[] kpts = new float[51]; // 17 * 3
                        for (int j = 0; j < 51; j++) {
                            kpts[j] = (float) detection.get(0, 5 + j)[0];
                        }

                        classIds.add(0); // 人体检测通常只有一个类别
                        confidences.add(confidence);
                        boxes2d.add(new Rect2d(left, top, width, height));
                        keypoints.add(kpts);

                        log.info("检测到人体: 置信度={}, 坐标=({},{},{},{})",
                                confidence, left, top, width, height);
                    }
                }
            }
        }

        System.out.println("NMS前检测框数量: " + boxes2d.size());
        if( boxes2d.size()<=0){
            return "未检测到";
        }
        // 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indices_arr = indices.toArray();
        System.out.println("NMS后检测框数量: " + indices_arr.length);

        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        // 绘制结果
        int c = 0;
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            float[] kpts = keypoints.get(idx);

            // 还原边界框到原图坐标
            double x = (box.x - dx) / scale;
            double y = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;

            // 确保坐标在图像范围内
            x = Math.max(0, Math.min(x, image.cols() - 1));
            y = Math.max(0, Math.min(y, image.rows() - 1));
            width = Math.min(width, image.cols() - x);
            height = Math.min(height, image.rows() - y);

            // 执行动作识别
//            ActionResult actionResult = recognizeAction(kpts, scale, dx, dy);
//
//            System.out.println(String.format("检测到人体: 置信度=%.3f, 动作=%s (%.2f), 描述=%s",
//                    conf, actionResult.getActionName(), actionResult.getConfidence(), actionResult.getDescription()));
            FallDetectionResult fallResult = detectFallOrStand(kpts, scale, dx, dy);
            System.out.println(String.format("人体检测: 置信度=%.3f, 状态=%s (%.2f), 原因=%s, 报警=%s",
                    conf, fallResult.getStatus(), fallResult.getConfidence(), fallResult.getReason(), fallResult.isAlert()));


            // 绘制边界框
            Imgproc.rectangle(image,
                    new Point(x, y),
                    new Point(x + width, y + height),
                    CommonColors(c), 2);

            String label = "person: " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(x, y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);

            // 绘制关键点和骨骼连接
            drawPoseKeypoints(image, kpts, scale, dx, dy);

            c++;
        }

        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }

        log.info("保存路径: {}", savepath);
        Imgcodecs.imwrite(savepath, image);

        Long b = System.currentTimeMillis();
        log.info("总耗时: {}ms", (b - a));

        return saveName + ".jpg";
    }
    public static String SendPicYoloV11ONNXPose(TabAiModel tabAiModel,String picUrl,
                                                String saveName, String uploadpath, boolean useGpu) throws Exception {
        log.info(uploadpath);
        Long startTime = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.45f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.4f:tabAiModel.getNmsThreshold().floatValue();
        // 1. 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));

        // 2. 读取输入图像并预处理
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        // 使用letterbox预处理
        Mat processedImage = letterboxResize(image, 640, 640);

        // 3. 转换为CHW格式的float数组
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);
        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }

        // 4. 创建 ONNX Runtime 环境
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA();
        } else {
            options.addCPU(true);
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + weight, options)) {
            // 5. 构建输入张量
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);

            // 6. 推理并解析结果


            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();
            List<float[]> keypoints = new ArrayList<>();

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    OnnxValue value = entry.getValue();
                    if (!(value instanceof OnnxTensor)) continue;
                    OnnxTensor tensor = (OnnxTensor) value;

                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    System.out.println("输出维度: [" + tensorShape[0] + ", " + tensorShape[1] + ", " + tensorShape[2] + "]");

                    if (rawOutput instanceof float[][][]) {
                        float[][][] batch = (float[][][]) rawOutput;

                        // 🔍 添加调试信息
                        System.out.println("实际数组维度: [" + batch.length + "]["
                                + batch[0].length + "][" + batch[0][0].length + "]");

                        // ✅ 根据实际维度判断数据格式
                        int dim0 = batch.length;        // 通常是 1
                        int dim1 = batch[0].length;     // 可能是 56 或 8400
                        int dim2 = batch[0][0].length;  // 可能是 8400 或 56
                        int debugCount = 0; // 用于控制调试输出数量

                        if (dim1 == 56 && dim2 > 1000) {
                            System.out.println("✅ 检测到格式: [batch][features][detections]");
                            float[][] detections = batch[0];


                            for (int i = 0; i < dim2; i++) {
                                float centerX = detections[0][i];
                                float centerY = detections[1][i];
                                float width = detections[2][i];
                                float height = detections[3][i];
                                float confidence = detections[4][i];

                                if (confidence > confThreshold) {
                                    float left = centerX - width / 2;
                                    float top = centerY - height / 2;

                                    // 提取关键点数据
                                    float[] kpts = new float[51];
                                    for (int j = 0; j < 51; j++) {
                                        kpts[j] = detections[5 + j][i];
                                    }




                                    // ✅ 验证关键点有效性
                                    int validCoordCount = 0;
                                    int highVisibilityCount = 0;
                                    float minX = Float.MAX_VALUE, maxX = Float.MIN_VALUE;
                                    float minY = Float.MAX_VALUE, maxY = Float.MIN_VALUE;

                                    for (int k = 0; k < 17; k++) {
                                        float kx = kpts[k * 3];
                                        float ky = kpts[k * 3 + 1];
                                        float visibility = kpts[k * 3 + 2];

                                        boolean coordsInRange = (kx >= 0 && kx <= 640 && ky >= 0 && ky <= 640);
                                        boolean notZero = (kx > 0.1 || ky > 0.1);

                                        if (coordsInRange && notZero) {
                                            validCoordCount++;

                                            if (visibility > 0.5) {
                                                if (i == 0) {
                                                    System.out.println("鼻子坐标: (" + kx + "," + ky + ")");
                                                } else if (i == 9 || i == 10) {
                                                    System.out.println("手腕位置: " + (i == 9 ? "左手" : "右手") + " (" + kx + "," + ky + ")");
                                                }
                                                highVisibilityCount++;
                                                // 只统计高可见性的关键点范围
                                                minX = Math.min(minX, kx);
                                                maxX = Math.max(maxX, kx);
                                                minY = Math.min(minY, ky);
                                                maxY = Math.max(maxY, ky);
                                            }
                                        }
                                    }

                                    // ⭐ 计算关键点分布范围
                                    float keypointWidth = (maxX == Float.MIN_VALUE) ? 0 : (maxX - minX);
                                    float keypointHeight = (maxY == Float.MIN_VALUE) ? 0 : (maxY - minY);

                                    // 计算关键点范围与边界框的比例
                                    float widthRatio = (width > 0) ? (keypointWidth / width) : 0;
                                    float heightRatio = (height > 0) ? (keypointHeight / height) : 0;

                                    if (debugCount < 3) {
                                        System.out.println(String.format("  统计: 有效坐标=%d/17, 高可见性=%d/17",
                                                validCoordCount, highVisibilityCount));
                                        System.out.println(String.format("  边界框: 宽=%.1f, 高=%.1f", width, height));
                                        System.out.println(String.format("  关键点范围: 宽=%.1f, 高=%.1f", keypointWidth, keypointHeight));
                                        System.out.println(String.format("  覆盖率: 宽度%.1f%%, 高度%.1f%%", widthRatio * 100, heightRatio * 100));
                                        debugCount++;
                                    }

                                    // ⭐⭐⭐ 关键过滤条件 ⭐⭐⭐
                                    boolean hasValidKeypoints = false;

                                    if (highVisibilityCount >= 3) {

                                        // 策略2: 根据边界框大小动态调整阈值
                                        float minWidth = Math.min(30, width * 0.3f);   // 最小宽度: 30px 或 边界框30%
                                        float minHeight = Math.min(70, height * 0.4f); // 最小高度: 70px 或 边界框40%

                                        boolean hasReasonableSpread = (keypointWidth > minWidth && keypointHeight > minHeight);

                                        // 策略3: 降低覆盖率要求，支持更多姿态
                                        // 宽度覆盖30%，高度覆盖40% 即可认为有效
                                        boolean coversEnoughArea = (widthRatio > 0.3 && heightRatio > 0.4);

                                        // 策略4: 特殊情况处理 - 如果关键点非常集中但置信度高，也认为有效
                                        boolean isHighConfidenceCompact = (confidence > 0.7f && highVisibilityCount >= 5);

                                        // 综合判断
                                        hasValidKeypoints = hasReasonableSpread && (coversEnoughArea || isHighConfidenceCompact);

                                        if (debugCount <= 3) {
                                            System.out.println(String.format("  验证结果: 合理分布=%s, 覆盖充分=%s, 高置信紧凑=%s, 最终=%s",
                                                    hasReasonableSpread, coversEnoughArea, isHighConfidenceCompact, hasValidKeypoints));
                                        }

                                    } else {
                                        // 策略5: 低可见性但高置信度的兜底方案
                                        // 如果置信度很高(>0.75)且至少有2个关键点，也可以尝试保留
                                        if (confidence > 0.6f && validCoordCount >= 2) {
                                            hasValidKeypoints = true;
                                            if (debugCount <= 3) {
                                                System.out.println(String.format("  验证结果: 高置信度兜底通过 (conf=%.2f, valid=%d)",
                                                        confidence, validCoordCount));
                                            }
                                        } else {
                                            if (debugCount <= 3) {
                                                System.out.println(String.format("  验证结果: 高可见性关键点不足(%d<3)", highVisibilityCount));
                                            }
                                        }
                                    }


                                    if (hasValidKeypoints) {
                                        classIds.add(0);
                                        confidences.add(confidence);
                                        boxes2d.add(new Rect2d(left, top, width, height));
                                        keypoints.add(kpts);

                                        log.info("✅ 检测到有效人体: 置信度={}, 坐标=({},{},{},{}), 关键点覆盖={}x{}",
                                                confidence, left, top, width, height,
                                                String.format("%.0f%%", widthRatio * 100),
                                                String.format("%.0f%%", heightRatio * 100));
                                    } else {
                                        if (debugCount <= 3) {
                                            System.out.println(String.format("❌ 过滤掉检测框[%d]: 关键点分布异常", i));
                                        }
                                    }
                                }
                            }
                        } else if (dim1 > 1000 && dim2 == 56) {
                            // 格式: [1][8400][56] - 检测在前
                            System.out.println("⚠️ 检测到格式: [batch][detections][features]");

                            for (int i = 0; i < dim1; i++) {
                                float[] detection = batch[0][i];  // [56]

                                float centerX = detection[0];
                                float centerY = detection[1];
                                float width = detection[2];
                                float height = detection[3];
                                float confidence = detection[4];

                                if (confidence > confThreshold) {
                                    float left = centerX - width / 2;
                                    float top = centerY - height / 2;

                                    // 提取关键点
                                    float[] kpts = new float[51];
                                    System.arraycopy(detection, 5, kpts, 0, 51);

                                    // 🔍 验证关键点有效性
                                    boolean hasValidKeypoints = false;
                                    for (int k = 0; k < 17; k++) {
                                        float kx = kpts[k * 3];
                                        float ky = kpts[k * 3 + 1];
                                        float visibility = kpts[k * 3 + 2];
                                        if (visibility > 0.3 && kx > 0 && ky > 0) {
                                            hasValidKeypoints = true;
                                            break;
                                        }
                                    }

                                    if (hasValidKeypoints) {
                                        classIds.add(0);
                                        confidences.add(confidence);
                                        boxes2d.add(new Rect2d(left, top, width, height));
                                        keypoints.add(kpts);

                                        log.info("检测到人体: 置信度={}, 坐标=({},{},{},{})",
                                                confidence, left, top, width, height);
                                    } else {
                                        System.out.println("⚠️ 跳过无效检测 (置信度=" + confidence + "): 无有效关键点");
                                    }
                                }
                            }
                        } else {
                            System.err.println("❌ 未知的输出格式: [" + dim0 + "][" + dim1 + "][" + dim2 + "]");
                        }
                    }
                }
            }

            System.out.println("NMS前检测框数量: " + boxes2d.size());

            if (boxes2d.size() <= 0) {
                return "未检测到";
            }

            // 7. 应用非极大值抑制
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();

            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArr = indices.toArray();
            System.out.println("NMS后检测框数量: " + indicesArr.length);

            // 8. 计算letterbox的缩放参数
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            // 9. 绘制结果
            int colorIdx = 0;
            for (int idx : indicesArr) {
                Rect2d box = boxes2d.get(idx);
                int classId = classIds.get(idx);
                float conf = confidences.get(idx);
                float[] kpts = keypoints.get(idx);

                // 还原边界框到原图坐标
                double x = (box.x - dx) / scale;
                double y = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                // 确保坐标在图像范围内
                x = Math.max(0, Math.min(x, image.cols() - 1));
                y = Math.max(0, Math.min(y, image.rows() - 1));
                width = Math.min(width, image.cols() - x);
                height = Math.min(height, image.rows() - y);

                // 执行跌倒检测
                FallDetectionResult fallResult = detectFallOrStand(kpts, scale, dx, dy);
                System.out.println(String.format("人体检测: 置信度=%.3f, 状态=%s (%.2f), 原因=%s, 报警=%s",
                        conf, fallResult.getStatus(), fallResult.getConfidence(),
                        fallResult.getReason(), fallResult.isAlert()));

                // 绘制边界框
                Imgproc.rectangle(image,
                        new Point(x, y),
                        new Point(x + width, y + height),
                        CommonColors(colorIdx), 2);

                String label = "person: " + String.format("%.2f", conf);
                Imgproc.putText(image, label, new Point(x, y - 10),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(colorIdx), 2);

                // 绘制关键点和骨骼连接
                drawPoseKeypoints(image, kpts, scale, dx, dy);

                colorIdx++;
            }

            // 10. 保存结果
            String savepath = uploadpath + File.separator + "temp" + File.separator;
            if (StringUtils.isNotBlank(saveName)) {
                savepath += saveName + ".jpg";
            } else {
                saveName = System.currentTimeMillis() + "";
                savepath += saveName + ".jpg";
            }

            log.info("保存路径: {}", savepath);
            Imgcodecs.imwrite(savepath, image);

            Long endTime = System.currentTimeMillis();
            log.info("总耗时: {}ms", (endTime - startTime));

            return saveName + ".jpg";
        }
    }
    // 绘制姿态关键点和骨骼连接
    private static void drawPoseKeypoints(Mat image, float[] keypoints, double scale, double dx, double dy) {
        // COCO 17个关键点的定义
        String[] keypointNames = {
                "nose", "left_eye", "right_eye", "left_ear", "right_ear",
                "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
                "left_wrist", "right_wrist", "left_hip", "right_hip",
                "left_knee", "right_knee", "left_ankle", "right_ankle"
        };

        // 骨骼连接定义 (COCO格式)
        int[][] skeleton = {
                {16, 14}, {14, 12}, {17, 15}, {15, 13}, {12, 13},  // 腿部
                {6, 12}, {7, 13}, {6, 7}, {6, 8}, {7, 9},          // 躯干和手臂
                {8, 10}, {9, 11}, {2, 3}, {1, 2}, {1, 3},          // 手臂和脸部
                {2, 4}, {3, 5}, {4, 6}, {5, 7}                     // 脸部到肩膀
        };

        // 将关键点坐标还原到原图
        Point[] points = new Point[17];
        boolean[] visible = new boolean[17];

        for (int i = 0; i < 17; i++) {
            float kx = keypoints[i * 3];     // x坐标
            float ky = keypoints[i * 3 + 1]; // y坐标
            float kv = keypoints[i * 3 + 2]; // 可见性

            // 还原到原图坐标
            double px = (kx - dx) / scale;
            double py = (ky - dy) / scale;

            // 确保坐标在图像范围内
            px = Math.max(0, Math.min(px, image.cols() - 1));
            py = Math.max(0, Math.min(py, image.rows() - 1));

            points[i] = new Point(px, py);
            visible[i] = kv > 0.5; // 可见性阈值
        }

        // 绘制骨骼连接线
        Scalar lineColor = new Scalar(0, 255, 0); // 绿色
        for (int[] bone : skeleton) {
            int p1_idx = bone[0] - 1; // 转换为0-based索引
            int p2_idx = bone[1] - 1;

            if (p1_idx >= 0 && p1_idx < 17 && p2_idx >= 0 && p2_idx < 17 &&
                    visible[p1_idx] && visible[p2_idx]) {
                Imgproc.line(image, points[p1_idx], points[p2_idx], lineColor, 2);
            }
        }

        // 绘制关键点
        Scalar pointColor = new Scalar(0, 0, 255); // 红色
        for (int i = 0; i < 17; i++) {
            if (visible[i]) {
                Imgproc.circle(image, points[i], 3, pointColor, -1);

                // 绘制关键点标签
                // Imgproc.putText(image, keypointNames[i],
                //     new Point(points[i].x + 5, points[i].y - 5),
                //     Imgproc.FONT_HERSHEY_SIMPLEX, 0.3, pointColor, 1);
            }
        }
    }

    /**
     * YOLOv11 实例分割模型推理 (ONNX Runtime)
     */
    public static String SendPicOnnxYoloV11Seg(TabAiModel tabAiModel, String picUrl,
                                               String saveName, String uploadpath, boolean useGpu) throws Exception {
        long startTime = System.currentTimeMillis();
        String weight = tabAiModel.getAiWeights();
        String names = tabAiModel.getAiNameName();

        float confThreshold = tabAiModel.getThreshold() == null ? 0.3f : tabAiModel.getThreshold().floatValue();
        float nmsThreshold = tabAiModel.getNmsThreshold() == null ? 0.2f : tabAiModel.getNmsThreshold().floatValue();

        // 1. 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();

        // 2. 读取图像并预处理
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        Mat processedImage = letterboxResize(image, 640, 640);
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);

        // HWC -> CHW
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);
        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }

        // 3. 创建 ONNX Runtime 环境
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA();
            log.info("使用ONNX GPU");
        } else {
            log.info("使用ONNX CPU");
            options.setInterOpNumThreads(4);
            options.setIntraOpNumThreads(8);
            options.addCPU(true);
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + weight, options)) {
            // 4. 构建输入
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor);

            // 5. 推理
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();
            List<float[]> maskCoeffsList = new ArrayList<>();

            float[][][][] protoOutput = null;
            int numMasks = 0;

            try (OrtSession.Result results = session.run(inputs)) {
                log.info("模型输出数量: {}", results.size());

                int outputIndex = 0;
                for (Map.Entry<String, OnnxValue> entry : results) {
                    OnnxValue value = entry.getValue();
                    if (!(value instanceof OnnxTensor)) continue;

                    OnnxTensor tensor = (OnnxTensor) value;
                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    log.info("输出 {} - 形状: {}", outputIndex++, Arrays.toString(tensorShape));

                    // ========== 处理4维原型输出 [1, 32, 160, 160] ==========
                    if (tensorShape.length == 4 && rawOutput instanceof float[][][][]) {
                        protoOutput = (float[][][][]) rawOutput;
                        numMasks = (int) tensorShape[1];
                        log.info("识别为Mask原型输出: [{}, {}, {}, {}]",
                                tensorShape[0], tensorShape[1], tensorShape[2], tensorShape[3]);
                        continue;
                    }

                    // ========== 处理3维检测输出 [1, 51, 8400] ==========
                    if (tensorShape.length == 3 && rawOutput instanceof float[][][]) {
                        float[][][] batch = (float[][][]) rawOutput;

                        int numFeatures = (int) tensorShape[1];  // 51
                        int numDetections = (int) tensorShape[2]; // 8400

                        // 计算：51 = 4(bbox) + classes + masks
                        // 如果已知masks数，则: numClasses = 51 - 4 - 32 = 15
                        int numClasses;
                        if (numMasks > 0) {
                            numClasses = numFeatures - 4 - numMasks;
                        } else {
                            // 如果原型输出还未处理，先假设有32个mask
                            numMasks = 32;
                            numClasses = numFeatures - 4 - numMasks;
                        }

                        log.info("检测输出: {} features, {} detections", numFeatures, numDetections);
                        log.info("计算: {} classes, {} masks", numClasses, numMasks);

                        if (numClasses != expectedClassCount) {
                            log.warn("警告: 计算类别数({}) != 期望类别数({})", numClasses, expectedClassCount);
                        }

                        // YOLOv11格式需要转置: [51, 8400] -> 按列遍历
                        for (float[][] detections : batch) {
                            for (int i = 0; i < numDetections; i++) {
                                // 提取bbox
                                float cx = detections[0][i];
                                float cy = detections[1][i];
                                float w = detections[2][i];
                                float h = detections[3][i];

                                // 提取类别分数
                                float maxScore = 0;
                                int classId = 0;
                                for (int c = 0; c < numClasses; c++) {
                                    float score = detections[4 + c][i];
                                    if (score > maxScore) {
                                        maxScore = score;
                                        classId = c;
                                    }
                                }

                                // 过滤并保存检测结果
                                if (maxScore > confThreshold && classId < expectedClassCount) {
                                    boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                    confidences.add(maxScore);
                                    classIds.add(classId);

                                    // 提取mask系数
                                    float[] maskCoeffs = new float[numMasks];
                                    for (int m = 0; m < numMasks; m++) {
                                        maskCoeffs[m] = detections[4 + numClasses + m][i];
                                    }
                                    maskCoeffsList.add(maskCoeffs);
                                }
                            }
                        }
                    }
                }
            }

            log.info("NMS前检测框数量: {}", boxes2d.size());

            // 6. NMS
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();

            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArr = indices.toArray();
            log.info("NMS后检测框数量: {}", indicesArr.length);

            // 7. 计算坐标还原参数
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            // 8. 生成并绘制分割mask
            Mat maskOverlay = Mat.zeros(image.size(), CvType.CV_8UC3);

            int colorIdx = 0;
            for (int idx : indicesArr) {
                Rect2d box = boxes2d.get(idx);
                int clsId = classIds.get(idx);
                float conf = confidences.get(idx);

                if (clsId >= classes.size()) {
                    log.warn("跳过无效类别ID: {}", clsId);
                    continue;
                }

                // 还原到原图坐标
                double x = (box.x - dx) / scale;
                double y = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                x = Math.max(0, Math.min(x, image.cols() - 1));
                y = Math.max(0, Math.min(y, image.rows() - 1));
                width = Math.min(width, image.cols() - x);
                height = Math.min(height, image.rows() - y);

                log.info("检测: {} { } [{ },{ },{ },{ }]",
                        classes.get(clsId), conf, x, y, width, height);

                Scalar color = CommonColors(colorIdx);

                // ========== 生成实例mask并绘制轮廓 ==========
                if (protoOutput != null && idx < maskCoeffsList.size()) {
                    try {
                        Mat instanceMask = generateInstanceMaskOnnx(
                                protoOutput,
                                maskCoeffsList.get(idx),
                                new Rect2d(x, y, width, height),
                                image.size()
                        );

                        if (instanceMask != null) {
                            // 提取并绘制轮廓
                            List<MatOfPoint> contours = new ArrayList<>();
                            Mat hierarchy = new Mat();

                            Imgproc.findContours(instanceMask, contours, hierarchy,
                                    Imgproc.RETR_EXTERNAL,
                                    Imgproc.CHAIN_APPROX_SIMPLE);

                            if (!contours.isEmpty()) {
                                // 绘制多边形轮廓
                                Imgproc.drawContours(image, contours, -1, color, 2);

                                // 绘制半透明填充mask
                                Mat colorMask = new Mat(instanceMask.size(), CvType.CV_8UC3, color);
                                Core.bitwise_and(colorMask, colorMask, colorMask, instanceMask);
                                Core.add(maskOverlay, colorMask, maskOverlay);

                                colorMask.release();
                            }

                            hierarchy.release();
                            instanceMask.release();
                        }
                    } catch (Exception e) {
                        log.warn("生成mask失败: {}", e.getMessage());
                    }
                }

                // 绘制标签
                String label = classes.get(clsId) + ": " + String.format("%.2f", conf);

                int[] baseline = new int[1];
                Size labelSize = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX,
                        0.5, 2, baseline);

                // 标签背景
                Imgproc.rectangle(image,
                        new Point(x, y - labelSize.height - 10),
                        new Point(x + labelSize.width, y),
                        color, -1);

                // 标签文字
                Imgproc.putText(image, label, new Point(x, y - 5),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(255, 255, 255), 2);

                colorIdx++;
            }

            // 叠加半透明mask
            if (Core.countNonZero(maskOverlay.reshape(1, maskOverlay.rows() * maskOverlay.cols())) > 0) {
                Core.addWeighted(image, 0.6, maskOverlay, 0.4, 0, image);
            }
            maskOverlay.release();

            // 9. 保存结果
            String savepath = uploadpath + File.separator + "temp" + File.separator;
            if (saveName != null && !saveName.isEmpty()) {
                savepath += saveName + ".jpg";
            } else {
                saveName = System.currentTimeMillis() + "";
                savepath += saveName + ".jpg";
            }
            Imgcodecs.imwrite(savepath, image);

            long endTime = System.currentTimeMillis();
            log.info("总耗时: {}ms", (endTime - startTime));
            return saveName + ".jpg";
        }
    }

    /**
     * 从ONNX输出生成实例分割mask
     */
    private static Mat generateInstanceMaskOnnx(float[][][][] protoOutput,
                                                float[] maskCoeffs,
                                                Rect2d bbox,
                                                Size imageSize) {
        try {
            // protoOutput: [1, 32, 160, 160]
            int numMasks = protoOutput[0].length;      // 32
            int maskH = protoOutput[0][0].length;      // 160
            int maskW = protoOutput[0][0][0].length;   // 160

            // 矩阵乘法: protos[160*160, 32] × coeffs[32, 1] = mask[160*160, 1]
            float[][] mask160 = new float[maskH][maskW];

            for (int h = 0; h < maskH; h++) {
                for (int w = 0; w < maskW; w++) {
                    float sum = 0;
                    for (int c = 0; c < numMasks; c++) {
                        sum += protoOutput[0][c][h][w] * maskCoeffs[c];
                    }
                    // Sigmoid激活
                    mask160[h][w] = (float) (1.0 / (1.0 + Math.exp(-sum)));
                }
            }

            // 转换为Mat
            Mat mask160Mat = new Mat(maskH, maskW, CvType.CV_32F);
            for (int h = 0; h < maskH; h++) {
                mask160Mat.put(h, 0, mask160[h]);
            }

            // 调整到原图尺寸
            Mat maskResized = new Mat();
            Imgproc.resize(mask160Mat, maskResized, imageSize, 0, 0, Imgproc.INTER_LINEAR);

            // 二值化
            Mat maskBinary = new Mat();
            Imgproc.threshold(maskResized, maskBinary, 0.5, 255, Imgproc.THRESH_BINARY);
            maskBinary.convertTo(maskBinary, CvType.CV_8UC1);

            // 裁剪到bbox区域
            int x = Math.max(0, (int) bbox.x);
            int y = Math.max(0, (int) bbox.y);
            int w = Math.min((int) bbox.width, (int) imageSize.width - x);
            int h = Math.min((int) bbox.height, (int) imageSize.height - y);

            if (w <= 0 || h <= 0) {
                return null;
            }

            Rect bboxRect = new Rect(x, y, w, h);
            Mat finalMask = Mat.zeros(imageSize, CvType.CV_8UC1);
            Mat roiMask = maskBinary.submat(bboxRect);
            Mat roiFinal = finalMask.submat(bboxRect);
            roiMask.copyTo(roiFinal);

            // 释放资源
            mask160Mat.release();
            maskResized.release();
            maskBinary.release();

            return finalMask;

        } catch (Exception e) {
            log.error("ONNX Mask生成错误: {}", e.getMessage(), e);
            return null;
        }
    }
    public static String SendPicOnnxYoloV11(TabAiModel tabAiModel,String picUrl,
                                            String saveName, String uploadpath, boolean useGpu) throws Exception {

        long startTime = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.3f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.2f:tabAiModel.getNmsThreshold().floatValue();
        // 1. 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();

        // 2. 读取图像并预处理
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        Mat processedImage = letterboxResize(image, 640, 640);
        // ✅ 修复：BGR → RGB
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);

        // HWC -> CHW
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);
        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }

        // 3. 创建 ONNX Runtime 环境
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA(); // 使用 GPU
        } else {
            log.info("使用onnx cpu");
            options.setInterOpNumThreads(4);   // 线程池并行
            options.setIntraOpNumThreads(8);   // 单算子内并行
            options.addCPU(true);  // 强制 CPU
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + weight, options)) {
            // 4. 构建输入
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor);

            // 5. 推理
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    OnnxValue value = entry.getValue();
                    if (!(value instanceof OnnxTensor)) continue;
                    OnnxTensor tensor = (OnnxTensor) value;

                    // 获取输出张量的形状
                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    if (rawOutput instanceof float[][][]) { // 常见 YOLOv5/11 输出
                        float[][][] batch = (float[][][]) rawOutput;

                        // 判断是否需要转置 (YOLOv11 格式: [1, 84, 8400])
                        boolean needTranspose = tensorShape.length == 3 &&
                                tensorShape[1] < tensorShape[2] &&
                                tensorShape[1] <= (expectedClassCount + 5);

                        for (float[][] detections : batch) {
                            if (needTranspose) {
                                // YOLOv11 转置处理: [84, 8400] -> 按列遍历
                                int numFeatures = (int) tensorShape[1]; // 84
                                int numDetections = (int) tensorShape[2]; // 8400
                                int numClasses = numFeatures - 4;

                                for (int i = 0; i < numDetections; i++) {
                                    float cx = detections[0][i];
                                    float cy = detections[1][i];
                                    float w = detections[2][i];
                                    float h = detections[3][i];

                                    // 找到最高分数的类别
                                    float maxScore = 0;
                                    int classId = 0;
                                    for (int c = 0; c < numClasses; c++) {
                                        if (detections[4 + c][i] > maxScore) {
                                            maxScore = detections[4 + c][i];
                                            classId = c;
                                        }
                                    }

                                    // 边界检查并添加检测结果
                                    if (maxScore > confThreshold && classId < expectedClassCount) {
                                        boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                        confidences.add(maxScore);
                                        classIds.add(classId);
                                    }
                                }
                            } else {
                                // YOLOv5 原有处理逻辑: [25200, 85]
                                for (float[] det : detections) {
                                    int actualClassCount;
                                    boolean hasObjectness;
                                    if (det.length > 5) { // YOLOv5
                                        hasObjectness = true;
                                        actualClassCount = det.length - 5;
                                    } else { // YOLOv11 (2D输出)
                                        hasObjectness = false;
                                        actualClassCount = det.length - 4;
                                    }

                                    int classId = 0;
                                    float maxScore = 0;
                                    for (int i = hasObjectness ? 5 : 4; i < det.length; i++) {
                                        if (det[i] > maxScore) {
                                            maxScore = det[i];
                                            classId = i - (hasObjectness ? 5 : 4);
                                        }
                                    }

                                    float confidence = hasObjectness ? det[4] * maxScore : maxScore;

                                    // 边界检查并添加检测结果
                                    if (confidence > confThreshold && classId < expectedClassCount) {
                                        float cx = det[0], cy = det[1], w = det[2], h = det[3];
                                        boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                        confidences.add(confidence);
                                        classIds.add(classId);
                                    }
                                }
                            }
                        }
                    } else if (rawOutput instanceof float[][]) {
                        // 可处理部分 YOLOv11 输出为 2D
                        float[][] detections = (float[][]) rawOutput;
                        for (float[] det : detections) {
                            int actualClassCount = det.length - 4;
                            float confidence = 0;
                            int classId = 0;
                            for (int i = 4; i < det.length; i++) {
                                if (det[i] > confidence) {
                                    confidence = det[i];
                                    classId = i - 4;
                                }
                            }

                            // 边界检查并添加检测结果
                            if (confidence > confThreshold && classId < expectedClassCount) {
                                float cx = det[0], cy = det[1], w = det[2], h = det[3];
                                boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                confidences.add(confidence);
                                classIds.add(classId);
                            }
                        }
                    }
                }
            }

            log.info("NMS前检测框数量: " + boxes2d.size());
            // 6. NMS
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArr = indices.toArray();
            log.info("NMS前检测框后数量: " + indicesArr.length);
            // 7. 绘制检测框
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            int colorIdx = 0;
            for (int idx : indicesArr) {
                Rect2d box = boxes2d.get(idx);
                int clsId = classIds.get(idx);
                float conf = confidences.get(idx);

                double x = (box.x - dx) / scale;
                double y = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                x = Math.max(0, Math.min(x, image.cols() - 1));
                y = Math.max(0, Math.min(y, image.rows() - 1));
                width = Math.min(width, image.cols() - x);
                height = Math.min(height, image.rows() - y);
                log.info("识别下标{}识别坐标内容{}{}{}{}",clsId,x,y,width,height);
                Imgproc.rectangle(image, new Point(x, y), new Point(x + width, y + height), CommonColors(colorIdx), 2);
                String label = classes.get(clsId) + ": " + String.format("%.2f", conf);
                Imgproc.putText(image, label, new Point(x, y - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(colorIdx), 2);
                colorIdx++;

            }

            // 8. 保存结果
            String savepath = uploadpath + File.separator + "temp" + File.separator;
            if (saveName != null && !saveName.isEmpty()) {
                savepath += saveName + ".jpg";
            } else {
                saveName = System.currentTimeMillis() + "";
                savepath += saveName + ".jpg";
            }
            Imgcodecs.imwrite(savepath, image);

            long endTime = System.currentTimeMillis();
            System.out.println("总耗时: " + (endTime - startTime) + "ms");
            return saveName + ".jpg";
        }
    }
    public static String SendPicOnnxInsightFace(TabAiModel tabAiModel, String picUrl,
                                                String saveName, String uploadpath, boolean useGpu) throws Exception {

        long startTime = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.65f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.4f:tabAiModel.getNmsThreshold().floatValue();

        // 1. 读取图像并预处理
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        int originalWidth = image.cols();
        int originalHeight = image.rows();

        log.info("原始图像尺寸: {}x{}", originalWidth, originalHeight);

        Mat processedImage = letterboxResize(image, 640, 640);
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);

        // SCRFD 归一化：(pixel - 127.5) / 128.0
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F);
        Core.subtract(blob, new Scalar(127.5, 127.5, 127.5), blob);
        Core.divide(blob, new Scalar(128.0, 128.0, 128.0), blob);

        // HWC -> CHW
        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);
        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }

        // 2. 创建 ONNX Runtime 环境
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA();
        } else {
            log.info("使用onnx cpu");
            options.setInterOpNumThreads(4);
            options.setIntraOpNumThreads(8);
            options.addCPU(true);
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + weight, options)) {
            // 3. 构建输入
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor);

            // 4. 推理并解析 SCRFD 输出
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();

            try (OrtSession.Result results = session.run(inputs)) {
                // 收集所有输出并排序
                Map<String, OnnxTensor> outputMap = new HashMap<>();
                for (Map.Entry<String, OnnxValue> entry : results) {
                    if (entry.getValue() instanceof OnnxTensor) {
                        outputMap.put(entry.getKey(), (OnnxTensor) entry.getValue());
                    }
                }

                List<String> outputNames = new ArrayList<>(outputMap.keySet());
                Collections.sort(outputNames);

                log.info("SCRFD 输出数量: {}", outputNames.size());
                for (int i = 0; i < outputNames.size(); i++) {
                    OnnxTensor tensor = outputMap.get(outputNames.get(i));
                    log.info("输出[{}]: {} - 形状: {}", i, outputNames.get(i),
                            Arrays.toString(tensor.getInfo().getShape()));
                }

                // ✅ 修正：每3个一组 (score, bbox, kps)
                // stride 8: index 0, 1, 2
                // stride 16: index 3, 4, 5
                // stride 32: index 6, 7, 8
                float[][] scores_s8 = (float[][]) outputMap.get(outputNames.get(0)).getValue();
                float[][] bboxes_s8 = (float[][]) outputMap.get(outputNames.get(1)).getValue();

                float[][] scores_s16 = (float[][]) outputMap.get(outputNames.get(3)).getValue();
                float[][] bboxes_s16 = (float[][]) outputMap.get(outputNames.get(4)).getValue();

                float[][] scores_s32 = (float[][]) outputMap.get(outputNames.get(6)).getValue();
                float[][] bboxes_s32 = (float[][]) outputMap.get(outputNames.get(7)).getValue();

                // 计算坐标转换参数
                double scale = Math.min(640.0 / originalWidth, 640.0 / originalHeight);
                double dx = (640 - originalWidth * scale) / 2;
                double dy = (640 - originalHeight * scale) / 2;

                log.info("坐标转换参数 - scale:{}, dx:{}, dy:{}", scale, dx, dy);

                // 处理 stride=8 的输出
                int anchorIdx = 0;
                for (int h = 0; h < 80; h++) {
                    for (int w = 0; w < 80; w++) {
                        for (int a = 0; a < 2; a++) {
                            if (anchorIdx >= scores_s8.length) break;

                            // 应用 sigmoid 激活函数
                            float rawScore = scores_s8[anchorIdx][0];
                            float score = sigmoid(rawScore);

                            if (score > confThreshold) { // 置信度阈值
                                // anchor center point
                                float cx = (w + 0.5f) * 8;
                                float cy = (h + 0.5f) * 8;

                                // 解码bbox
                                float dist_left = bboxes_s8[anchorIdx][0] * 8;
                                float dist_top = bboxes_s8[anchorIdx][1] * 8;
                                float dist_right = bboxes_s8[anchorIdx][2] * 8;
                                float dist_bottom = bboxes_s8[anchorIdx][3] * 8;

                                float x1 = cx - dist_left;
                                float y1 = cy - dist_top;
                                float x2 = cx + dist_right;
                                float y2 = cy + dist_bottom;

                                // 转换回原图坐标
                                x1 = (float) ((x1 - dx) / scale);
                                y1 = (float) ((y1 - dy) / scale);
                                x2 = (float) ((x2 - dx) / scale);
                                y2 = (float) ((y2 - dy) / scale);

                                // 裁剪到图像边界
                                x1 = Math.max(0, Math.min(x1, originalWidth - 1));
                                y1 = Math.max(0, Math.min(y1, originalHeight - 1));
                                x2 = Math.max(0, Math.min(x2, originalWidth - 1));
                                y2 = Math.max(0, Math.min(y2, originalHeight - 1));

                                float width = x2 - x1;
                                float height = y2 - y1;

                                // 更严格的尺寸过滤 + 宽高比检查
                                if (width > 20 && height > 20 && width < originalWidth * 0.8 && height < originalHeight * 0.8) {
                                    float aspectRatio = width / height;
                                    if (aspectRatio > 0.5 && aspectRatio < 2.0) {
                                        boxes2d.add(new Rect2d(x1, y1, width, height));
                                        confidences.add(score);
                                    }
                                }
                            }
                            anchorIdx++;
                        }
                    }
                }
                log.info("stride=8 处理完成");

                // 处理 stride=16 的输出
                anchorIdx = 0;
                for (int h = 0; h < 40; h++) {
                    for (int w = 0; w < 40; w++) {
                        for (int a = 0; a < 2; a++) {
                            if (anchorIdx >= scores_s16.length) break;

                            float rawScore = scores_s16[anchorIdx][0];
                            float score = sigmoid(rawScore);

                            if (score > confThreshold) {
                                float cx = (w + 0.5f) * 16;
                                float cy = (h + 0.5f) * 16;

                                float dist_left = bboxes_s16[anchorIdx][0] * 16;
                                float dist_top = bboxes_s16[anchorIdx][1] * 16;
                                float dist_right = bboxes_s16[anchorIdx][2] * 16;
                                float dist_bottom = bboxes_s16[anchorIdx][3] * 16;

                                float x1 = cx - dist_left;
                                float y1 = cy - dist_top;
                                float x2 = cx + dist_right;
                                float y2 = cy + dist_bottom;

                                x1 = (float) ((x1 - dx) / scale);
                                y1 = (float) ((y1 - dy) / scale);
                                x2 = (float) ((x2 - dx) / scale);
                                y2 = (float) ((y2 - dy) / scale);

                                x1 = Math.max(0, Math.min(x1, originalWidth - 1));
                                y1 = Math.max(0, Math.min(y1, originalHeight - 1));
                                x2 = Math.max(0, Math.min(x2, originalWidth - 1));
                                y2 = Math.max(0, Math.min(y2, originalHeight - 1));

                                float width = x2 - x1;
                                float height = y2 - y1;

                                if (width > 20 && height > 20 && width < originalWidth * 0.8 && height < originalHeight * 0.8) {
                                    float aspectRatio = width / height;
                                    if (aspectRatio > 0.5 && aspectRatio < 2.0) {
                                        boxes2d.add(new Rect2d(x1, y1, width, height));
                                        confidences.add(score);
                                    }
                                }
                            }
                            anchorIdx++;
                        }
                    }
                }
                log.info("stride=16 处理完成");

                // 处理 stride=32 的输出
                anchorIdx = 0;
                for (int h = 0; h < 20; h++) {
                    for (int w = 0; w < 20; w++) {
                        for (int a = 0; a < 2; a++) {
                            if (anchorIdx >= scores_s32.length) break;

                            float rawScore = scores_s32[anchorIdx][0];
                            float score = sigmoid(rawScore);

                            if (score > confThreshold) {
                                float cx = (w + 0.5f) * 32;
                                float cy = (h + 0.5f) * 32;

                                float dist_left = bboxes_s32[anchorIdx][0] * 32;
                                float dist_top = bboxes_s32[anchorIdx][1] * 32;
                                float dist_right = bboxes_s32[anchorIdx][2] * 32;
                                float dist_bottom = bboxes_s32[anchorIdx][3] * 32;

                                float x1 = cx - dist_left;
                                float y1 = cy - dist_top;
                                float x2 = cx + dist_right;
                                float y2 = cy + dist_bottom;

                                x1 = (float) ((x1 - dx) / scale);
                                y1 = (float) ((y1 - dy) / scale);
                                x2 = (float) ((x2 - dx) / scale);
                                y2 = (float) ((y2 - dy) / scale);

                                x1 = Math.max(0, Math.min(x1, originalWidth - 1));
                                y1 = Math.max(0, Math.min(y1, originalHeight - 1));
                                x2 = Math.max(0, Math.min(x2, originalWidth - 1));
                                y2 = Math.max(0, Math.min(y2, originalHeight - 1));

                                float width = x2 - x1;
                                float height = y2 - y1;

                                if (width > 20 && height > 20 && width < originalWidth * 0.8 && height < originalHeight * 0.8) {
                                    float aspectRatio = width / height;
                                    if (aspectRatio > 0.5 && aspectRatio < 2.0) {
                                        boxes2d.add(new Rect2d(x1, y1, width, height));
                                        confidences.add(score);
                                    }
                                }
                            }
                            anchorIdx++;
                        }
                    }
                }
                log.info("stride=32 处理完成");
            }

            log.info("NMS前检测框数量: {}", boxes2d.size());

            // 5. NMS - 更严格的参数
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArr = indices.toArray();
            log.info("NMS后检测框数量: {}", indicesArr.length);

            // 6. 绘制检测框
            for (int i = 0; i < indicesArr.length; i++) {
                int idx = indicesArr[i];
                Rect2d box = boxes2d.get(idx);
                float conf = confidences.get(idx);

                double x = box.x;
                double y = box.y;
                double width = box.width;
                double height = box.height;

                log.info("✅ 人脸 {} - 坐标: ({}, {}), 尺寸: {}x{}, 置信度: {}",
                        i + 1, x, y, width, height, conf);

                // 绘制矩形框
                Scalar color = CommonColors(i % 10);
                Imgproc.rectangle(image,
                        new Point(x, y),
                        new Point(x + width, y + height),
                        color, 2);

                // 绘制标签
                String label = String.format("Face %.2f", conf);
                int[] baseline = new int[1];
                Size textSize = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, 1, baseline);

                // 标签背景
                Imgproc.rectangle(image,
                        new Point(x, y - textSize.height - 10),
                        new Point(x + textSize.width, y),
                        color, -1);

                // 标签文字
                Imgproc.putText(image, label,
                        new Point(x, y - 5),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.5,
                        new Scalar(255, 255, 255), 1);
            }

            // 7. 保存结果
            String savepath = uploadpath + File.separator + "temp" + File.separator;
            if (saveName != null && !saveName.isEmpty()) {
                savepath += saveName + ".jpg";
            } else {
                saveName = System.currentTimeMillis() + "";
                savepath += saveName + ".jpg";
            }
            Imgcodecs.imwrite(savepath, image);

            long endTime = System.currentTimeMillis();
            log.info("总耗时: {}ms", (endTime - startTime));
            return saveName + ".jpg";
        }
    }



    /**
     * 处理 SCRFD 单个尺度的输出
     */
    private static void processSCRFDScale(float[][] scores, float[][] bboxes,
                                          int stride, int featSize,
                                          List<Rect2d> boxes2d, List<Float> confidences,
                                          double scale, double dx, double dy,
                                          int originalWidth, int originalHeight) {

        int anchorIdx = 0;
        for (int h = 0; h < featSize; h++) {
            for (int w = 0; w < featSize; w++) {
                for (int a = 0; a < 2; a++) { // 每个位置2个anchor
                    if (anchorIdx >= scores.length) break;

                    float score = scores[anchorIdx][0];
                    if (score < 0.5f) { // 置信度阈值
                        anchorIdx++;
                        continue;
                    }

                    // anchor center
                    float cx = (w + 0.5f) * stride;
                    float cy = (h + 0.5f) * stride;

                    // 解码边界框: distance to ltrb
                    float left = bboxes[anchorIdx][0] * stride;
                    float top = bboxes[anchorIdx][1] * stride;
                    float right = bboxes[anchorIdx][2] * stride;
                    float bottom = bboxes[anchorIdx][3] * stride;

                    // 计算实际坐标
                    float x1 = cx - left;
                    float y1 = cy - top;
                    float x2 = cx + right;
                    float y2 = cy + bottom;

                    // 转换回原图尺寸
                    x1 = (float) ((x1 - dx) / scale);
                    y1 = (float) ((y1 - dy) / scale);
                    x2 = (float) ((x2 - dx) / scale);
                    y2 = (float) ((y2 - dy) / scale);

                    // 边界裁剪
                    x1 = Math.max(0, Math.min(x1, originalWidth));
                    y1 = Math.max(0, Math.min(y1, originalHeight));
                    x2 = Math.max(0, Math.min(x2, originalWidth));
                    y2 = Math.max(0, Math.min(y2, originalHeight));

                    float w_box = x2 - x1;
                    float h_box = y2 - y1;

                    if (w_box > 0 && h_box > 0) {
                        boxes2d.add(new Rect2d(x1, y1, w_box, h_box));
                        confidences.add(score);
                    }

                    anchorIdx++;
                }
            }
        }

        log.info("stride={} 处理完成，检测到 {} 个候选框", stride, anchorIdx);
    }
    /***
     *  截取face
     * @param weight
     * @param names
     * @param picUrl
     * @param saveName
     * @param uploadpath
     * @param useGpu
     * @return
     * @throws Exception
     */
    public static retureBoxInfo SendPicOnnxFace(String weight, String names, String picUrl,
                                                String saveName, String uploadpath, boolean useGpu) throws Exception {

        long startTime = System.currentTimeMillis();
        retureBoxInfo retureBoxInfo=new retureBoxInfo();
        retureBoxInfo.setFlag(false);


        // 1. 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();

        // 2. 读取图像并预处理
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        Mat processedImage = letterboxResize(image, 640, 640);
        // ✅ 修复：BGR → RGB
        Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);

        // HWC -> CHW
        Mat blob = new Mat();
        processedImage.convertTo(blob, CvType.CV_32F, 1.0 / 255.0);
        List<Mat> channels = new ArrayList<>();
        Core.split(blob, channels);
        float[] inputData = new float[3 * 640 * 640];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[640 * 640];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * 640 * 640, 640 * 640);
        }

        // 3. 创建 ONNX Runtime 环境
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA(); // 使用 GPU
        } else {
            log.info("使用onnx cpu");
            options.setInterOpNumThreads(4);   // 线程池并行
            options.setIntraOpNumThreads(8);   // 单算子内并行
            options.addCPU(true);  // 强制 CPU
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + weight, options)) {
            // 4. 构建输入
            long[] shape = new long[]{1, 3, 640, 640};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(session.getInputNames().iterator().next(), inputTensor);

            // 5. 推理
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();

            try (OrtSession.Result results = session.run(inputs)) {
                for (Map.Entry<String, OnnxValue> entry : results) {
                    OnnxValue value = entry.getValue();
                    if (!(value instanceof OnnxTensor)) continue;
                    OnnxTensor tensor = (OnnxTensor) value;

                    // 获取输出张量的形状
                    long[] tensorShape = tensor.getInfo().getShape();
                    Object rawOutput = tensor.getValue();

                    if (rawOutput instanceof float[][][]) { // 常见 YOLOv5/11 输出
                        float[][][] batch = (float[][][]) rawOutput;

                        // 判断是否需要转置 (YOLOv11 格式: [1, 84, 8400])
                        boolean needTranspose = tensorShape.length == 3 &&
                                tensorShape[1] < tensorShape[2] &&
                                tensorShape[1] <= (expectedClassCount + 5);

                        for (float[][] detections : batch) {
                            if (needTranspose) {
                                // YOLOv11 转置处理: [84, 8400] -> 按列遍历
                                int numFeatures = (int) tensorShape[1]; // 84
                                int numDetections = (int) tensorShape[2]; // 8400
                                int numClasses = numFeatures - 4;

                                for (int i = 0; i < numDetections; i++) {
                                    float cx = detections[0][i];
                                    float cy = detections[1][i];
                                    float w = detections[2][i];
                                    float h = detections[3][i];

                                    // 找到最高分数的类别
                                    float maxScore = 0;
                                    int classId = 0;
                                    for (int c = 0; c < numClasses; c++) {
                                        if (detections[4 + c][i] > maxScore) {
                                            maxScore = detections[4 + c][i];
                                            classId = c;
                                        }
                                    }

                                    // 边界检查并添加检测结果
                                    if (maxScore > 0.35f && classId < expectedClassCount) {
                                        boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                        confidences.add(maxScore);
                                        classIds.add(classId);
                                    }
                                }
                            } else {
                                // YOLOv5 原有处理逻辑: [25200, 85]
                                for (float[] det : detections) {
                                    int actualClassCount;
                                    boolean hasObjectness;
                                    if (det.length > 5) { // YOLOv5
                                        hasObjectness = true;
                                        actualClassCount = det.length - 5;
                                    } else { // YOLOv11 (2D输出)
                                        hasObjectness = false;
                                        actualClassCount = det.length - 4;
                                    }

                                    int classId = 0;
                                    float maxScore = 0;
                                    for (int i = hasObjectness ? 5 : 4; i < det.length; i++) {
                                        if (det[i] > maxScore) {
                                            maxScore = det[i];
                                            classId = i - (hasObjectness ? 5 : 4);
                                        }
                                    }

                                    float confidence = hasObjectness ? det[4] * maxScore : maxScore;

                                    // 边界检查并添加检测结果
                                    if (confidence > 0.35f && classId < expectedClassCount) {
                                        float cx = det[0], cy = det[1], w = det[2], h = det[3];
                                        boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                        confidences.add(confidence);
                                        classIds.add(classId);
                                    }
                                }
                            }
                        }
                    } else if (rawOutput instanceof float[][]) {
                        // 可处理部分 YOLOv11 输出为 2D
                        float[][] detections = (float[][]) rawOutput;
                        for (float[] det : detections) {
                            int actualClassCount = det.length - 4;
                            float confidence = 0;
                            int classId = 0;
                            for (int i = 4; i < det.length; i++) {
                                if (det[i] > confidence) {
                                    confidence = det[i];
                                    classId = i - 4;
                                }
                            }

                            // 边界检查并添加检测结果
                            if (confidence > 0.35f && classId < expectedClassCount) {
                                float cx = det[0], cy = det[1], w = det[2], h = det[3];
                                boxes2d.add(new Rect2d(cx - w / 2, cy - h / 2, w, h));
                                confidences.add(confidence);
                                classIds.add(classId);
                            }
                        }
                    }
                }
            }

            // 6. NMS
            MatOfRect2d boxesMat = new MatOfRect2d();
            boxesMat.fromList(boxes2d);
            MatOfFloat confidencesMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            if (!boxesMat.empty() && !confidencesMat.empty()) {
                Dnn.NMSBoxes(boxesMat, confidencesMat, 0.1f, 0.5f, indices);
            }

            int[] indicesArr = indices.toArray();

            // 7. 绘制检测框
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;

            int colorIdx = 0;
            for (int idx : indicesArr) {
                Rect2d box = boxes2d.get(idx);
                int clsId = classIds.get(idx);
                float conf = confidences.get(idx);

                double x = (box.x - dx) / scale;
                double y = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                x = Math.max(0, Math.min(x, image.cols() - 1));
                y = Math.max(0, Math.min(y, image.rows() - 1));
                width = Math.min(width, image.cols() - x);
                height = Math.min(height, image.rows() - y);

                Imgproc.rectangle(image, new Point(x, y), new Point(x + width, y + height), CommonColors(colorIdx), 2);
                String label = classes.get(clsId) + ": " + String.format("%.2f", conf);
                Imgproc.putText(image, label, new Point(x, y - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(colorIdx), 2);
                colorIdx++;
            }

            return retureBoxInfo;
        }
    }
    public static String SendPicYoloV11Seg(TabAiModel tabAiModel, String picUrl, String saveName, String uploadpath, boolean gpuFlag) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        String weight = tabAiModel.getAiWeights();
        String names = tabAiModel.getAiNameName();

        float confThreshold = tabAiModel.getThreshold() == null ? 0.3f : tabAiModel.getThreshold().floatValue();
        float nmsThreshold = tabAiModel.getNmsThreshold() == null ? 0.2f : tabAiModel.getNmsThreshold().floatValue();

        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);

        if (gpuFlag) {
            net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
            net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);
            log.info("[DNN推理规则：GPU]");
        } else {
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
            log.info("[DNN推理规则：CPU]");
        }

        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        Mat processedImage = letterboxResize(image, 640, 640);
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640),
                new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);

        if (result.isEmpty()) {
            log.error("Failed to get output from the model.");
            return "error";
        }

        // ========== YOLOv11实例分割模型输出解析 ==========
        Mat detectOutput = null;  // 检测输出 [1, 51, 8400]
        Mat protoOutput = null;   // 原型输出 [1, 32, 160, 160]

        for (Mat output : result) {
            int dims = output.dims();
            log.info("========================================");

            if (dims == 3) {
                long dim0 = output.size(0);
                long dim1 = output.size(1);
                long dim2 = output.size(2);
                log.info("3维输出: [{}, {}, {}]", dim0, dim1, dim2);

                // YOLOv11实例分割检测头: [1, 4+classes+masks, anchors]
                // 例如: [1, 51, 8400] = [1, 4+15+32, 8400]
                if (dim1 < 200 && dim2 > 1000) {
                    detectOutput = output;
                    log.info("识别为检测输出 (包含bbox+类别+mask系数)");
                }
            } else if (dims == 4) {
                long dim0 = output.size(0);
                long dim1 = output.size(1);
                long dim2 = output.size(2);
                long dim3 = output.size(3);
                log.info("4维输出: [{}, {}, {}, {}]", dim0, dim1, dim2, dim3);

                // YOLOv11实例分割原型: [1, num_masks, H, W]
                // 例如: [1, 32, 160, 160]
                protoOutput = output;
                log.info("识别为Mask原型输出");
            }
        }

        if (detectOutput == null) {
            log.error("未找到检测输出!");
            return "error";
        }

        // ========== 解析检测输出 ==========
        long dim0 = detectOutput.size(0);
        long dim1 = detectOutput.size(1);  // 51 = 4 + 15 + 32
        long dim2 = detectOutput.size(2);  // 8400

        log.info("检测输出维度: [{}, {}, {}]", dim0, dim1, dim2);

        // 计算实际结构
        int numMasks = (protoOutput != null) ? (int)protoOutput.size(1) : 0;  // 32
        int numClasses = (int)dim1 - 4 - numMasks;  // 51 - 4 - 32 = 15

        log.info("类别数: {}, Mask系数数: {}, 期望类别数: {}", numClasses, numMasks, expectedClassCount);

        if (numClasses != expectedClassCount) {
            log.warn("警告: 计算的类别数({})与names文件({})不匹配!", numClasses, expectedClassCount);
        }

        // 转置为 [8400, 51]
        Mat reshaped = detectOutput.reshape(1, (int) dim1);
        Mat transposed = new Mat();
        Core.transpose(reshaped, transposed);

        int rows = (int) dim2;  // 8400
        int cols = (int) dim1;  // 51

        log.info("处理后矩阵: {} x {}", rows, cols);

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();
        List<Mat> maskCoeffs = new ArrayList<>();  // 存储每个检测的mask系数

        // ========== 遍历每个anchor point ==========
        for (int i = 0; i < rows; i++) {
            Mat detection = transposed.row(i);

            // 提取bbox坐标 [0:4]
            float centerX = (float) detection.get(0, 0)[0];
            float centerY = (float) detection.get(0, 1)[0];
            float width = (float) detection.get(0, 2)[0];
            float height = (float) detection.get(0, 3)[0];

            // 提取类别分数 [4:4+numClasses]
            Mat classScores = detection.colRange(4, 4 + numClasses);
            Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(classScores);
            float maxClassScore = (float) minMaxResult.maxVal;
            int classId = (int) minMaxResult.maxLoc.x;

            // YOLOv11实例分割没有objectness，置信度就是类别分数
            float confidence = maxClassScore;

            if (confidence > confThreshold && classId < numClasses) {
                float left = centerX - width / 2;
                float top = centerY - height / 2;

                classIds.add(classId);
                confidences.add(confidence);
                boxes2d.add(new Rect2d(left, top, width, height));

                // 提取mask系数 [4+numClasses : 4+numClasses+numMasks]
                if (protoOutput != null && numMasks > 0) {
                    Mat maskCoeff = detection.colRange(4 + numClasses, cols).clone();
                    maskCoeffs.add(maskCoeff);
                }
            }
        }

        log.info("NMS前检测框数量: {}", boxes2d.size());

        // ========== NMS ==========
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indices_arr = indices.toArray();
        log.info("NMS后检测框数量: {}", indices_arr.length);

        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        // ========== 生成并绘制分割mask ==========
        // ========== 生成并绘制分割mask ==========
        Mat maskOverlay = Mat.zeros(image.size(), CvType.CV_8UC3);

        int c = 0;
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);

            if (classId >= classes.size()) {
                log.warn("跳过无效类别ID: {}", classId);
                continue;
            }

            // 还原到原图坐标
            double x = (box.x - dx) / scale;
            double y = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;

            x = Math.max(0, Math.min(x, image.cols() - 1));
            y = Math.max(0, Math.min(y, image.rows() - 1));
            width = Math.min(width, image.cols() - x);
            height = Math.min(height, image.rows() - y);

            log.info("检测: {} { } [{ },{ },{ },{ }]",
                    classes.get(classId), conf, x, y, width, height);

            Scalar color = CommonColors(c);

            // ========== 生成实例mask并绘制轮廓 ==========
            if (protoOutput != null && idx < maskCoeffs.size()) {
                try {
                    Mat instanceMask = generateInstanceMask(
                            protoOutput,
                            maskCoeffs.get(idx),
                            new Rect2d(x, y, width, height),
                            image.size(),
                            scale, dx, dy
                    );

                    if (instanceMask != null) {
                        // ========== 提取并绘制多边形轮廓 ==========
                        List<MatOfPoint> contours = new ArrayList<>();
                        Mat hierarchy = new Mat();

                        // 查找轮廓
                        Imgproc.findContours(instanceMask, contours, hierarchy,
                                Imgproc.RETR_EXTERNAL,
                                Imgproc.CHAIN_APPROX_SIMPLE);

                        if (!contours.isEmpty()) {
                            // 绘制所有轮廓（多边形）
                            Imgproc.drawContours(image, contours, -1, color, 2);

                            // 可选：绘制填充的半透明mask
                            Mat colorMask = new Mat(instanceMask.size(), CvType.CV_8UC3, color);
                            Core.bitwise_and(colorMask, colorMask, colorMask, instanceMask);
                            Core.add(maskOverlay, colorMask, maskOverlay);

                            colorMask.release();
                        }

                        hierarchy.release();
                        instanceMask.release();
                    }
                } catch (Exception e) {
                    log.warn("生成mask失败: {}", e.getMessage());
                }
            } else {
                // 如果没有mask，退回到绘制矩形框
                Imgproc.rectangle(image,
                        new Point(x, y),
                        new Point(x + width, y + height),
                        color, 2);
            }

            // 绘制标签
            String label = classes.get(classId) + ": " + String.format("%.2f", conf);

            // 添加文字背景（可选，让文字更清晰）
            int[] baseline = new int[1];
            Size labelSize = Imgproc.getTextSize(label, Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.5, 2, baseline);

            // 绘制标签背景
            Imgproc.rectangle(image,
                    new Point(x, y - labelSize.height - 10),
                    new Point(x + labelSize.width, y),
                    color, -1);  // 填充矩形

            // 绘制标签文字（白色）
            Imgproc.putText(image, label, new Point(x, y - 5),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, new Scalar(255, 255, 255), 2);

            c++;
        }

// 叠加半透明mask
        if (Core.countNonZero(maskOverlay.reshape(1, maskOverlay.rows() * maskOverlay.cols())) > 0) {
            Core.addWeighted(image, 0.6, maskOverlay, 0.4, 0, image);
        }

        maskOverlay.release();

        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }

        log.info("保存路径: {}", savepath);
        Imgcodecs.imwrite(savepath, image);

        Long b = System.currentTimeMillis();
        log.info("总耗时: {}ms", (b - a));

        return saveName + ".jpg";
    }

    // ========== 辅助方法：生成实例分割mask ==========
    // ========== 辅助方法：生成实例分割mask ==========
    private static Mat generateInstanceMask(Mat protoOutput, Mat maskCoeffs,
                                            Rect2d bbox, Size imageSize,
                                            double scale, double dx, double dy) {
        try {
            // protoOutput: [1, 32, 160, 160]
            // maskCoeffs: [1, 32]

            int numMasks = (int) protoOutput.size(1);  // 32
            int maskH = (int) protoOutput.size(2);     // 160
            int maskW = (int) protoOutput.size(3);     // 160

            // 提取原型矩阵 [32, 160*160]
            Mat protos = protoOutput.reshape(1, numMasks);  // [32, 25600]

            // maskCoeffs reshape 为 [32, 1]
            Mat coeffs = maskCoeffs.reshape(1, numMasks);  // [32, 1]

            // 矩阵乘法: [160*160, 32] × [32, 1] = [160*160, 1]
            Mat protosT = new Mat();
            Core.transpose(protos, protosT);  // [25600, 32]

            Mat maskVec = new Mat();
            Core.gemm(protosT, coeffs, 1, new Mat(), 0, maskVec);  // [25600, 1]

            // Reshape回 [160, 160]
            Mat mask160 = maskVec.reshape(1, maskH);

            // ========== 修正：Sigmoid激活 ==========
            // 方法1: 分步计算
            Mat negMask = new Mat();
            Core.multiply(mask160, Scalar.all(-1), negMask);  // -mask

            Mat expMask = new Mat();
            Core.exp(negMask, expMask);  // exp(-mask)

            Mat onePlusExp = new Mat();
            Core.add(expMask, Scalar.all(1), onePlusExp);  // 1 + exp(-mask)

            Mat sigmoidMask = new Mat();
            Core.divide(1, onePlusExp, sigmoidMask);  // 1 / (1 + exp(-mask))

            // 调整到原图尺寸
            Mat maskResized = new Mat();
            Imgproc.resize(sigmoidMask, maskResized, imageSize, 0, 0, Imgproc.INTER_LINEAR);

            // 二值化
            Mat maskBinary = new Mat();
            Imgproc.threshold(maskResized, maskBinary, 0.5, 255, Imgproc.THRESH_BINARY);
            maskBinary.convertTo(maskBinary, CvType.CV_8UC1);

            // 裁剪到bbox区域
            int x = Math.max(0, (int)bbox.x);
            int y = Math.max(0, (int)bbox.y);
            int w = Math.min((int)bbox.width, (int)imageSize.width - x);
            int h = Math.min((int)bbox.height, (int)imageSize.height - y);

            if (w <= 0 || h <= 0) {
                return null;
            }

            Rect bboxRect = new Rect(x, y, w, h);

            // 创建只在bbox内的mask
            Mat finalMask = Mat.zeros(imageSize, CvType.CV_8UC1);
            Mat roiMask = maskBinary.submat(bboxRect);
            Mat roiFinal = finalMask.submat(bboxRect);
            roiMask.copyTo(roiFinal);

            // 释放临时矩阵
            negMask.release();
            expMask.release();
            onePlusExp.release();
            sigmoidMask.release();
            maskResized.release();
            maskBinary.release();

            return finalMask;

        } catch (Exception e) {
            log.error("Mask生成错误: {}", e.getMessage(), e);
            return null;
        }
    }
    public static String SendPicYoloV11(TabAiModel tabAiModel, String picUrl, String saveName, String uploadpath,boolean gpuFlag) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.3f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.2f:tabAiModel.getNmsThreshold().floatValue();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();
        // 加载YOLOv11模型
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);

        if (gpuFlag) { //gpu
            net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
            net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);  //gpu推理
            log.info("[DNN推理规则：GPU]");
        } else {
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);  //cpu推理
            log.info("[DNN推理规则：CPU]");
        }
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        // ==========关键修正1: 使用letterbox预处理==========
        Mat processedImage = letterboxResize(image, 640, 640);

        // 创建blob - 注意参数调整
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);

        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }



        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            long dim0 = output.size(0);
            long dim1 = output.size(1);
            long dim2 = output.size(2);

            log.info("========================================");
            log.info("输出维度: [" + dim0 + ", " + dim1 + ", " + dim2 + "]");

            Mat detectionMat;
            int rows, cols;
            String formatType = "UNKNOWN";

            // ========== 智能格式识别 ==========
            if (dims == 3) {
                if (dim1 < 100 && dim2 > 1000) {
                    // YOLOv11格式: [1, C, N] 其中 C=(4+类别数), N=锚点数
                    // 例如: [1, 6, 8400] 或 [1, 84, 8400]
                    formatType = "YOLOv11";
                    Mat reshaped = output.reshape(1, (int) dim1);
                    Mat transposed = new Mat();
                    Core.transpose(reshaped, transposed);
                    detectionMat = transposed;
                    rows = (int) dim2;  // 8400
                    cols = (int) dim1;  // 6 或 84
                } else if (dim1 > 1000 && dim2 < 100) {
                    // YOLOv5格式: [1, N, C] 其中 N=锚点数, C=(5+类别数)
                    // 例如: [1, 25200, 85]
                    formatType = "YOLOv5";
                    detectionMat = output.reshape(1, (int) dim1);
                    rows = (int) dim1;  // 25200
                    cols = (int) dim2;  // 85
                } else {
                    // 尝试自动推断
                    log.error("警告: 非标准维度,尝试自动处理");
                    detectionMat = output.reshape(1, (int) dim1);
                    rows = detectionMat.rows();
                    cols = detectionMat.cols();
                    formatType = "AUTO";
                }
            } else {
                log.error("不支持的维度数: " + dims);
                continue;
            }

            log.info("识别格式: " + formatType);
            log.info("处理后矩阵: " + rows + " x " + cols);

            // ========== 根据格式动态计算类别数 ==========
            int actualClassCount;
            boolean hasObjectness;

            if (formatType.equals("YOLOv11")) {
                // YOLOv11: [x, y, w, h, class_scores...]
                hasObjectness = false;
                actualClassCount = cols - 4;
            } else if (formatType.equals("YOLOv5")) {
                // YOLOv5: [x, y, w, h, objectness, class_scores...]
                hasObjectness = true;
                actualClassCount = cols - 5;
            } else {
                // 自动判断: 如果第5列数值普遍在[0,1]且较大,可能是objectness
                // 这里简化处理,假设列数>5就是YOLOv5格式
                if (cols > 5) {
                    hasObjectness = true;
                    actualClassCount = cols - 5;
                } else {
                    hasObjectness = false;
                    actualClassCount = cols - 4;
                }
            }

            log.info("检测到类别数: " + actualClassCount + " (期望: " + expectedClassCount + ")");
            log.info("是否包含objectness: " + hasObjectness);

            // 验证有效性
            if (actualClassCount < 1) {
                log.info("错误: 计算出的类别数无效 (" + actualClassCount + "),跳过此输出");
                continue;
            }


            // ========== 统一的置信度计算 ==========
            int scoresStartCol = hasObjectness ? 5 : 4;  // 类别分数起始列

            for (int i = 0; i < rows; i++) {
                Mat detection = detectionMat.row(i);

                float confidence;
                int classId;

                // 提取类别分数
                Mat scores = detection.colRange(scoresStartCol, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float maxClassScore = (float) minMaxResult.maxVal;
                classId = (int) minMaxResult.maxLoc.x;

                if (hasObjectness) {
                    // YOLOv5: confidence = objectness × max_class_score
                    float objectness = (float) detection.get(0, 4)[0];
                    confidence = objectness * maxClassScore;
                } else {
                    // YOLOv11: confidence = max_class_score
                    confidence = maxClassScore;
                }

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    // 验证类别ID有效性
                    if (classId < 0 || classId >= actualClassCount) {
                        System.err.println("警告: 无效的类别ID " + classId + ",跳过");
                        continue;
                    }

                    classIds.add(classId);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));


                }
            }
        }

        log.info("NMS前检测框数量: " + boxes2d.size());

        // 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indices_arr = indices.toArray();
        log.info("NMS后检测框数量: " + indices_arr.length);

        // ==========关键修正4: 坐标还原==========
        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        // 绘制结果
        int c = 0;
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);

            // 还原到原图坐标
            double x = (box.x - dx) / scale;
            double y = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;

            // 确保坐标在图像范围内
            x = Math.max(0, Math.min(x, image.cols() - 1));
            y = Math.max(0, Math.min(y, image.rows() - 1));
            width = Math.min(width, image.cols() - x);
            height = Math.min(height, image.rows() - y);

            System.out.println(String.format("最终检测框: %s %.3f [%.1f,%.1f,%.1f,%.1f]",
                    classes.get(classId), conf, x, y, width, height));

            Imgproc.rectangle(image,
                    new Point(x, y),
                    new Point(x + width, y + height),
                    CommonColors(c), 2);

            String label = classes.get(classId) + ": " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(x, y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);
            c++;
        }

        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }

        log.info("保存路径: {}", savepath);
        Imgcodecs.imwrite(savepath, image);

        Long b = System.currentTimeMillis();
        log.info("总耗时: {}ms", (b - a));

        return saveName + ".jpg";
    }
    public static pushApi SendPicYoloV11PushApi(TabAiModel tabAiModel, String picUrl, String saveName, String uploadpath,boolean gpuFlag,String name) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        String weight=tabAiModel.getAiWeights();
        String names=tabAiModel.getAiNameName();

        float confThreshold=tabAiModel.getThreshold()==null?0.3f:tabAiModel.getThreshold().floatValue();
        float nmsThreshold=tabAiModel.getNmsThreshold()==null?0.2f:tabAiModel.getNmsThreshold().floatValue();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        int expectedClassCount = classes.size();
        // 加载YOLOv11模型
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);

        if (gpuFlag) { //gpu
            net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
            net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);  //gpu推理
            log.info("[DNN推理规则：GPU]");
        } else {
            net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
            net.setPreferableTarget(Dnn.DNN_TARGET_CPU);  //cpu推理
            log.info("[DNN推理规则：CPU]");
        }
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("原始图片尺寸: {}x{}", image.cols(), image.rows());

        // ==========关键修正1: 使用letterbox预处理==========
        Mat processedImage = letterboxResize(image, 640, 640);

        // 创建blob - 注意参数调整
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);

        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return null;
        }



        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            long dim0 = output.size(0);
            long dim1 = output.size(1);
            long dim2 = output.size(2);

            log.info("========================================");
            log.info("输出维度: [" + dim0 + ", " + dim1 + ", " + dim2 + "]");

            Mat detectionMat;
            int rows, cols;
            String formatType = "UNKNOWN";

            // ========== 智能格式识别 ==========
            if (dims == 3) {
                if (dim1 < 100 && dim2 > 1000) {
                    // YOLOv11格式: [1, C, N] 其中 C=(4+类别数), N=锚点数
                    // 例如: [1, 6, 8400] 或 [1, 84, 8400]
                    formatType = "YOLOv11";
                    Mat reshaped = output.reshape(1, (int) dim1);
                    Mat transposed = new Mat();
                    Core.transpose(reshaped, transposed);
                    detectionMat = transposed;
                    rows = (int) dim2;  // 8400
                    cols = (int) dim1;  // 6 或 84
                } else if (dim1 > 1000 && dim2 < 100) {
                    // YOLOv5格式: [1, N, C] 其中 N=锚点数, C=(5+类别数)
                    // 例如: [1, 25200, 85]
                    formatType = "YOLOv5";
                    detectionMat = output.reshape(1, (int) dim1);
                    rows = (int) dim1;  // 25200
                    cols = (int) dim2;  // 85
                } else {
                    // 尝试自动推断
                    log.error("警告: 非标准维度,尝试自动处理");
                    detectionMat = output.reshape(1, (int) dim1);
                    rows = detectionMat.rows();
                    cols = detectionMat.cols();
                    formatType = "AUTO";
                }
            } else {
                log.error("不支持的维度数: " + dims);
                continue;
            }

            log.info("识别格式: " + formatType);
            log.info("处理后矩阵: " + rows + " x " + cols);

            // ========== 根据格式动态计算类别数 ==========
            int actualClassCount;
            boolean hasObjectness;

            if (formatType.equals("YOLOv11")) {
                // YOLOv11: [x, y, w, h, class_scores...]
                hasObjectness = false;
                actualClassCount = cols - 4;
            } else if (formatType.equals("YOLOv5")) {
                // YOLOv5: [x, y, w, h, objectness, class_scores...]
                hasObjectness = true;
                actualClassCount = cols - 5;
            } else {
                // 自动判断: 如果第5列数值普遍在[0,1]且较大,可能是objectness
                // 这里简化处理,假设列数>5就是YOLOv5格式
                if (cols > 5) {
                    hasObjectness = true;
                    actualClassCount = cols - 5;
                } else {
                    hasObjectness = false;
                    actualClassCount = cols - 4;
                }
            }

            log.info("检测到类别数: " + actualClassCount + " (期望: " + expectedClassCount + ")");
            log.info("是否包含objectness: " + hasObjectness);

            // 验证有效性
            if (actualClassCount < 1) {
                log.info("错误: 计算出的类别数无效 (" + actualClassCount + "),跳过此输出");
                continue;
            }


            // ========== 统一的置信度计算 ==========
            int scoresStartCol = hasObjectness ? 5 : 4;  // 类别分数起始列

            for (int i = 0; i < rows; i++) {
                Mat detection = detectionMat.row(i);

                float confidence;
                int classId;

                // 提取类别分数
                Mat scores = detection.colRange(scoresStartCol, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float maxClassScore = (float) minMaxResult.maxVal;
                classId = (int) minMaxResult.maxLoc.x;

                if (hasObjectness) {
                    // YOLOv5: confidence = objectness × max_class_score
                    float objectness = (float) detection.get(0, 4)[0];
                    confidence = objectness * maxClassScore;
                } else {
                    // YOLOv11: confidence = max_class_score
                    confidence = maxClassScore;
                }

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    // 验证类别ID有效性
                    if (classId < 0 || classId >= actualClassCount) {
                        System.err.println("警告: 无效的类别ID " + classId + ",跳过");
                        continue;
                    }

                    classIds.add(classId);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));


                }
            }
        }

        log.info("NMS前检测框数量: " + boxes2d.size());

        // 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indices_arr = indices.toArray();
        log.info("NMS后检测框数量: " + indices_arr.length);

        // ==========关键修正4: 坐标还原==========
        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        // 绘制结果
        int c = 0;
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);
            int classId = classIds.get(idx);
            float conf = confidences.get(idx);

            // 还原到原图坐标
            double x = (box.x - dx) / scale;
            double y = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;

            // 确保坐标在图像范围内
            x = Math.max(0, Math.min(x, image.cols() - 1));
            y = Math.max(0, Math.min(y, image.rows() - 1));
            width = Math.min(width, image.cols() - x);
            height = Math.min(height, image.rows() - y);

            System.out.println(String.format("最终检测框: %s %.3f [%.1f,%.1f,%.1f,%.1f]",
                    classes.get(classId), conf, x, y, width, height));

            Imgproc.rectangle(image,
                    new Point(x, y),
                    new Point(x + width, y + height),
                    CommonColors(c), 2);

            String label = classes.get(classId) + ": " + String.format("%.2f", conf);
            Imgproc.putText(image, label, new Point(x, y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 2);
            c++;
        }

        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }

        log.info("保存路径: {}", savepath);
        Imgcodecs.imwrite(savepath, image);

        Long b = System.currentTimeMillis();
        log.info("总耗时: {}ms", (b - a));

     //   return saveName + ".jpg";
        return  null;
    }

    // ==========新增letterbox预处理方法==========
    private static Mat letterboxResize(Mat image, int targetWidth, int targetHeight) {
        int originalWidth = image.cols();
        int originalHeight = image.rows();

        // 计算缩放比例
        double scale = Math.min((double) targetWidth / originalWidth, (double) targetHeight / originalHeight);

        // 计算新的尺寸
        int newWidth = (int) (originalWidth * scale);
        int newHeight = (int) (originalHeight * scale);

        // 缩放图像
        Mat resized = new Mat();
        Imgproc.resize(image, resized, new Size(newWidth, newHeight));

        // 创建目标尺寸的画布（灰色填充）
        Mat letterboxed = new Mat(targetHeight, targetWidth, image.type(), new Scalar(114, 114, 114));

        // 计算居中位置
        int dx = (targetWidth - newWidth) / 2;
        int dy = (targetHeight - newHeight) / 2;

        // 将缩放后的图像复制到画布中心
        Rect roi = new Rect(dx, dy, newWidth, newHeight);
        Mat roiMat = new Mat(letterboxed, roi);
        resized.copyTo(roiMat);

        return letterboxed;
    }

    // Sigmoid函数
    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    /***
     * AI模型嵌套模型
     * 需要绝对路径
     * 输入图片
     */
    public static String SendPicYoloV5Car(String weight, String names, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
        List<String> classes = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv5模型

        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromONNX(uploadpath + File.separator + weight);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        log.info("图片地址{}", image);

        Mat blob = Dnn.blobFromImage(image, 1 / 255.0, new Size(640, 640), new Scalar(0), true, false);
        net.setInput(blob);

        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = getOutputNames(net);
        net.forward(result, outBlobNames);
        System.out.println(Arrays.asList(outBlobNames));
        if (result.isEmpty()) {
            System.err.println("Failed to get output from the model.");
            return "error";
        }


        float confThreshold = 0.3f;
        float nmsThreshold = 0.4f;

        List<Rect2d> boxes2d = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();

        for (Mat output : result) {
            int dims = output.dims();
            int index = (int) output.size(0);
            int rows = (int) output.size(1);
            int cols = (int) output.size(2);
            // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
            System.out.println("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols + " row," + output.row(0) + "index:" + index);
            Mat detectionMat = output.reshape(1, output.size(1));

            for (int i = 0; i < detectionMat.rows(); i++) {
                Mat detection = detectionMat.row(i);
                Mat scores = detection.colRange(5, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float confidence = (float) detection.get(0, 4)[0];
                Point classIdPoint = minMaxResult.maxLoc;

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    float left = centerX - width / 2;
                    float top = centerY - height / 2;

                    classIds.add((int) classIdPoint.x);
                    confidences.add(confidence);
                    boxes2d.add(new Rect2d(left, top, width, height));
                    //  System.out.println("识别到了");
                }
            }
        }

// 应用非极大值抑制
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            System.out.println("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }
        int c = 0;
        int[] indices_arr = indices.toArray();
        for (int idx : indices_arr) {
            Rect2d box = boxes2d.get(idx);

            int classId = classIds.get(idx);
            float conf = confidences.get(idx);
            double x = box.x;
            double y = box.y;
            double width = box.width * ((double) image.cols() / 640);
            double height = box.height * ((double) image.rows() / 640);
            double xzb = x * ((double) image.cols() / 640);
            double yzb = y * ((double) image.rows() / 640);
            System.out.println("绘制1" + "x:" + x + "y:" + y + "");
            System.out.println("绘制1" + "width:" + width + "height:" + height + "");
            System.out.println(" image.cols()" + Double.valueOf((double) image.cols() / 640));
            System.out.println(" image.rows()" + Double.valueOf((double) image.rows() / 640));

            Rect rect = new Rect((int) Math.round(xzb), (int) Math.round(yzb), (int) Math.round(width), (int) Math.round(height));
            Mat plaateimage = Imgcodecs.imread(uploadpath + File.separator + picUrl);
            Mat plateMat = new Mat(plaateimage, rect);


            String plateNumber = extractPlateNumber(plateMat);
            String plateColor = recognizePlateColor(plateMat);

            System.out.println("车牌号码: " + plateNumber);
            System.out.println("车牌颜色: " + plateColor);

            // 在原图上绘制检测结果
            Imgproc.rectangle(image, rect, new Scalar(0, 255, 0), 2);
            Imgproc.putText(image, plateNumber, new Point(rect.x, rect.y - 10),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, new Scalar(0, 255, 0), 2);


            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }

    private static String recognizePlateColor(Mat plateMat) {
        Mat hsvMat = new Mat();
        Imgproc.cvtColor(plateMat, hsvMat, Imgproc.COLOR_BGR2HSV);

        Scalar blueMin = new Scalar(100, 100, 100);
        Scalar blueMax = new Scalar(140, 255, 255);
        Scalar yellowMin = new Scalar(20, 100, 100);
        Scalar yellowMax = new Scalar(30, 255, 255);
        Scalar greenMin = new Scalar(40, 100, 100);
        Scalar greenMax = new Scalar(80, 255, 255);
        Scalar whiteMin = new Scalar(0, 0, 200);
        Scalar whiteMax = new Scalar(180, 30, 255);

        Mat blueMask = new Mat();
        Mat yellowMask = new Mat();
        Mat greenMask = new Mat();
        Mat whiteMask = new Mat();

        Core.inRange(hsvMat, blueMin, blueMax, blueMask);
        Core.inRange(hsvMat, yellowMin, yellowMax, yellowMask);
        Core.inRange(hsvMat, greenMin, greenMax, greenMask);
        Core.inRange(hsvMat, whiteMin, whiteMax, whiteMask);

        int bluePixels = Core.countNonZero(blueMask);
        int yellowPixels = Core.countNonZero(yellowMask);
        int greenPixels = Core.countNonZero(greenMask);
        int whitePixels = Core.countNonZero(whiteMask);

        int maxPixels = Math.max(Math.max(bluePixels, yellowPixels), Math.max(greenPixels, whitePixels));

        if (maxPixels == bluePixels) return "蓝色";
        if (maxPixels == yellowPixels) return "黄色";
        if (maxPixels == greenPixels) return "绿色";
        if (maxPixels == whitePixels) return "白色";
        return "未知";
    }

    //读取文字
    private static String extractPlateNumber(Mat plateMat) {
        // 预处理
        Mat gray = new Mat();
        Mat binary = new Mat();
        Imgproc.cvtColor(plateMat, gray, Imgproc.COLOR_BGR2GRAY);
        Imgproc.threshold(gray, binary, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);

        // 查找轮廓
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        // 筛选可能的字符轮廓
        List<Rect> charBounds = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            Rect rect = Imgproc.boundingRect(contour);
            if (isValidCharSize(rect, binary.size())) {
                charBounds.add(rect);
            }
        }

        // 按x坐标排序
        charBounds.sort(Comparator.comparingInt(r -> r.x));
        CharRecognizer recognizer = new CharRecognizer();
        // 识别每个字符
        StringBuilder plateNumber = new StringBuilder();
        int a = 0;
        for (Rect charRect : charBounds) {
            Mat charMat = new Mat(binary, charRect);
            char recognizedChar;
            if (a == 0) {
                recognizedChar = recognizer.recognizeChineseChar(charMat);
            } else {
                recognizedChar = recognizer.recognizeChar(charMat);
            }
            a++;

            plateNumber.append(recognizedChar);
        }

        return plateNumber.toString();
    }


    private static boolean isValidCharSize(Rect rect, Size plateSize) {
        double charAspectRatio = (double) rect.height / rect.width;
        double charHeightRatio = (double) rect.height / plateSize.height;
        return charAspectRatio > 1.0 && charAspectRatio < 4.0 &&
                charHeightRatio > 0.4 && charHeightRatio < 0.9;
    }

    private static List<String> getOutputNames(Net net) {
        List<String> names = new ArrayList<>();
        List<Integer> outLayers = net.getUnconnectedOutLayers().toList();
        List<String> layersNames = net.getLayerNames();
        outLayers.forEach(i -> names.add(layersNames.get(i - 1)));
        return names;
    }

    /***
     * AI模型嵌套模型
     * 需要绝对路径
     * 输入图片
     */
    public static String SendPicYoloV3(String weight, String cfg, String names, String picUrl, String saveName, String uploadpath) throws Exception {
        log.info(uploadpath);
        Long a = System.currentTimeMillis();
        // 加载类别名称
        List<String> classNames = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv3模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromDarknet(uploadpath + File.separator + cfg, uploadpath + File.separator + weight);
//        net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
//        net.setPreferableBackend(Dnn.DNN_TARGET_CUDA);
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);
        // 读取输入图像
        log.info("图片地址{}", uploadpath + File.separator + picUrl);
        Mat image = Imgcodecs.imread(uploadpath + File.separator + picUrl);
        // 将图像传递给模型进行目标检测
        Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);

        net.setInput(blob);
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.5f;
        List<Rect2d> boundingBoxes = new ArrayList<>();
        List<Float> confidences = new ArrayList<>();
        List<Integer> classIds = new ArrayList<>();
        for (Mat level : result) {
            for (int i = 0; i < level.rows(); ++i) {
                Mat row = level.row(i);
                Mat scores = level.row(i).colRange(5, level.cols());
                Core.MinMaxLocResult minMaxLocResult = Core.minMaxLoc(scores);
                Point classIdPoint = minMaxLocResult.maxLoc;
                double confidence = row.get(0, 4)[0];
                if (confidence > confThreshold) {
                    //    log.info("classIdPoint"+ classIdPoint);
                    //    log.info("classIdPointx"+ classIdPoint.x);
                    classIds.add((int) classIdPoint.x); //记录标签下标
                    double centerX = row.get(0, 0)[0] * image.cols();
                    double centerY = row.get(0, 1)[0] * image.rows();
                    double width = row.get(0, 2)[0] * image.cols();
                    double height = row.get(0, 3)[0] * image.rows();
                    double left = centerX - width / 2;
                    double top = centerY - height / 2;
                    // 绘制边界框
                    Rect2d rect = new Rect2d(left, top, width, height);
                    boundingBoxes.add(rect);
                    confidences.add((float) confidence);
                }
            }
        }

        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes = new MatOfRect2d(boundingBoxes.toArray(new Rect2d[0]));
        MatOfFloat confidencesMat = new MatOfFloat();
        confidencesMat.fromList(confidences);
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes, confidencesMat, confThreshold, 0.4f, indices);
        if (indices.empty()) {
            log.info("类别下标啊" + "未识别到内容");
            return "error";
        }
        int[] indicesArray = indices.toArray();
        // 获取保留的边界框

        log.info(confidences.size() + "类别下标啊" + indicesArray.length);
        // 在图像上绘制保留的边界框
        int c = 0;
        for (int idx : indicesArray) {
            Rect2d box = boundingBoxes.get(idx);

            System.out.println("绘制111111" + "x:" + box.x + "y:" + box.y + "");
            System.out.println("绘制11111111" + "width:" + box.width + "y:" + box.height + "");
            Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height), CommonColors(c), 2);
            // 添加类别标签
            log.info("当前有多少" + confidences.get(idx));
            Integer ab = classIds.get(idx);
            log.info("类别下标" + ab);
            //  AIModelYolo3.addChineseText(image, caption,new Point(box.x, box.y - 5));
            Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }
        String savepath = uploadpath + File.separator + "temp" + File.separator;

        if (StringUtils.isNotBlank(saveName)) {
            savepath += saveName + ".jpg";
        } else {
            saveName = System.currentTimeMillis() + "";
            savepath += saveName + ".jpg";
        }
        log.info(savepath);
        Imgcodecs.imwrite(savepath, image);
        Long b = System.currentTimeMillis();
        log.info("消耗时间：" + (b - a));
        return saveName + ".jpg";
    }

    /***
     * AI识别输出图片或者视频帧
     * @param weight
     * @param cfg
     * @param names
     * @param videoUrl
     * @param uploadpath
     * @return
     * @throws Exception
     */
    public String SendVideoYoloV3(String weight, String cfg, String names, String videoUrl, String uploadpath) throws Exception {
        Long a = System.currentTimeMillis();

        // 加载类别名称
        List<String> classNames = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv3模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromDarknet(uploadpath + File.separator + cfg, uploadpath + File.separator + weight);
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);

//        net.setPreferableBackend(Dnn.DNN_BACKEND_CUDA);
//        net.setPreferableTarget(Dnn.DNN_TARGET_CUDA);

        String savepath = uploadpath + File.separator + a + File.separator;
        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();// 创建文件根目录
        }
        VideoCapture videoCapture = new VideoCapture(videoUrl);
        if (!videoCapture.isOpened()) {
            log.info("未能正确打开视频");
            return "eror";
        }

        // 设置输出视频文件参数
        int frameWidth = (int) videoCapture.get(Videoio.CAP_PROP_FRAME_WIDTH);
        int frameHeight = (int) videoCapture.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        //   VideoWriter videoWriter = new VideoWriter("F:\\JAVAAI\\output.mp4", VideoCodec.MPEG4, 30, new Size(frameWidth, frameHeight), true);
        VideoWriter videoWriter = new VideoWriter("F:\\JAVAAI\\output.mp4", VideoWriter.fourcc('X', '2', '6', '4'), 30, new Size(frameWidth, frameHeight), true);

        Mat frame = new Mat();

        while (videoCapture.read(frame)) {
            Long b = System.currentTimeMillis();
            // 将图像传递给模型进行目标检测
            Mat blob = Dnn.blobFromImage(frame, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);
            net.setInput(blob);
            // 将图像传递给模型进行目标检测
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // 处理检测结果
            float confThreshold = 0.5f;
            List<Rect2d> boundingBoxes = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();
            for (Mat level : result) {
                for (int i = 0; i < level.rows(); ++i) {
                    Mat row = level.row(i);
                    Mat scores = level.row(i).colRange(5, level.cols());
                    Core.MinMaxLocResult minMaxLocResult = Core.minMaxLoc(scores);
                    Point classIdPoint = minMaxLocResult.maxLoc;
                    double confidence = row.get(0, 4)[0];
                    if (confidence > confThreshold) {
                        //    log.info("classIdPoint"+ classIdPoint);
                        //    log.info("classIdPointx"+ classIdPoint.x);
                        classIds.add((int) classIdPoint.x); //记录标签下标
                        double centerX = row.get(0, 0)[0] * frame.cols();
                        double centerY = row.get(0, 1)[0] * frame.rows();
                        double width = row.get(0, 2)[0] * frame.cols();
                        double height = row.get(0, 3)[0] * frame.rows();
                        double left = centerX - width / 2;
                        double top = centerY - height / 2;
                        // 绘制边界框
                        Rect2d rect = new Rect2d(left, top, width, height);
                        boundingBoxes.add(rect);
                        confidences.add((float) confidence);

                    }
                }
            }

            // 执行非最大抑制，消除重复的边界框
            MatOfRect2d boxes = new MatOfRect2d(boundingBoxes.toArray(new Rect2d[0]));
            MatOfFloat confidencesMat = new MatOfFloat();
            confidencesMat.fromList(confidences);
            MatOfInt indices = new MatOfInt();
            Dnn.NMSBoxes(boxes, confidencesMat, confThreshold, 0.4f, indices);
            if (indices.empty()) {
                return "error";
            }
            int[] indicesArray = indices.toArray();
            // 获取保留的边界框

            log.info(confidences.size() + "类别下标啊" + indicesArray.length);
            // 在图像上绘制保留的边界框
            int c = 0;
            for (int idx : indicesArray) {
                Rect2d box = boundingBoxes.get(idx);
                Imgproc.rectangle(frame, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height), CommonColors(c), 2);
                // 添加类别标签
                log.info("当前有多少" + confidences.get(idx));
                Integer ab = classIds.get(idx);
                log.info("类别下标" + ab);
                //  AIModelYolo3.addChineseText(image, caption,new Point(box.x, box.y - 5));
                Imgproc.putText(frame, classNames.get(ab), new Point(box.x, box.y - 5), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
                c++;
                videoWriter.write(frame);
            }

            String saveName = "";
            if (StringUtils.isNotBlank(saveName)) {
                saveName = savepath + saveName + ".jpg";
            } else {
                saveName = savepath + System.currentTimeMillis() + ".jpg";
            }

            // Imgcodecs.imwrite(saveName, frame);


            //       imshow("YOLOv3 Detection", frame);
            long d = (b - a) / 1000;
            if (d > 60) {
                break;
            }
            log.info(saveName + "{}", d);
        }
        // Release resources
        videoCapture.release();
        videoWriter.release();
        return "";

    }


    /***
     * 测试合成是频liu
     *
     */
    public void SendTestVideo() {
        VideoCapture capture = new VideoCapture();
        long a = System.currentTimeMillis();
        capture.open("http://218.92.168.230:8888/LL/34020000001180000002_34020000001310000005.live.mp4");

        // Check if the capture is opened successfully
        if (!capture.isOpened()) {
            System.out.println("Error: Could not open video stream");
            return;
        }

        // Get video properties
        double frameWidth = capture.get(Videoio.CAP_PROP_FRAME_WIDTH);
        double frameHeight = capture.get(Videoio.CAP_PROP_FRAME_HEIGHT);
        double fps = capture.get(Videoio.CAP_PROP_FPS);

        // Create VideoWriter object to write frames to a file
        String outputFileName = "F:\\JAVAAI\\output_video.mp4"; // Change this to your desired output file name
        int fourcc = VideoWriter.fourcc('X', '2', '6', '4');
        VideoWriter writer = new VideoWriter(outputFileName, fourcc, fps, new Size((int) frameWidth, (int) frameHeight));

        // Process frames from the video stream
        Mat frame = new Mat();
        while (capture.read(frame)) {
            long b = System.currentTimeMillis();
            // Write the frame to the output file
            long c = (b - a) / 1000;
            log.info("运行中{}", c);
            writer.write(frame);
            if (c > 60) {
                break;
            }

        }

        // Release resources
        capture.release();
        writer.release();
    }

    /**
     * 获取坐标
     *
     * @return
     */
    public String SendVideoLocalhostYoloV3(String userId, String weight, String cfg, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil) throws Exception {
        Long a = System.currentTimeMillis();

        // 加载类别名称   人 1
        List<String> classNames = Files.readAllLines(Paths.get(uploadpath + File.separator + names));
        // 加载YOLOv3模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        Net net = Dnn.readNetFromDarknet(uploadpath + File.separator + cfg, uploadpath + File.separator + weight);
        //    onnx  pt darknet
        net.setPreferableBackend(Dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(Dnn.DNN_TARGET_CPU);

        VideoCapture videoCapture = new VideoCapture(videoUrl);
        if (!videoCapture.isOpened()) {
            log.info("未能正确打开视频");
            return "eror";
        }
        double fps = videoCapture.get(Videoio.CAP_PROP_FPS);
        // 计算每帧的时间消耗（单位：毫秒）
        double frameTime = 1000 / fps; // 单位为毫秒
        log.info("");


        log.info("当前视频帧数{}", fps);
        Mat frame = new Mat();
        int k = 0;
        while (videoCapture.read(frame)) {


            Long startTime = System.currentTimeMillis();
            Boolean flag = (Boolean) redisUtil.get(videoUrl + "" + userId);
            log.info("获取的当前识别信息{}{}{}", videoUrl, userId, flag);
            if (!flag) {
                videoCapture.release();
                break;
            }
            // 将图像传递给模型进行目标检测 1280*1080  720p
            Mat blob = Dnn.blobFromImage(frame, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);
            net.setInput(blob);
            // 将图像传递给模型进行目标检测
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // 处理检测结果
            float confThreshold = 0.5f; //80 70 60%   （1000*1000） 3-5天  50%
            List<Rect2d> boundingBoxes = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();
            for (Mat level : result) {
                for (int i = 0; i < level.rows(); ++i) {
                    Mat row = level.row(i);
                    Mat scores = level.row(i).colRange(5, level.cols());
                    Core.MinMaxLocResult minMaxLocResult = Core.minMaxLoc(scores);
                    Point classIdPoint = minMaxLocResult.maxLoc;
                    double confidence = row.get(0, 4)[0];
                    if (confidence > confThreshold) {
                        //    log.info("classIdPoint"+ classIdPoint);
                        //    log.info("classIdPointx"+ classIdPoint.x);
                        classIds.add((int) classIdPoint.x); //记录标签下标
                        double centerX = row.get(0, 0)[0] * frame.cols(); // xy
                        double centerY = row.get(0, 1)[0] * frame.rows();
                        double width = row.get(0, 2)[0] * frame.cols();
                        double height = row.get(0, 3)[0] * frame.rows();
                        double left = centerX - width / 2;
                        double top = centerY - height / 2;

                        // 绘制边界框
                        Rect2d rect = new Rect2d(left, top, width, height);
                        boundingBoxes.add(rect);
                        confidences.add((float) confidence);

                    }
                }
            }

            // 执行非最大抑制，消除重复的边界框 第一高度
            MatOfRect2d boxes = new MatOfRect2d(boundingBoxes.toArray(new Rect2d[0]));
            MatOfFloat confidencesMat = new MatOfFloat();
            confidencesMat.fromList(confidences);
            MatOfInt indices = new MatOfInt();
            Dnn.NMSBoxes(boxes, confidencesMat, confThreshold, 0.4f, indices);
            if (indices.empty()) {
                log.info("未识别到视频内容");
                continue;
            }
            int[] indicesArray = indices.toArray();
            //   // 获取保留的边界框

            log.info(confidences.size() + "类别下标啊" + indicesArray.length);
            // 在图像上绘制保留的边界框
            int c = 0;
            JSONObject bja = new JSONObject();
            List<JSONObject> jsonlist = new ArrayList<>();
            for (int idx : indicesArray) {
                Rect2d box = boundingBoxes.get(idx);
                //    Imgproc.rectangle(frame, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
                // 添加类别标签
                log.info("当前有多少" + confidences.get(idx));
                Integer ab = classIds.get(idx);
                log.info("类别下标" + ab);
                //  AIModelYolo3.addChineseText(image, caption,new Point(box.x, box.y - 5))
                log.info("Detected object at: (" + box.x + ", " + box.y + "),width: (" + box.width + ", " + box.height + ")");
                //   Imgproc.putText(frame, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);

                bja.put("cmd", "video");
                JSONObject bj = new JSONObject();
                bj.put("x", box.x);
                bj.put("y", box.y);
                bj.put("width", box.width);
                bj.put("height", box.height);
                bj.put("url", videoUrl);
                bj.put("name", classNames.get(ab));
                bj.put("color", CommonColorsVue(c));
                jsonlist.add(bj);
                bja.put("list", jsonlist);
                c++;

            }


            // 计算跳过的帧数（根据所需的时间消耗）
            webSocket.sendMessage(bja.toJSONString());

            Long b = System.currentTimeMillis();
            long consumingTime = 0;
            if (k == 0) {
                consumingTime = (b - a);
                k++;
            } else {
                consumingTime = (b - startTime);
            }
            int framesToSkip = (int) (consumingTime / frameTime);

            log.warn("耗时时间{},跳过帧数{}", b - startTime, framesToSkip);
            // 跳过计算出的帧数
//            for (int i = 0; i < framesToSkip; i++) {
//                videoCapture.grab(); // 跳过帧
//            }

            Mat newmat = new Mat();
            VideoCapture videoCapture2 = new VideoCapture(videoUrl);
            videoCapture2.read(newmat);
            videoCapture.release();
            long timestamp2 = (long) videoCapture2.get(Videoio.CAP_PROP_POS_MSEC);
            videoCapture = videoCapture2;


            long timestamp = (long) videoCapture.get(Videoio.CAP_PROP_POS_MSEC);
            log.warn("当前帧时间" + (millisecondsToHours(timestamp)));
            log.warn("最新帧时间" + (millisecondsToHours(timestamp2)));
        }

        return "";

    }

    /**
     * 多线程处理视频帧
     *
     * @param
     * @return
     */
    public String SendVideoLocalhostYoloV3Thread(String userId, String weight, String cfg, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil, RedisTemplate redisTemplate) throws Exception {
        Long a = System.currentTimeMillis();


        // 加载YOLOv3模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        log.info("names{}", uploadpath + File.separator + names);
        // 计算每帧的时间消耗（单位：毫秒）
        int maxIdleThreads = Runtime.getRuntime().availableProcessors();
        log.info("当前主机最大空闲线程数：" + maxIdleThreads);
        // 创建线程池
        ExecutorService executor = Executors.newCachedThreadPool();
        VideoSendReadCfg.StartTime = 0;
        log.info("videoUrl：" + videoUrl);
        // 提交多个任务到线程池
        for (int i = 0; i < 3; i++) {
            //效果延迟了三秒
            //     executor.submit(new VideoFrameReader(videoUrl,uploadpath+ File.separator +weight,uploadpath+ File.separator +cfg,uploadpath+ File.separator +names,redisUtil,webSocket,userId,i,redisTemplate));
            if (i == 0) {
                executor.submit(new VideoRead(videoUrl, redisTemplate, userId));
            } else if (i == 1) {
                executor.submit(new VideoReadInfo(videoUrl, redisTemplate, userId, uploadpath + File.separator + names, uploadpath + File.separator + cfg, uploadpath + File.separator + weight, webSocket));
            } else {
                executor.submit(new VideoReadtest(videoUrl, redisTemplate, userId));
            }
            Thread.sleep(500);
        }
        // 关闭线程池
        executor.shutdown();


        return "";

    }

    public String SendVideoLocalhostYoloV11Thread(NetPush netpush,TabAudioDevice tabAudioDevice, TabAiModelBund tabAiModelBund, String userId, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil, RedisTemplate redisTemplate) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        log.info("开始识别onnx");
        executor.submit(new VideoReadOnnx(netpush,tabAudioDevice, tabAiModelBund, videoUrl, redisTemplate, userId, uploadpath + File.separator + names,  webSocket));

        return "";
    }

    /***
     * 姿态
     * @param netpush
     * @param tabAudioDevice
     * @param tabAiModelBund
     * @param userId
     * @param names
     * @param videoUrl
     * @param uploadpath
     * @param webSocket
     * @param redisUtil
     * @param redisTemplate
     * @return
     * @throws Exception
     */
    public String SendVideoLocalhostYoloV11ThreadPose(NetPush netpush,TabAudioDevice tabAudioDevice, TabAiModelBund tabAiModelBund, String userId, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil, RedisTemplate redisTemplate) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        log.info("开始识别onnx");
        executor.submit(new VideoReadOnnxPose(netpush,tabAudioDevice, tabAiModelBund, videoUrl, redisTemplate, userId, uploadpath + File.separator + names,  webSocket));

        return "";
    }
        /**
         * 多线程处理视频帧
         *
         * @param
         * @return
         */
    public String SendVideoLocalhostYoloV5Thread(TabAudioDevice tabAudioDevice, TabAiModelBund tabAiModelBund, String userId, String weight, String cfg, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil, RedisTemplate redisTemplate) throws Exception {
        Long a = System.currentTimeMillis();


        // 加载v5/v8模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        log.info("names{}", uploadpath + File.separator + names);
        // 计算每帧的时间消耗（单位：毫秒）
        int maxIdleThreads = Runtime.getRuntime().availableProcessors();
        log.info("当前主机最大空闲线程数：" + maxIdleThreads);
        // 创建线程池
        ExecutorService executor = Executors.newCachedThreadPool();
        VideoSendReadCfg.StartTime = 0;
        log.info("videoUrl：" + videoUrl);


        // 提交多个任务到线程池
        for (int i = 0; i < 3; i++) {
            //效果延迟了三秒
            //     executor.submit(new VideoFrameReader(videoUrl,uploadpath+ File.separator +weight,uploadpath+ File.separator +cfg,uploadpath+ File.separator +names,redisUtil,webSocket,userId,i,redisTemplate));
            if (i == 0) {
                executor.submit(new VideoReadV5(videoUrl, redisTemplate, userId));
            } else if (i == 1) {
                executor.submit(new VideoReadInfoV5(tabAudioDevice, tabAiModelBund, videoUrl, redisTemplate, userId, uploadpath + File.separator + names, uploadpath + File.separator + cfg, uploadpath + File.separator + weight, webSocket));
            } else {
                executor.submit(new VideoReadtestV5(videoUrl, redisTemplate, userId));
            }
            Thread.sleep(3000);
        }
        // 关闭线程池
        // executor.shutdown();


        return "";

    }


    /**
     * 多线程处理视频帧区域入侵
     *
     * @param
     * @return
     */
    public String SendVideoLocalhostYoloV5ThreadVideoUtil(TabVideoUtil tabVideoUtil, String weight, String cfg, String names, String videoUrl, String uploadpath, WebSocket webSocket, RedisUtil redisUtil, RedisTemplate redisTemplate) throws Exception {
        Long a = System.currentTimeMillis();


        // 加载v5/v8模型
        log.info("cfg地址{}", uploadpath + File.separator + cfg);
        log.info("weight地址{}", uploadpath + File.separator + weight);
        log.info("names{}", uploadpath + File.separator + names);
        // 计算每帧的时间消耗（单位：毫秒）
        int maxIdleThreads = Runtime.getRuntime().availableProcessors();
        log.info("当前主机最大空闲线程数：" + maxIdleThreads);
        // 创建线程池
        ExecutorService executor = Executors.newCachedThreadPool();
        VideoSendReadCfg.StartTime = 0;
        log.info("videoUrl：" + videoUrl);
        // 提交多个任务到线程池
        for (int i = 0; i < 3; i++) {
            //效果延迟了三秒
            //     executor.submit(new VideoFrameReader(videoUrl,uploadpath+ File.separator +weight,uploadpath+ File.separator +cfg,uploadpath+ File.separator +names,redisUtil,webSocket,userId,i,redisTemplate));
            if (i == 0) {
                executor.submit(new VideoReadV5Util(tabVideoUtil, videoUrl, redisTemplate));
            } else if (i == 1) {
                executor.submit(new VideoReadInfoV5Util(tabVideoUtil, videoUrl, redisTemplate, uploadpath + File.separator + names, uploadpath + File.separator + cfg, uploadpath + File.separator + weight, webSocket));
            } else {
                executor.submit(new VideoReadtestV5Util(tabVideoUtil, videoUrl, redisTemplate));
            }
            Thread.sleep(1000);
        }
        // 关闭线程池
        // executor.shutdown();


        return "";

    }

    /***
     * 带线程推送
     *
     * @return
     */
    public void SendPicThread(RedisTemplate redisTemplate, String uploadPath) {
        List<PushInfo> pushA = (List<PushInfo>) redisTemplate.opsForValue().get("sendPush");
        ExecutorService executor = Executors.newCachedThreadPool();
        for (PushInfo pushInfo : pushA) {
            log.info("当前属性内容{}", pushInfo.getName());

            executor.submit(new VideoReadPic(pushInfo, uploadPath, redisTemplate));
        }


    }


    /**
     * 图片转base64
     *
     * @param imagePath
     * @return
     */
    public static String base64Image(String imagePath) {
        try {
            // 读取图片文件
            File file = new File(imagePath);
            byte[] bytesArray = new byte[(int) file.length()];
            FileInputStream fis = new FileInputStream(file);
            fis.read(bytesArray); // 读取文件内容到字节数组
            fis.close();

            // 将字节数组编码为Base64字符串
            String base64String = Base64.getEncoder().encodeToString(bytesArray);

            return base64String;
        } catch (IOException e) {
            e.printStackTrace();
            return "图片解析错误";
        }

    }

    public static String millisecondsToHours(long milliseconds) {
        // 将毫秒转换为小时、分钟和秒
        long hours = milliseconds / (1000 * 60 * 60);
        long minutes = (milliseconds % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = ((milliseconds % (1000 * 60 * 60)) % (1000 * 60)) / 1000;

        // 构造结果字符串
        String result = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        return result;
    }


    public static Mat addChineseText(Mat images, String text, Point position, Scalar scalar) {
        BufferedImage bufferedImage = matToBufferedImage(images);
        Graphics graphics = bufferedImage.getGraphics();

        // 设置中文文本字体
        Font chineseFont = new Font("微软雅黑", Font.PLAIN, 26);
        graphics.setFont(chineseFont);
        Color awtColor = convertScalarToColor(scalar);
        // 设置文本颜色
        graphics.setColor(awtColor);

        // 在指定位置绘制中文文本
        graphics.drawString(text, (int) position.x, (int) position.y);

        // 将修改后的图像转换回OpenCV的Mat对象
        return bufferedImageToMat(bufferedImage);
    }

    private static Color convertScalarToColor(Scalar scalarColor) {
        double[] rgb = scalarColor.val;
        int r = (int) rgb[2];
        int g = (int) rgb[1];
        int b = (int) rgb[0];
        return new Color(r, g, b);
    }

    // 将Mat对象转换为BufferedImage对象
    public static BufferedImage matToBufferedImage(Mat matrix) {
        int type = BufferedImage.TYPE_BYTE_GRAY;
        if (matrix.channels() > 1) {
            type = BufferedImage.TYPE_3BYTE_BGR;
        }
        int bufferSize = matrix.channels() * matrix.cols() * matrix.rows();
        byte[] buffer = new byte[bufferSize];
        matrix.get(0, 0, buffer);
        BufferedImage image = new BufferedImage(matrix.cols(), matrix.rows(), type);
        final byte[] targetPixels = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(buffer, 0, targetPixels, 0, buffer.length);
        return image;
    }

    // 将BufferedImage对象转换为Mat对象
    public static Mat bufferedImageToMat(BufferedImage image) {
        byte[] pixels = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, pixels);
        return mat;
    }


    /***
     * 保存线上图片到本地
     * @param imageUrl
     * @return
     */
    public String SavePicInLocalhost(String imageUrl, String path) {
        System.load("F:\\JAVAAI\\opencv481\\opencv\\build\\java\\x64\\opencv_java481.dll");
        try {
            String uuid = System.currentTimeMillis() + "";
            File dir = new File(path + File.separator);
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    dir.mkdirs();// 创建文件根目录
                }
            }

            // 打开连接
            URL url = new URL(imageUrl);
            URLConnection connection = url.openConnection();
            // 设置请求超时为15秒
            connection.setConnectTimeout(15 * 1000);
            // 读取数据流并保存到本地
            InputStream input = connection.getInputStream();
            byte[] datas = new byte[2048];
            int len;
            FileOutputStream output = new FileOutputStream(new File(dir, uuid + ".jpg"));
            while ((len = input.read(datas)) != -1) {
                output.write(datas, 0, len);
            }
            output.close();
            input.close();
            log.info("图片保存成功：" + dir + uuid + ".jpg");
            return uuid + ".jpg";
        } catch (IOException e) {

            log.info("图片保存失败：" + e.getMessage());
            return "error";
        }

    }

    public static Scalar CommonColors(int i) {
        Scalar[] commonColors = {
                new Scalar(255, 0, 0),     // 蓝色
                new Scalar(0, 255, 0),     // 绿色
                new Scalar(0, 0, 255),     // 红色
                new Scalar(255, 255, 0),   // 黄色
                new Scalar(0, 255, 255),   // 青色
                new Scalar(255, 0, 255),   // 粉色
                new Scalar(255, 255, 255), // 白色
                new Scalar(0, 0, 0),        // 黑色
                new Scalar(128, 128, 128), // 灰色
                new Scalar(250, 128, 114), // 三文鱼
                new Scalar(240, 128, 128), // 珊瑚
                new Scalar(255, 99, 71), // 番茄
                new Scalar(124, 252, 0), // 草坪绿
                new Scalar(72, 209, 204), // 绿松石色
                new Scalar(0, 206, 209), // 深蓝绿色
                new Scalar(65, 105, 225), // 宝蓝色
                new Scalar(255, 0, 255), // 紫红色
                new Scalar(255, 0, 0),     // 蓝色
                new Scalar(0, 255, 0),     // 绿色
                new Scalar(0, 0, 255),     // 红色
                new Scalar(255, 255, 0),   // 黄色
                new Scalar(0, 255, 255),   // 青色
                new Scalar(255, 0, 255),   // 粉色
                new Scalar(255, 255, 255), // 白色
                new Scalar(0, 0, 0),        // 黑色
                new Scalar(128, 128, 128), // 灰色
                new Scalar(250, 128, 114), // 三文鱼
                new Scalar(240, 128, 128), // 珊瑚
                new Scalar(255, 99, 71), // 番茄
                new Scalar(124, 252, 0), // 草坪绿
                new Scalar(72, 209, 204), // 绿松石色
                new Scalar(0, 206, 209), // 深蓝绿色
                new Scalar(65, 105, 225), // 宝蓝色
                new Scalar(255, 0, 255), // 紫红色
                new Scalar(255, 0, 0),     // 蓝色
                new Scalar(0, 255, 0),     // 绿色
                new Scalar(0, 0, 255),     // 红色
                new Scalar(255, 255, 0),   // 黄色
                new Scalar(0, 255, 255),   // 青色
                new Scalar(255, 0, 255),   // 粉色
                new Scalar(255, 255, 255), // 白色
                new Scalar(0, 0, 0),        // 黑色
                new Scalar(128, 128, 128), // 灰色
                new Scalar(250, 128, 114), // 三文鱼
                new Scalar(240, 128, 128), // 珊瑚
                new Scalar(255, 99, 71), // 番茄
                new Scalar(124, 252, 0), // 草坪绿
                new Scalar(72, 209, 204), // 绿松石色
                new Scalar(0, 206, 209), // 深蓝绿色
                new Scalar(65, 105, 225), // 宝蓝色
                new Scalar(255, 0, 255), // 紫红色
                // 添加更多的颜色...
        };
        if (i >= commonColors.length) {
            i = 0;
        }
        return commonColors[i];
    }

    public static String CommonColorsVue(int i) {
        String[] commonColors = {
                "#0000FF",     // 蓝色
                "#00FF00",     // 绿色
                "#FF0000",     // 红色
                "#FFFF00",   // 黄色
                "#00FFFF",   // 青色
                "#FFC0CB",   // 粉色
                "#FFFFFF", // 白色
                "#000000",        // 黑色
                "#808080", // 灰色
                "#FA8072", // 三文鱼
                "#FF7F50", // 珊瑚
                "#FF6347", // 番茄
                "#7CFC00", // 草坪绿
                "#48D1CC", // 绿松石色
                "#00CED1", // 深蓝绿色
                "#4169E1", // 宝蓝色
                "#FF00FF", // 紫红色
                "#0000FF",     // 蓝色
                "#00FF00",     // 绿色
                "#FF0000",     // 红色
                "#FFFF00",   // 黄色
                "#00FFFF",   // 青色
                "#FFC0CB",   // 粉色
                "#FFFFFF", // 白色
                "#000000",        // 黑色
                "#808080", // 灰色
                "#FA8072", // 三文鱼
                "#FF7F50", // 珊瑚
                "#FF6347", // 番茄
                "#7CFC00", // 草坪绿
                "#48D1CC", // 绿松石色
                "#00CED1", // 深蓝绿色
                "#4169E1", // 宝蓝色
                "#FF00FF", // 紫红色
                "#0000FF",     // 蓝色
                "#00FF00",     // 绿色
                "#FF0000",     // 红色
                "#FFFF00",   // 黄色
                "#00FFFF",   // 青色
                "#FFC0CB",   // 粉色
                "#FFFFFF", // 白色
                "#000000",        // 黑色
                "#808080", // 灰色
                "#FA8072", // 三文鱼
                "#FF7F50", // 珊瑚
                "#FF6347", // 番茄
                "#7CFC00", // 草坪绿
                "#48D1CC", // 绿松石色
                "#00CED1", // 深蓝绿色
                "#4169E1", // 宝蓝色
                "#FF00FF", // 紫红色
                "#0000FF",     // 蓝色
                "#00FF00",     // 绿色
                "#FF0000",     // 红色
                "#FFFF00",   // 黄色
                "#00FFFF",   // 青色
                "#FFC0CB",   // 粉色
                "#FFFFFF", // 白色
                "#000000",        // 黑色
                "#808080", // 灰色
                "#FA8072", // 三文鱼
                "#FF7F50", // 珊瑚
                "#FF6347", // 番茄
                "#7CFC00", // 草坪绿
                "#48D1CC", // 绿松石色
                "#00CED1", // 深蓝绿色
                "#4169E1", // 宝蓝色
                "#FF00FF", // 紫红色
                "#0000FF",     // 蓝色
                "#00FF00",     // 绿色
                "#FF0000",     // 红色
                "#FFFF00",   // 黄色
                "#00FFFF",   // 青色
                "#FFC0CB",   // 粉色
                "#FFFFFF", // 白色
                "#000000",        // 黑色
                "#808080", // 灰色
                "#FA8072", // 三文鱼
                "#FF7F50", // 珊瑚
                "#FF6347", // 番茄
                "#7CFC00", // 草坪绿
                "#48D1CC", // 绿松石色
                "#00CED1", // 深蓝绿色
                "#4169E1", // 宝蓝色
                "#FF00FF", // 紫红色


                // 添加更多的颜色...
        };
        if (i >= commonColors.length) {
            i = 0;
        }
        return commonColors[i];
    }

    // 生成指定数量的随机颜色
    private static Scalar[] generateRandomColors(int count) {
        Scalar[] colors = new Scalar[count];
        for (int i = 0; i < count; i++) {
            int r = (int) (Math.random() * 256);
            int g = (int) (Math.random() * 256);
            int b = (int) (Math.random() * 256);
            colors[i] = new Scalar(b, g, r);
        }
        return colors;
    }


    /**
     * 识别性别和年龄
     * @param modelPath genderage.onnx 模型路径（相对路径）
     * @param imagePath 图片路径（相对路径）
     * @param faceBox 人脸框
     * @param uploadpath 基础路径
     * @param useGpu 是否使用GPU
     * @return 性别年龄预测结果
     */
    public static GenderAgeResult predictGenderAge(String modelPath, String imagePath, FaceBox faceBox,
                                                   String uploadpath, boolean useGpu) throws Exception {

        // 读取原图
        Mat image = Imgcodecs.imread(uploadpath + File.separator + imagePath);
        if (image.empty()) {
            throw new RuntimeException("无法读取图像: " + imagePath);
        }

        // 裁剪人脸区域（加一些边距）
        int padding = 10;
        int x = Math.max(0, (int) faceBox.getX() - padding);
        int y = Math.max(0, (int) faceBox.getY() - padding);
        int width = Math.min((int) faceBox.getWidth() + 2 * padding, image.cols() - x);
        int height = Math.min((int) faceBox.getHeight() + 2 * padding, image.rows() - y);

        Rect faceRect = new Rect(x, y, width, height);
        Mat faceCrop = new Mat(image, faceRect);

        // 预处理：resize 到 112x112
        Mat resized = new Mat();
        Imgproc.resize(faceCrop, resized, new Size(112, 112));
        Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB);

        // 归一化：(pixel - 127.5) / 128.0
        resized.convertTo(resized, CvType.CV_32F);
        Core.subtract(resized, new Scalar(127.5, 127.5, 127.5), resized);
        Core.divide(resized, new Scalar(128.0, 128.0, 128.0), resized);

        // HWC -> CHW
        float[] inputData = matToFloatArrayCHW(resized, 112, 112);

        // ONNX 推理
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA();
        } else {
            options.setInterOpNumThreads(4);
            options.setIntraOpNumThreads(8);
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + modelPath, options)) {
            long[] shape = {1, 3, 112, 112};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor
            );

            try (OrtSession.Result results = session.run(inputs)) {
                // 解析性别年龄输出
                return parseGenderAgeOutput(results, session);
            } finally {
                inputTensor.close();
            }
        }
    }

    /**
     * 解析 genderage 模型输出
     */
    private static GenderAgeResult parseGenderAgeOutput(OrtSession.Result results, OrtSession session)
            throws Exception {

        String gender = "Unknown";
        float genderConfidence = 0.0f;
        int age = 0;

        // 获取所有输出名称
        Set<String> outputNames = session.getOutputNames();
        System.out.println("模型输出节点: " + outputNames);

        // 遍历所有输出
        int outputIndex = 0;
        for (String outputName : outputNames) {
            OnnxValue value = results.get(outputIndex);

            if (value instanceof OnnxTensor) {
                OnnxTensor tensor = (OnnxTensor) value;
                float[] outputData = tensor.getFloatBuffer().array();

                System.out.println("输出 [" + outputName + "] 形状: " +
                        java.util.Arrays.toString(tensor.getInfo().getShape()));
                System.out.println("输出 [" + outputName + "] 数据: " +
                        java.util.Arrays.toString(outputData));

                // 根据输出判断是性别还是年龄
                if (outputIndex == 0) {
                    // 第一个输出通常是性别
                    if (outputData.length == 2) {
                        // 两个值: [female_prob, male_prob]
                        float femaleProb = outputData[0];
                        float maleProb = outputData[1];

                        if (maleProb > femaleProb) {
                            gender = "Male";
                            genderConfidence = maleProb;
                        } else {
                            gender = "Female";
                            genderConfidence = femaleProb;
                        }
                    } else if (outputData.length == 1) {
                        // 单个值: >0.5 为男性
                        float prob = outputData[0];
                        gender = prob > 0.5 ? "Male" : "Female";
                        genderConfidence = Math.abs(prob - 0.5f) * 2; // 转换为置信度
                    }
                } else if (outputIndex == 1) {
                    // 第二个输出通常是年龄
                    age = Math.round(outputData[0]);
                    // 年龄合理性检查
                    age = Math.max(0, Math.min(120, age));
                }
            }

            outputIndex++;
        }

        return new GenderAgeResult(gender, genderConfidence, age);
    }


    /**
     * 人脸检测 - 使用 SCRFD-10G
     */
    public static List<FaceBox> detectFaces(String modelPath, String imagePath,
                                            String uploadpath, boolean useGpu) throws Exception {

        Mat image = null;
        Mat processedImage = null;
        Mat blob = null;
        List<FaceBox> faceBoxes = new ArrayList<>();

        try {
            image = Imgcodecs.imread(uploadpath + File.separator + imagePath);
            if (image.empty()) {
                throw new RuntimeException("无法读取图像: " + imagePath);
            }

            int originalWidth = image.cols();
            int originalHeight = image.rows();

            // 预处理
            processedImage = letterboxResize(image, 640, 640);
            Imgproc.cvtColor(processedImage, processedImage, Imgproc.COLOR_BGR2RGB);

            // SCRFD 归一化
            blob = new Mat();
            processedImage.convertTo(blob, CvType.CV_32F);
            Core.subtract(blob, new Scalar(127.5, 127.5, 127.5), blob);
            Core.divide(blob, new Scalar(128.0, 128.0, 128.0), blob);

            // HWC -> CHW
            float[] inputData = matToFloatArrayCHW(blob, 640, 640);

            // ONNX 推理
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            if (useGpu) {
                options.addCUDA();
            } else {
                options.setInterOpNumThreads(4);
                options.setIntraOpNumThreads(8);
                options.addCPU(true);
            }

            try (OrtSession session = env.createSession(uploadpath + File.separator + modelPath, options)) {
                long[] shape = {1, 3, 640, 640};
                OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
                Map<String, OnnxTensor> inputs = Collections.singletonMap(
                        session.getInputNames().iterator().next(), inputTensor
                );

                try (OrtSession.Result results = session.run(inputs)) {
                    // 收集输出
                    Map<String, OnnxTensor> outputMap = new HashMap<>();
                    for (Map.Entry<String, OnnxValue> entry : results) {
                        if (entry.getValue() instanceof OnnxTensor) {
                            outputMap.put(entry.getKey(), (OnnxTensor) entry.getValue());
                        }
                    }

                    List<String> outputNames = new ArrayList<>(outputMap.keySet());
                    Collections.sort(outputNames);

                    // 提取 score 和 bbox
                    float[][] scores_s8  = (float[][]) outputMap.get(outputNames.get(0)).getValue();
                    float[][] bboxes_s8  = (float[][]) outputMap.get(outputNames.get(1)).getValue();
                    float[][] scores_s16 = (float[][]) outputMap.get(outputNames.get(3)).getValue();
                    float[][] bboxes_s16 = (float[][]) outputMap.get(outputNames.get(4)).getValue();
                    float[][] scores_s32 = (float[][]) outputMap.get(outputNames.get(6)).getValue();
                    float[][] bboxes_s32 = (float[][]) outputMap.get(outputNames.get(7)).getValue();

                    // 坐标转换参数
                    double scale = Math.min(640.0 / originalWidth, 640.0 / originalHeight);
                    double dx = (640 - originalWidth * scale) / 2;
                    double dy = (640 - originalHeight * scale) / 2;

                    List<Rect2d> boxes2d = new ArrayList<>();
                    List<Float> confidences = new ArrayList<>();

                    // 处理三个尺度
                    processSCRFDStride(scores_s8,  bboxes_s8,  8,  80, boxes2d, confidences,
                            scale, dx, dy, originalWidth, originalHeight);
                    processSCRFDStride(scores_s16, bboxes_s16, 16, 40, boxes2d, confidences,
                            scale, dx, dy, originalWidth, originalHeight);
                    processSCRFDStride(scores_s32, bboxes_s32, 32, 20, boxes2d, confidences,
                            scale, dx, dy, originalWidth, originalHeight);

                    // NMS —— 先判空再构造,避免空 List 触发 m.dims >= 2 断言
                    List<Integer> keepIndices = new ArrayList<>();
                    if (!confidences.isEmpty()) {
                        MatOfRect2d boxesMat = new MatOfRect2d();
                        boxesMat.fromList(boxes2d);

                        // 用 float[] 构造,绕开 MatOfFloat(Mat) 的维度检查
                        float[] confArr = new float[confidences.size()];
                        for (int i = 0; i < confArr.length; i++) {
                            confArr[i] = confidences.get(i);
                        }
                        MatOfFloat confidencesMat = new MatOfFloat(confArr);

                        MatOfInt indices = new MatOfInt();
                        try {
                            Dnn.NMSBoxes(boxesMat, confidencesMat, 0.65f, 0.4f, indices);
                            for (int idx : indices.toArray()) {
                                keepIndices.add(idx);
                            }
                        } finally {
                            boxesMat.release();
                            confidencesMat.release();
                            indices.release();
                        }
                    }

                    for (int idx : keepIndices) {
                        Rect2d box = boxes2d.get(idx);
                        FaceBox faceBox = new FaceBox();
                        faceBox.setX(box.x);
                        faceBox.setY(box.y);
                        faceBox.setWidth(box.width);
                        faceBox.setHeight(box.height);
                        faceBox.setConfidence(confidences.get(idx));
                        faceBoxes.add(faceBox);
                    }
                }
            }

            return faceBoxes;

        } finally {
            // 释放 Mat 堆外内存,避免长跑内存暴涨
            if (image != null) image.release();
            if (processedImage != null) processedImage.release();
            if (blob != null) blob.release();
        }
    }

    /**
     * 处理 SCRFD 单个尺度
     */
    private  static  void processSCRFDStride(float[][] scores, float[][] bboxes, int stride, int featSize,
                                    List<Rect2d> boxes2d, List<Float> confidences,
                                    double scale, double dx, double dy,
                                    int originalWidth, int originalHeight) {
        int anchorIdx = 0;
        for (int h = 0; h < featSize; h++) {
            for (int w = 0; w < featSize; w++) {
                for (int a = 0; a < 2; a++) {
                    if (anchorIdx >= scores.length) break;

                    float rawScore = scores[anchorIdx][0];
                    float score = sigmoid(rawScore);

                    if (score > 0.65f) {
                        float cx = (w + 0.5f) * stride;
                        float cy = (h + 0.5f) * stride;

                        float dist_left = bboxes[anchorIdx][0] * stride;
                        float dist_top = bboxes[anchorIdx][1] * stride;
                        float dist_right = bboxes[anchorIdx][2] * stride;
                        float dist_bottom = bboxes[anchorIdx][3] * stride;

                        float x1 = cx - dist_left;
                        float y1 = cy - dist_top;
                        float x2 = cx + dist_right;
                        float y2 = cy + dist_bottom;

                        x1 = (float) ((x1 - dx) / scale);
                        y1 = (float) ((y1 - dy) / scale);
                        x2 = (float) ((x2 - dx) / scale);
                        y2 = (float) ((y2 - dy) / scale);

                        x1 = Math.max(0, Math.min(x1, originalWidth - 1));
                        y1 = Math.max(0, Math.min(y1, originalHeight - 1));
                        x2 = Math.max(0, Math.min(x2, originalWidth - 1));
                        y2 = Math.max(0, Math.min(y2, originalHeight - 1));

                        float width = x2 - x1;
                        float height = y2 - y1;

                        if (width > 20 && height > 20 && width < originalWidth * 0.8 && height < originalHeight * 0.8) {
                            float aspectRatio = width / height;
                            if (aspectRatio > 0.5 && aspectRatio < 2.0) {
                                boxes2d.add(new Rect2d(x1, y1, width, height));
                                confidences.add(score);
                            }
                        }
                    }
                    anchorIdx++;
                }
            }
        }
    }



    /**
     * 提取人脸特征 - 使用 InsightFace w600k_r50
     */
    public static float[] extractFaceFeature(String modelPath, String imagePath, FaceBox faceBox,
                                       String uploadpath, boolean useGpu) throws Exception {

        // 读取原图
        Mat image = Imgcodecs.imread(uploadpath + File.separator + imagePath);
        if (image.empty()) {
            throw new RuntimeException("无法读取图像: " + imagePath);
        }

        // 裁剪人脸区域（加一些边距）
        int padding = 10;
        int x = Math.max(0, (int) faceBox.getX() - padding);
        int y = Math.max(0, (int) faceBox.getY() - padding);
        int width = Math.min((int) faceBox.getWidth() + 2 * padding, image.cols() - x);
        int height = Math.min((int) faceBox.getHeight()+ 2 * padding, image.rows() - y);

        Rect faceRect = new Rect(x, y, width, height);
        Mat faceCrop = new Mat(image, faceRect);

        // InsightFace 预处理：resize 到 112x112
        Mat resized = new Mat();
        Imgproc.resize(faceCrop, resized, new Size(112, 112));
        Imgproc.cvtColor(resized, resized, Imgproc.COLOR_BGR2RGB);

        // 归一化：(pixel - 127.5) / 128.0
        resized.convertTo(resized, CvType.CV_32F);
        Core.subtract(resized, new Scalar(127.5, 127.5, 127.5), resized);
        Core.divide(resized, new Scalar(128.0, 128.0, 128.0), resized);

        // HWC -> CHW
        float[] inputData = matToFloatArrayCHW(resized, 112, 112);

        // ONNX 推理
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        if (useGpu) {
            options.addCUDA();
        } else {
            options.setInterOpNumThreads(4);
            options.setIntraOpNumThreads(8);
        }

        try (OrtSession session = env.createSession(uploadpath + File.separator + modelPath, options)) {
            long[] shape = {1, 3, 112, 112};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), shape);
            Map<String, OnnxTensor> inputs = Collections.singletonMap(
                    session.getInputNames().iterator().next(), inputTensor
            );

            try (OrtSession.Result results = session.run(inputs)) {
                OnnxTensor outputTensor = (OnnxTensor) results.get(0);
                float[][] output = (float[][]) outputTensor.getValue();

                // L2 归一化
                return normalizeL2(output[0]);
            }
        }
    }
    /**
     * L2 归一化
     */
    private static float[] normalizeL2(float[] feature) {
        double norm = 0.0;
        for (float v : feature) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);

        float[] normalized = new float[feature.length];
        for (int i = 0; i < feature.length; i++) {
            normalized[i] = (float) (feature[i] / (norm + 1e-10));
        }
        return normalized;
    }

    /**
     * Mat 转 CHW float 数组
     */
    private static float[] matToFloatArrayCHW(Mat mat, int width, int height) {
        List<Mat> channels = new ArrayList<>();
        Core.split(mat, channels);

        float[] inputData = new float[3 * width * height];
        for (int c = 0; c < 3; c++) {
            float[] data = new float[width * height];
            channels.get(c).get(0, 0, data);
            System.arraycopy(data, 0, inputData, c * width * height, width * height);
        }

        return inputData;
    }
}
