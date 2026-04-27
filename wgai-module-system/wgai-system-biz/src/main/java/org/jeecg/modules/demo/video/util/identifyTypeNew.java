package org.jeecg.modules.demo.video.util;

import ai.onnxruntime.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.*;
import org.jeecg.common.util.RestUtil;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.tab.entity.PushInfo;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.train.util.picXml;
import org.jeecg.modules.demo.video.entity.TabAiModelNew;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.util.reture.retureBoxInfo;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.AIModel.pose.FallDetectionResult;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.entity.pushEntity;
import org.opencv.core.*;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.utils.Converters;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.springframework.data.redis.core.RedisTemplate;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.jeecg.modules.demo.audio.util.audioSend.getToken;
import static org.jeecg.modules.demo.audio.util.audioSend.postAudioText;
import static org.jeecg.modules.demo.video.util.frame.FrameQualityFilter.printAverageRGB;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.*;
import static org.jeecg.modules.tab.AIModel.pose.FallDetectionResult.detectFallOrStand;

/**
 * @author wggg
 * @date 2025/5/22 10:31
 */
@Slf4j
public class identifyTypeNew {


    //v3 验证是否通过
    public retureBoxInfo detectObjects(TabAiSubscriptionNew tabAiSubscriptionNew, Mat image, Net net, List<String> classNames, NetPush netPush) {

        retureBoxInfo retureBoxInfo=new retureBoxInfo();
        retureBoxInfo.setFlag(false);
        try {


            // 读取输入图像
            Long a = System.currentTimeMillis();
            // 将图像传递给模型进行目标检测
            Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);
            net.setInput(blob);
            // 将图像传递给模型进行目标检测
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // 处理检测结果
            float confThreshold = 0.56f;
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
                return retureBoxInfo;
            }

            int[] indicesArray = indices.toArray();
            // 获取保留的边界框
            if(indicesArray.length>50){
                log.error("怎么可能类别太大 20就是上限");
                return retureBoxInfo;
            }
            log.info(confidences.size() + "类别下标啊" + indicesArray.length);
            // 在图像上绘制保留的边界框

            List<retureBoxInfo> list=new ArrayList<>();
            for (int idx : indicesArray) {

                retureBoxInfo returnBox2 = new retureBoxInfo();
                // 添加类别标签
                Integer ab = classIds.get(idx);
                String name = classNames.get(ab);
                Rect2d box = boundingBoxes.get(idx);
                double x = box.x;
                double y = box.y;
                double width = box.width * ((double) image.cols() / 640);
                double height = box.height * ((double) image.rows() / 640);
                double xzb = x * ((double) image.cols() / 640);
                double yzb = y * ((double) image.rows() / 640);

                TabAiBase aiBase = VideoSendReadCfg.map.get(name);
                if (aiBase == null) {
                    aiBase = new TabAiBase();
                    aiBase.setChainName(name);

                }
                log.info("当前类别{}验证内容：{}", name, netPush.getBeforText());
                if (aiBase.getChainName().equals(netPush.getBeforText())) {
                    log.warn("验证通过{},{}：", name, netPush.getBeforText());
                    returnBox2.setX(xzb);
                    returnBox2.setY(yzb);
                    returnBox2.setWidth(width);
                    returnBox2.setHeight(height);
                    retureBoxInfo.setFlag(true);
                    list.add(returnBox2);
                }
            }

        } catch (Exception ex) {

            return retureBoxInfo;
        }


        return retureBoxInfo;
    }

    /***
     * 自动标注
     * @param image
     * @param net
     * @param classNames
     * @return
     */
    public List<picXml>  AutoDetectObjectsV5(TabEasyPic pic, Mat image, Net net, List<String> classNames) {

        List<picXml> picXmls=new ArrayList<>();
        try {


            // 读取输入图像
            Long a = System.currentTimeMillis();
            // ==========关键修正1: 使用letterbox预处理==========
            Mat processedImage = letterboxResize(image, 640, 640);
            // 将图像传递给模型进行目标检测
            Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(640, 640), new Scalar(0), true, false);
            net.setInput(blob);
            // 将图像传递给模型进行目标检测
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // 处理检测结果
            float confThreshold = 0.35f;
            float nmsThreshold = 0.2f;
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();

            for (Mat output : result) {
                int dims = output.dims();
                long dim0 = output.size(0);
                long dim1 = output.size(1);
                long dim2 = output.size(2);

                System.out.println("输出维度: [" + dim0 + ", " + dim1 + ", " + dim2 + "]");

                Mat detectionMat;
                int rows, cols;

                // 判断是YOLOv11还是YOLOv5格式
                if (dims == 3 && dim1 < 100 && dim2 > 1000) {
                    // YOLOv11格式 [1, 84, 8400]
                    System.out.println("检测到YOLOv11格式");
                    Mat reshaped = output.reshape(1, (int) dim1);
                    Mat transposed = new Mat();
                    Core.transpose(reshaped, transposed);
                    detectionMat = transposed;
                    rows = (int) dim2;
                    cols = (int) dim1;
                } else {
                    // YOLOv5格式
                    System.out.println("检测到YOLOv5格式");
                    detectionMat = output.reshape(1, (int) output.size(1));
                    rows = detectionMat.rows();
                    cols = detectionMat.cols();
                }

                System.out.println("处理后矩阵: " + rows + "x" + cols);

                for (int i = 0; i < rows; i++) {
                    Mat detection = detectionMat.row(i);

                    float confidence;
                    Mat scores;
                    Point classIdPoint;

                    if (cols == 84) {
                        // ==========关键修正3: YOLOv11置信度计算==========
                        // YOLOv11格式：[x, y, w, h, class0_conf, class1_conf, ...]
                        scores = detection.colRange(4, cols);
                        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                        confidence = (float) minMaxResult.maxVal;
                        classIdPoint = minMaxResult.maxLoc;
                    } else {
                        // YOLOv5格式
                        confidence = (float) detection.get(0, 4)[0];
                        scores = detection.colRange(5, cols);
                        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                        classIdPoint = minMaxResult.maxLoc;
                        confidence *= (float) minMaxResult.maxVal;
                    }

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
//
//                        log.info("检测到目标: 类别={}, 置信度={}, 坐标=({},{},{},{})",
//                                (int)classIdPoint.x, confidence, left, top, width, height);
                    }
                }
            }

            if (confidences.size() <= 0||confidences.size()>200) {

                return null;
            }
            // 执行非最大抑制，消除重复的边界框
            MatOfRect2d boxes_mat = new MatOfRect2d();
            boxes_mat.fromList(boxes2d);
            log.info("confidences.size{}", confidences.size());
            MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            if (!boxes_mat.empty() && !confidences_mat.empty()) {
                System.out.println("不为空");
                Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArray = indices.toArray();
            // 获取保留的边界框
            if(indicesArray.length>50){
                log.error("怎么可能类别太大 20就是上限");
                return picXmls;
            }
            //     log.info(confidences.size() + "类别下标啊" + indicesArray.length);
            // 在图像上绘制保留的边界框
            int c = 0;
            // 计算letterbox的缩放参数
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;
            for (int idx : indicesArray) {
                // 添加类别标签
                picXml picXml=new picXml();
                Rect2d box = boxes2d.get(idx);
                Integer ab = classIds.get(idx);
                String name = classNames.get(ab);
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
                if(x<0){
                    x=0;
                }
                if(y<0){
                    y=0;
                }
                picXml.setName(name);
                picXml.setPicId(pic.getId());
                picXml.setXmin(x+"");
                picXml.setXmax(x+width+"");
                picXml.setYmin(y+"");
                picXml.setYmax(y+height+"");
                picXml.setModelId(pic.getModelId());
                picXml.setCanvaswidth(width);
                picXml.setCanvasheight(height);
                picXml.setYwidth(Double.parseDouble(String.valueOf(image.width())));
                picXml.setYheight(Double.parseDouble(String.valueOf(image.width())));
                picXmls.add(picXml);
            }
        } catch (Exception ex) {
            return picXmls;
        }
        return picXmls;
    }

    //v5 v8 V10 验证是否通过
    public retureBoxInfo detectObjectsV5(TabAiSubscriptionNew tabAiSubscriptionNew, Mat image, Net net, List<String> classNames, NetPush netPush,RedisTemplate redisTemplate) {

        retureBoxInfo returnBox=new retureBoxInfo();
        returnBox.setFlag(false);
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime == null) {
            log.info("当前间隔消失可以推送了-间隔时间{}-当前可以推送的是{},当前数据：{}");
//             if(printAverageRGB(image)){
//                setErrorImg(image,"huidutu");
//                log.info("当前是灰度图片");
//                return false;
//             };
        } else {
            log.info("【当前不可间隔存在不可推送】");
            return returnBox;
        }



        try {


            // 读取输入图像
            Long a = System.currentTimeMillis();

            Mat processedImage = letterboxResize(image, 640, 640);
            // 将图像传递给模型进行目标检测
            Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255, new Size(640, 640), new Scalar(0), true, false);
            net.setInput(blob);
            // 将图像传递给模型进行目标检测
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // 处理检测结果
            float confThreshold = 0.4f;
            float nmsThreshold = 0.4f;
            List<Rect2d> boxes2d = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();

            for (Mat output : result) {
                int dims = output.dims();
                long dim0 = output.size(0);
                long dim1 = output.size(1);
                long dim2 = output.size(2);

                System.out.println("输出维度: [" + dim0 + ", " + dim1 + ", " + dim2 + "]");

                Mat detectionMat;
                int rows, cols;

                // 判断是YOLOv11还是YOLOv5格式
                if (dims == 3 && dim1 < 100 && dim2 > 1000) {
                    // YOLOv11格式 [1, 84, 8400]
                    log.info("检测到YOLOv11格式");
                    Mat reshaped = output.reshape(1, (int) dim1);
                    Mat transposed = new Mat();
                    Core.transpose(reshaped, transposed);
                    detectionMat = transposed;
                    rows = (int) dim2;
                    cols = (int) dim1;
                } else {
                    // YOLOv5格式
                    log.info("检测到YOLOv5格式");
                    detectionMat = output.reshape(1, (int) output.size(1));
                    rows = detectionMat.rows();
                    cols = detectionMat.cols();
                }

                log.info("处理后矩阵: " + rows + "x" + cols);

                for (int i = 0; i < rows; i++) {
                    Mat detection = detectionMat.row(i);

                    float confidence;
                    Mat scores;
                    Point classIdPoint;

                    if (cols == 84) {
                        // ==========关键修正3: YOLOv11置信度计算==========
                        // YOLOv11格式：[x, y, w, h, class0_conf, class1_conf, ...]
                        scores = detection.colRange(4, cols);
                        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                        confidence = (float) minMaxResult.maxVal;
                        classIdPoint = minMaxResult.maxLoc;
                    } else {
                        // YOLOv5格式
                        confidence = (float) detection.get(0, 4)[0];
                        scores = detection.colRange(5, cols);
                        Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                        classIdPoint = minMaxResult.maxLoc;
                        confidence *= (float) minMaxResult.maxVal;
                    }

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

//                        log.info("检测到目标: 类别={}, 置信度={}, 坐标=({},{},{},{})",
//                                (int)classIdPoint.x, confidence, left, top, width, height);
                    }
                }
            }


            if (confidences.size() <= 0||confidences.size()>200) {
                log.warn(tabAiSubscriptionNew.getName() + ":当前未检测到内容");
                return returnBox;
            }
            // 执行非最大抑制，消除重复的边界框
            MatOfRect2d boxes_mat = new MatOfRect2d();
            boxes_mat.fromList(boxes2d);
            log.info("confidences.size{}", confidences.size());
            MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
            MatOfInt indices = new MatOfInt();
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            if (!boxes_mat.empty() && !confidences_mat.empty()) {
                log.info("不为空");
                Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
            }

            int[] indicesArray = indices.toArray();
            // 获取保留的边界框
            if(indicesArray.length>50){
                log.error("怎么可能类别太大 20就是上限");
                return returnBox;
            }
            //     log.info(confidences.size() + "类别下标啊" + indicesArray.length);

            // 计算letterbox的缩放参数
            double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
            double dx = (640 - image.cols() * scale) / 2;
            double dy = (640 - image.rows() * scale) / 2;
            List<retureBoxInfo> list=new ArrayList<>();
            for (int idx : indicesArray) {

                retureBoxInfo returnBox2=new retureBoxInfo();
                // 添加类别标签
                Integer ab = classIds.get(idx);
                String name = classNames.get(ab);
                Rect2d box = boxes2d.get(idx);
                // 还原到原图坐标
                double xzb = (box.x - dx) / scale;
                double yzb = (box.y - dy) / scale;
                double width = box.width / scale;
                double height = box.height / scale;

                // 确保坐标在图像范围内
                xzb = Math.max(0, Math.min(xzb, image.cols() - 1));
                yzb = Math.max(0, Math.min(yzb, image.rows() - 1));
                width = Math.min(width, image.cols() - xzb);
                height = Math.min(height, image.rows() - yzb);

                TabAiBase aiBase = VideoSendReadCfg.map.get(name);
                if (aiBase == null) {
                    aiBase = new TabAiBase();
                    aiBase.setChainName(name);

                }
                log.info("当前类别{}验证内容：{}", name, netPush.getBeforText());
                if (aiBase.getChainName().equals(netPush.getBeforText())) {
                    log.warn("验证通过{},{}：", name, netPush.getBeforText());
                    returnBox2.setX(xzb);
                    returnBox2.setY(yzb);
                    returnBox2.setWidth(width);
                    returnBox2.setHeight(height);
                    returnBox.setFlag(true);
                    list.add(returnBox2);
                }

            }
            returnBox.setInfoList(list);

        } catch (Exception ex) {
            return returnBox;
        }finally {
            if(returnBox.isFlag()==false){
                setBeforeImg(image,"before");
            }
        }
        return returnBox;
    }


    public boolean detectObjectsDify(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush, RedisTemplate redisTemplate,List<retureBoxInfo> retureBoxInfos) {

        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();
        String uploadpath = netPush.getUploadPath();
        String settingId = netPush.getId();
        TabAiModel tabAiModel = netPush.getTabAiModel();

        // 读取输入图像
        Long a = System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(416, 416), new Scalar(0), true, false);
        net.setInput(blob);
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.56f;
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
            return false;
        }

        int[] indicesArray = indices.toArray();
        // 获取保留的边界框
        if(indicesArray.length>50){
            log.error("怎么可能类别太大 20就是上限");
            return false;
        }
        log.info("类别下标啊" + indicesArray.length);
        // 在图像上绘制保留的边界框
        int c = 0;
        for (int idx : indicesArray) {
            // 添加类别标签
            log.info("当前有多少" + confidences.get(idx));
            Integer ab = classIds.get(idx);
            String name = classNames.get(ab);
            TabAiBase aiBase = VideoSendReadCfg.map.get(name);
            if (aiBase == null) {
                aiBase.setChainName(name);
            }
            Rect2d box = boundingBoxes.get(idx);
            Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height), CommonColors(c), 2);

            log.info(aiBase.getChainName() + "类别下标" + ab);
            image = AIModelYolo3.addChineseText(image, aiBase.getChainName(), new Point(box.x, box.y - 5), CommonColors(c));
            //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }

        String savepath = uploadpath + File.separator + "temp" + File.separator;

        String saveName = settingId;//tabAiModel.getId();
        if (StringUtils.isNotBlank(saveName)) {
            saveName = savepath + saveName + ".jpg";
        } else {
            saveName = savepath + System.currentTimeMillis() + ".jpg";
        }
        log.info("存储地址{}", saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);
        String base64Img = base64Image(saveName);
        //组装参数
        pushEntity push = new pushEntity();
        push.setVideo(pushInfo.getBeginEventTypes());
        push.setType("图片");
        push.setCameraUrl(pushInfo.getBeginEventTypes());
        push.setAlarmPicData(base64Img);
        push.setTime(System.currentTimeMillis() + "");
        push.setModelId(tabAiModel.getId());
        push.setIndexCode(pushInfo.getIndexCode());
        push.setModelName(tabAiModel.getAiName());
        JSONObject ob = null;
        try {
            Long b = System.currentTimeMillis();
//            int endTime= (int) ((b-LastTime)/1000);
//            if(LastTime==0L){
//                log.info("当前时间未赋值："+endTime);
//                LastTime=b;
//            }else if(endTime>=pushInfo.getTime()){
//                LastTime=b;
//                log.info("当前时间频率赋值："+endTime);
//            }else if(endTime<pushInfo.getTime()){
//                log.info("当前时间小于间隔："+endTime);
//                return  false;
//            }
            if (pushInfo.getPushStatic() == 0) {
                ob = RestUtil.post(pushInfo.getEventUrl(), (JSONObject) JSONObject.toJSON(push));
            } else {
                log.info("不推送第三方V3");
            }
            // mqtt
            log.info("消耗时间V3：" + (b - a));
            log.info("返回内容：" + ob);
            //      LastTime=b;


        } catch (Exception ex) {
            log.info("连接失败");
            return false;

        }


        return true;
    }


    public boolean detectObjectsDifyV11(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush, RedisTemplate redisTemplate,List<retureBoxInfo> retureBoxInfos) {

        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime == null) {
            log.info("当前间隔消失可以推送了-间隔时间{}-当前可以推送的是{},当前数据：{}", time, pushInfo.getName(),JSON.toJSONString(retureBoxInfos));
        } else {
            return false;
        }
        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();

        String uploadpath = netPush.getUploadPath();
        String settingId = netPush.getId();

        TabAiModel tabAiModel = netPush.getTabAiModel();
        // 读取输入图像
        Long a = System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        Mat blob = Dnn.blobFromImage(image, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.45f;
        float nmsThreshold = 0.4f;
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

            log.info("检测到类别数: " + actualClassCount + " (期望: " + classNames.size() + ")");
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


        boolean flag;
        String savepath = uploadpath + File.separator + "push" + File.separator;
        if (confidences.size() <= 0||confidences.size()>200) {
            log.warn(pushInfo.getName() + ":当前未检测到内容：{}-{}",netPush.getTabAiModel().getAiName(),confidences.size());
            //setBeforeImg(image,"end");
            if(netPush.getWarinngMethod()==1){//0 是识别到报警  1 是未识别到报警
                log.info("[未识别到推送数据]:{}",netPush.getIsFollow());
                String   saveName = savepath + System.currentTimeMillis() + ".jpg";
                Imgcodecs.imwrite(saveName, image);
                isOk(pushInfo,netPush,redisTemplate,saveName,tabAiModel,netPush.getNoDifText(),1,netPush.getNoDifText(),netPush.getNoDifText(),savepath);
            }
            return false;
        }
        log.info("NMS前检测框数量: " + boxes2d.size());
        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);


        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            log.info("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indicesArray = indices.toArray();

        // 获取保留的边界框
        if(indicesArray.length>50){
            setErrorImg(image,"maxIndex");
            log.warn("最大消除后最大识别数量50 不可能大于50 "+indicesArray.length);
            return false;
        }
        log.info(confidences.size() + "类别下标啊-NMS前检测框数量" + indicesArray.length);
        // 在图像上绘制保留的边界框
        int c = 0;
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";
        //保存识别前的图片
        setBeforeImg(image,"end");
        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        for (int idx : indicesArray) {
            // 添加类别标签
            Rect2d box = boxes2d.get(idx);
            Integer ab = classIds.get(idx);
            String name = classNames.get(ab);
            float conf = confidences.get(idx);

            // 还原到原图坐标
            double xzb = (box.x - dx) / scale;
            double yzb = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;
            // 确保坐标在图像范围内
            xzb = Math.max(0, Math.min(xzb, image.cols() - 1));
            yzb = Math.max(0, Math.min(yzb, image.rows() - 1));
            width = Math.min(width, image.cols() - xzb);
            height = Math.min(height, image.rows() - yzb);

            if(netPush.getIsFollow()==0){
                log.info("[只要识别前置模型内的内容x:{},y:{},JSON:{}] ",xzb,yzb, JSON.toJSONString(retureBoxInfos));
                boolean followFlag= retureBoxInfo.getLocalhost(retureBoxInfos,xzb,yzb,netPush.getFollowPosition());
                if(!followFlag){
                    log.info("[不在范围内到直接跳过]");
                    continue;
                }else{
                    log.info("[在范围内开始推送： ]");
                }
            }

            if(netPush.getIsBy()==0){//开启了 0开启 1未开启
//                boolean isPointFlag= isPointInArea(box.x, box.y,  Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasStartx()), Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasStarty()),  Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasWidth()), Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasHeight()));


                TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                boolean inArea;

                // 关键修复：将640×640坐标转换为原图坐标
                if (videoUtil.getBzType() == null) {

                    inArea = isPointInArea(box.x, box.y,
                            Double.parseDouble(videoUtil.getCanvasStartx()),
                            Double.parseDouble(videoUtil.getCanvasStarty()),
                            Double.parseDouble(videoUtil.getCanvasWidth()),
                            Double.parseDouble(videoUtil.getCanvasHeight()));
                } else {
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    inArea = isPointInShapeData(box.x, box.y, videoUtil.getShapeData());
                }
                log.info("[是否在区域内]:"+inArea);
                if(inArea){
                    log.info("[在区域内]");
                }else{
                    log.info("[不在区域内]");
                    continue;
                }
            }

            Scalar color=CommonColors(c);
            TabAiBase aiBase = VideoSendReadCfg.map.get(name);
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(name);

            }else{
                if(StringUtils.isNotEmpty(aiBase.getSpaceThree())&&aiBase.getSpaceThree().equals("N")){
                    log.warn("【当前不推送：{}】",name);
                    continue;
                }

                color=getColor(aiBase.getRgbColor());
            }
            log.error("【当前推送：{}】",name);


            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText,StringUtils.isEmpty(aiBase.getRemark()) == true ? aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName,aiBase.getChainName());
            // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    color, 2);
            //    log.info( "类别下标"+ab);
            image = AIModelYolo3.addChineseText(image, aiBase.getChainName() + conf, new Point(xzb, yzb),color);
            //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }
        if(warnNumber>0){
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(), time, TimeUnit.SECONDS);
        }else{
            log.error("【当前不推送：{}】");
            return false;
        }


        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();
        }
//        String saveName = settingId;//pushInfo.getId();
//        if (StringUtils.isNotBlank(saveName)) {
//            saveName = savepath + saveName + ".jpg";
//        } else {
        String   saveName = savepath + System.currentTimeMillis() + ".jpg";
//        }

        log.info("存储地址{}", saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);

        try {
//            while(true){
//                log.info("会一直卡在这吗?");
//            }


//            int endTime= (int) ((b-LastTime)/1000);
//            if(LastTime==0L){
//                log.info("当前时间未赋值："+endTime);
//                LastTime=b;
//            }else if(endTime>=pushInfo.getTime()){
//                LastTime=b;
//                log.info("当前时间频率赋值："+endTime);
//            }else if(endTime<pushInfo.getTime()){
//                log.info("当前时间小于间隔："+endTime);
//                return  false;
//            }
            Long b = System.currentTimeMillis();
            log.info("识别消耗时间V5-v11：" + (b - a) + "ms");
            isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel, audioText, warnNumber, warnText, warnName, savepath);
//            if(pushInfo.getAudioStatic()==0){
//                log.info("语音播报："+audioText);
//                String token= getToken(pushInfo.getAudioId());
//                postAudioText(token,pushInfo.getAudioId(),audioText);
//            }else{
//                log.info("语音不播报：");
//            }

//            LastTime=b;


        } catch (Exception ex) {
            ex.printStackTrace();
            log.info("连接失败");
            return false;

        }


        return true;
    }
    public boolean detectObjectsDifyROIV11(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush,
                                        RedisTemplate redisTemplate, List<retureBoxInfo> retureBoxInfos) {

        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime == null) {
            log.info("当前间隔消失可以推送了-间隔时间{}-当前可以推送的是{},当前数据：{}",
                    time, pushInfo.getName(), JSON.toJSONString(retureBoxInfos));
        } else {
            return false;
        }

        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();
        String uploadpath = netPush.getUploadPath();
        String settingId = netPush.getId();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        Long startTime = System.currentTimeMillis();

        // ========== 全局变量:存储所有ROI的检测结果 ==========
        List<Rect2d> allBoxes = new ArrayList<>();
        List<Float> allConfidences = new ArrayList<>();
        List<Integer> allClassIds = new ArrayList<>();
        List<Integer> allRoiIndices = new ArrayList<>();  // 记录每个框来自哪个ROI

        float confThreshold = 0.45f;
        float nmsThreshold = 0.4f;

        // ========== 遍历每个ROI区域 ==========
        for (int roiIndex = 0; roiIndex < retureBoxInfos.size(); roiIndex++) {
            retureBoxInfo personBox = retureBoxInfos.get(roiIndex);

            log.info("========== 处理ROI区域 #{} ==========", roiIndex);
            log.info("ROI坐标: x={}, y={}, w={}, h={}",
                    personBox.getX(), personBox.getY(), personBox.getWidth(), personBox.getHeight());

            // ========== 1. 裁剪ROI区域 ==========
            int roiX = (int) Math.max(0, personBox.getX());
            int roiY = (int) Math.max(0, personBox.getY());
            int roiW = (int) Math.min(personBox.getWidth(), image.cols() - roiX);
            int roiH = (int) Math.min(personBox.getHeight(), image.rows() - roiY);

            // 验证ROI有效性
            if (roiW <= 0 || roiH <= 0) {
                log.warn("ROI #{} 无效,跳过", roiIndex);
                continue;
            }

            Rect roiRect = new Rect(roiX, roiY, roiW, roiH);
            Mat roiImage = new Mat(image, roiRect);

            log.info("ROI #{} 裁剪后尺寸: {}x{}", roiIndex, roiImage.cols(), roiImage.rows());

            // ========== 2. ROI图像预处理 ==========
            Mat blob = Dnn.blobFromImage(roiImage, 1.0 / 255.0, new Size(640, 640),
                    new Scalar(0, 0, 0), true, false, CvType.CV_32F);
            net.setInput(blob);

            // ========== 3. 模型推理 ==========
            List<Mat> result = new ArrayList<>();
            List<String> outBlobNames = net.getUnconnectedOutLayersNames();
            net.forward(result, outBlobNames);

            // ========== 4. 计算ROI的letterbox参数(用于坐标还原) ==========
            double roiScale = Math.min(640.0 / roiImage.cols(), 640.0 / roiImage.rows());
            double roiDx = (640 - roiImage.cols() * roiScale) / 2;
            double roiDy = (640 - roiImage.rows() * roiScale) / 2;

            // ========== 5. 解析检测结果 ==========
            for (Mat output : result) {
                int dims = output.dims();
                long dim0 = output.size(0);
                long dim1 = output.size(1);
                long dim2 = output.size(2);

                log.info("ROI #{} 输出维度: [{}, {}, {}]", roiIndex, dim0, dim1, dim2);

                Mat detectionMat;
                int rows, cols;
                String formatType;
                boolean hasObjectness;
                int actualClassCount;

                // ========== 智能格式识别 ==========
                if (dims == 3) {
                    if (dim1 < 100 && dim2 > 1000) {
                        // YOLOv11格式: [1, C, N] -> [N, C]
                        formatType = "YOLOv11";
                        Mat reshaped = output.reshape(1, (int) dim1);
                        Mat transposed = new Mat();
                        Core.transpose(reshaped, transposed);
                        detectionMat = transposed;
                        rows = (int) dim2;
                        cols = (int) dim1;
                        hasObjectness = false;
                        actualClassCount = cols - 4;
                    } else if (dim1 > 1000 && dim2 < 100) {
                        // YOLOv5格式: [1, N, C]
                        formatType = "YOLOv5";
                        detectionMat = output.reshape(1, (int) dim1);
                        rows = (int) dim1;
                        cols = (int) dim2;
                        hasObjectness = true;
                        actualClassCount = cols - 5;
                    } else {
                        // 自动判断
                        formatType = "AUTO";
                        detectionMat = output.reshape(1, (int) dim1);
                        rows = detectionMat.rows();
                        cols = detectionMat.cols();
                        hasObjectness = cols > 5;
                        actualClassCount = hasObjectness ? cols - 5 : cols - 4;
                    }
                } else {
                    log.error("ROI #{} 不支持的维度数: {}", roiIndex, dims);
                    continue;
                }

                log.info("ROI #{} 格式: {}, 矩阵: {}x{}, 类别数: {}",
                        roiIndex, formatType, rows, cols, actualClassCount);

                // 验证类别数
                if (actualClassCount < 1 || actualClassCount != classNames.size()) {
                    log.warn("ROI #{} 类别数不匹配: 检测到{}, 期望{}",
                            roiIndex, actualClassCount, classNames.size());
                }

                // ========== 6. 提取检测框(在ROI坐标系下) ==========
                int scoresStartCol = hasObjectness ? 5 : 4;

                for (int i = 0; i < rows; i++) {
                    Mat detection = detectionMat.row(i);

                    // 提取类别分数
                    Mat scores = detection.colRange(scoresStartCol, cols);
                    Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                    float maxClassScore = (float) minMaxResult.maxVal;
                    int classId = (int) minMaxResult.maxLoc.x;

                    // 计算置信度
                    float confidence;
                    if (hasObjectness) {
                        float objectness = (float) detection.get(0, 4)[0];
                        confidence = objectness * maxClassScore;
                    } else {
                        confidence = maxClassScore;
                    }

                    if (confidence > confThreshold) {
                        // 提取边框(640x640坐标系)
                        float centerX = (float) detection.get(0, 0)[0];
                        float centerY = (float) detection.get(0, 1)[0];
                        float width = (float) detection.get(0, 2)[0];
                        float height = (float) detection.get(0, 3)[0];

                        // ========== 7. 坐标还原:640x640 -> ROI原图 -> 全图 ==========
                        // 步骤1: 去除letterbox padding
                        double xInRoi = (centerX - roiDx) / roiScale;
                        double yInRoi = (centerY - roiDy) / roiScale;
                        double wInRoi = width / roiScale;
                        double hInRoi = height / roiScale;

                        // 步骤2: 转换为左上角坐标
                        double leftInRoi = xInRoi - wInRoi / 2;
                        double topInRoi = yInRoi - hInRoi / 2;

                        // 步骤3: 映射到全图坐标系
                        double leftInFullImage = leftInRoi + roiX;
                        double topInFullImage = topInRoi + roiY;

                        // 边界检查
                        leftInFullImage = Math.max(0, Math.min(leftInFullImage, image.cols() - 1));
                        topInFullImage = Math.max(0, Math.min(topInFullImage, image.rows() - 1));
                        wInRoi = Math.min(wInRoi, image.cols() - leftInFullImage);
                        hInRoi = Math.min(hInRoi, image.rows() - topInFullImage);

                        // 验证类别ID
                        if (classId < 0 || classId >= actualClassCount) {
                            log.warn("ROI #{} 无效的类别ID: {}", roiIndex, classId);
                            continue;
                        }

                        // 存储到全局列表
                        allBoxes.add(new Rect2d(leftInFullImage, topInFullImage, wInRoi, hInRoi));
                        allConfidences.add(confidence);
                        allClassIds.add(classId);
                        allRoiIndices.add(roiIndex);
                    }
                }
            }

            // 释放ROI图像
            roiImage.release();
            blob.release();
        }

        // ========== 8. 全局NMS(跨ROI去重) ==========
        log.info("所有ROI检测框总数: {}", allBoxes.size());

        if (allConfidences.size() <= 0 || allConfidences.size() > 200) {
            log.warn("{}: 检测框数量异常: {}", pushInfo.getName(), allConfidences.size());
            if (netPush.getWarinngMethod() == 1) {
                String savepath = uploadpath + File.separator + "push" + File.separator;
                String saveName = savepath + System.currentTimeMillis() + ".jpg";
                Imgcodecs.imwrite(saveName, image);
                isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel,
                        netPush.getNoDifText(), 1, netPush.getNoDifText(),
                        netPush.getNoDifText(), savepath);
            }
            return false;
        }

        // 执行NMS
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(allBoxes);
        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(allConfidences));
        MatOfInt indices = new MatOfInt();

        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indicesArray = indices.toArray();
        log.info("NMS后保留框数量: {}", indicesArray.length);

        if (indicesArray.length > 50) {
            log.warn("NMS后检测框数量超过50: {}", indicesArray.length);
            setErrorImg(image, "maxIndex");
            return false;
        }

        // ========== 9. 绘制检测结果 ==========
        int colorIndex = 0;
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";

        setBeforeImg(image, "end");

        for (int idx : indicesArray) {
            Rect2d box = allBoxes.get(idx);
            Integer classId = allClassIds.get(idx);
            float conf = allConfidences.get(idx);
            int roiSource = allRoiIndices.get(idx);

            String name = classNames.get(classId);
            double xzb = box.x;
            double yzb = box.y;
            double width = box.width;
            double height = box.height;

            // ========== 10. 应用过滤条件 ==========
            // 跟随前置模型判断
            if (netPush.getIsFollow() == 0) {
                boolean followFlag = retureBoxInfo.getLocalhost(retureBoxInfos, xzb, yzb,
                        netPush.getFollowPosition());
                if (!followFlag) {
                    log.info("框不在前置模型范围内,跳过");
                    continue;
                }
            }

            // 画布区域判断
            if (netPush.getIsBy() == 0) {
//                boolean isPointFlag = isPointInArea(box.x, box.y,
//                        Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasStartx()),
//                        Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasStarty()),
//                        Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasWidth()),
//                        Double.parseDouble(pushInfo.getTabVideoUtil().getCanvasHeight()));

                TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                boolean inArea;

                // 关键修复：将640×640坐标转换为原图坐标
                if (videoUtil.getBzType() == null) {

                    inArea = isPointInArea(box.x, box.y,
                            Double.parseDouble(videoUtil.getCanvasStartx()),
                            Double.parseDouble(videoUtil.getCanvasStarty()),
                            Double.parseDouble(videoUtil.getCanvasWidth()),
                            Double.parseDouble(videoUtil.getCanvasHeight()));
                } else {
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    inArea = isPointInShapeData(box.x, box.y, videoUtil.getShapeData());
                }
                if (!inArea) {
                    log.info("框不在画布区域内,跳过");
                    continue;
                }
            }

            // ========== 11. 获取类别配置 ==========
            Scalar color = CommonColors(colorIndex);
            TabAiBase aiBase = VideoSendReadCfg.map.get(name);
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(name);
            } else {
                if (StringUtils.isNotEmpty(aiBase.getSpaceThree()) &&
                        aiBase.getSpaceThree().equals("N")) {
                    log.warn("当前类别不推送: {}", name);
                    continue;
                }
                color = getColor(aiBase.getRgbColor());
            }

            log.info("检测到: {} (来自ROI #{}), 置信度: {:.2f}", name, roiSource, conf);

            // ========== 12. 绘制边框和标签 ==========
            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    color, 2);

            image = AIModelYolo3.addChineseText(image,
                    aiBase.getChainName() + String.format("%.2f", conf),
                    new Point(xzb, yzb), color);

            // 累计报警信息
            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText,
                    StringUtils.isEmpty(aiBase.getRemark()) ? aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName, aiBase.getChainName());

            colorIndex++;
        }

        // ========== 13. 保存结果并推送 ==========
        if (warnNumber > 0) {
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                    time, TimeUnit.SECONDS);
        } else {
            log.error("无有效检测结果,不推送");
            return false;
        }

        String savepath = uploadpath + File.separator + "push" + File.separator;
        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();
        }

        String saveName = savepath + System.currentTimeMillis() + ".jpg";
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);

        try {
            Long endTime = System.currentTimeMillis();
            log.info("ROI检测总耗时(v5-v11): {}ms, 处理了{}个ROI区域",
                    (endTime - startTime), retureBoxInfos.size());

            isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel,
                    audioText, warnNumber, warnText, warnName, savepath);

            return true;

        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("推送失败: {}", ex.getMessage());
            return false;
        }
    }
    public boolean detectObjectsDifyV5Pose(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush, RedisTemplate redisTemplate,List<retureBoxInfo> retureBoxInfos) {

        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime == null) {
            log.info("当前间隔消失可以推送了-间隔时间{}-当前可以推送的是{},当前数据：{}", time, pushInfo.getName(),JSON.toJSONString(retureBoxInfos));
//             if(printAverageRGB(image)){
//                setErrorImg(image,"huidutu");
//                log.info("当前是灰度图片");
//                return false;
//             };

        } else {
            return false;
        }
        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();
        String uploadpath = netPush.getUploadPath();
        String settingId = netPush.getId();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        // 读取输入图像
        Long a = System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        // ==========关键修正1: 使用letterbox预处理 以前不必须纠正 后续v8 v10 v11需要纠正==========
        Mat processedImage = letterboxResize(image, 640, 640);

        // 创建blob - 注意参数调整
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);;
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.4f;
        float nmsThreshold = 0.4f;
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

        boolean flag;
        String savepath = uploadpath + File.separator + "push" + File.separator;
        if (confidences.size() <= 0||confidences.size()>200) {
            log.warn(pushInfo.getName() + ":当前未检测到内容：{}-{}",netPush.getTabAiModel().getAiName(),confidences.size());
            //setBeforeImg(image,"end");

            if(netPush.getWarinngMethod()==1){//0 是识别到报警  1 是未识别到报警
                log.info("[未识别到推送数据]:{}",netPush.getIsFollow());
                String   saveName = savepath + System.currentTimeMillis() + ".jpg";
                Imgcodecs.imwrite(saveName, image);
                isOk(pushInfo,netPush,redisTemplate,saveName,tabAiModel,netPush.getNoDifText(),1,netPush.getNoDifText(),netPush.getNoDifText(),savepath);
            }
            return false;
        }
        log.info("NMS前检测框数量: " + boxes2d.size());
        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            log.info("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indicesArray = indices.toArray();

        // 获取保留的边界框
        if(indicesArray.length>50){

            setErrorImg(image,"maxIndex");
            log.info("最大消除后最大识别数量50 不可能大于50 "+indicesArray.length);
            return false;
        }
        log.info(confidences.size() + "类别下标-NMS后检测框数量" + indicesArray.length);
        // 在图像上绘制保留的边界框
        int c = 0;
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";
        //保存识别前的图片
        setBeforeImg(image,"end");
        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        for (int idx : indicesArray) {
            // 添加类别标签
            Rect2d box = boxes2d.get(idx);
            Integer ab = classIds.get(idx);
            String name = classNames.get(ab);
            float conf = confidences.get(idx);
            // 还原到原图坐标
            double xzb = (box.x - dx) / scale;
            double yzb = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;
            // 确保坐标在图像范围内
            xzb = Math.max(0, Math.min(xzb, image.cols() - 1));
            yzb = Math.max(0, Math.min(yzb, image.rows() - 1));
            width = Math.min(width, image.cols() - xzb);
            height = Math.min(height, image.rows() - yzb);
            float[] kpts = keypoints.get(idx);
            FallDetectionResult fallResult = detectFallOrStand(kpts, scale, dx, dy);
            if(fallResult.isAlert()==false){
                log.info("姿态未识别到内容");
                continue;
            }
            if(netPush.getIsFollow()==0){
                log.info("[只要识别前置模型内的内容x:{},y:{},JSON:{}] ",xzb,yzb, JSON.toJSONString(retureBoxInfos));
                boolean followFlag= retureBoxInfo.getLocalhost(retureBoxInfos,xzb,yzb,netPush.getFollowPosition());
                if(!followFlag){
                    log.info("[不在范围内到直接跳过]");
                    continue;
                }else{
                    log.info("[在范围内开始推送： ]");
                }
            }

            if(netPush.getIsBy()==0){//开启了 0开启 1未开启
            //    boolean isPointFlag= isPointInArea(box.x, box.y,  Double.parseDouble(netPush.getTabVideoUtil().getCanvasStartx()), Double.parseDouble(netPush.getTabVideoUtil().getCanvasStarty()),  Double.parseDouble(netPush.getTabVideoUtil().getCanvasWidth()), Double.parseDouble(netPush.getTabVideoUtil().getCanvasHeight()));
                TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                boolean inArea;

                // 关键修复：将640×640坐标转换为原图坐标
                if (videoUtil.getBzType() == null) {

                    inArea = isPointInArea(box.x, box.y,
                            Double.parseDouble(videoUtil.getCanvasStartx()),
                            Double.parseDouble(videoUtil.getCanvasStarty()),
                            Double.parseDouble(videoUtil.getCanvasWidth()),
                            Double.parseDouble(videoUtil.getCanvasHeight()));
                } else {
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    inArea = isPointInShapeData(box.x, box.y, videoUtil.getShapeData());
                }
                log.info("[是否在区域内]:"+inArea);
                if(inArea){
                    log.info("[在区域内]");
                }else{
                    log.info("[不在区域内]");
                    continue;
                }
            }

            Scalar color=CommonColors(c);
            TabAiBase aiBase = VideoSendReadCfg.map.get(fallResult.getStatus());
            aiBase.setChainName(fallResult.getStatus());
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(name);

            }else{
                if(StringUtils.isNotEmpty(aiBase.getSpaceThree())&&aiBase.getSpaceThree().equals("N")){
                    log.warn("【当前不推送：{}】",name);
                    continue;
                }

                color=getColor(aiBase.getRgbColor());
            }
            log.error("【当前推送：{}】",name);

            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText,StringUtils.isEmpty(aiBase.getRemark()) == true ? aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName,aiBase.getChainName());
            // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    color, 2);
            //    log.info( "类别下标"+ab);
            image = AIModelYolo3.addChineseText(image, aiBase.getChainName() + conf, new Point(xzb, yzb),color);
            //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }
        if(warnNumber>0){
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(), time, TimeUnit.SECONDS);
        }else{
            log.error("【当前不推送：{}】");
            return false;
        }


        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();
        }
//        String saveName = settingId;//pushInfo.getId();
//        if (StringUtils.isNotBlank(saveName)) {
//            saveName = savepath + saveName + ".jpg";
//        } else {
        String   saveName = savepath + System.currentTimeMillis() + ".jpg";
//        }

        log.info("存储地址{}", saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);

        try {
//            while(true){
//                log.info("会一直卡在这吗?");
//            }


//            int endTime= (int) ((b-LastTime)/1000);
//            if(LastTime==0L){
//                log.info("当前时间未赋值："+endTime);
//                LastTime=b;
//            }else if(endTime>=pushInfo.getTime()){
//                LastTime=b;
//                log.info("当前时间频率赋值："+endTime);
//            }else if(endTime<pushInfo.getTime()){
//                log.info("当前时间小于间隔："+endTime);
//                return  false;
//            }
            Long b = System.currentTimeMillis();
            log.info("识别消耗时间V5-v11：" + (b - a) + "ms");
            isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel, audioText, warnNumber, warnText, warnName, savepath);
//            if(pushInfo.getAudioStatic()==0){
//                log.info("语音播报："+audioText);
//                String token= getToken(pushInfo.getAudioId());
//                postAudioText(token,pushInfo.getAudioId(),audioText);
//            }else{
//                log.info("语音不播报：");
//            }

//            LastTime=b;


        } catch (Exception ex) {
            ex.printStackTrace();
            log.info("连接失败");
            return false;

        }


        return true;
    }

    public boolean detectObjectsDifyV5(TabAiSubscriptionNew pushInfo, Mat image, NetPush netPush, RedisTemplate redisTemplate,List<retureBoxInfo> retureBoxInfos) {

        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime == null) {
            log.info("当前间隔消失可以推送了-间隔时间{}-当前可以推送的是{},当前数据：{}", time, pushInfo.getName(),JSON.toJSONString(retureBoxInfos));
//             if(printAverageRGB(image)){
//                setErrorImg(image,"huidutu");
//                log.info("当前是灰度图片");
//                return false;
//             };

        } else {
            return false;
        }
        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();
        String uploadpath = netPush.getUploadPath();
        String settingId = netPush.getId();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        // 读取输入图像
        Long a = System.currentTimeMillis();
        // 将图像传递给模型进行目标检测
        // ==========关键修正1: 使用letterbox预处理 以前不必须纠正 后续v8 v10 v11需要纠正==========
        Mat processedImage = letterboxResize(image, 640, 640);

        // 创建blob - 注意参数调整
        Mat blob = Dnn.blobFromImage(processedImage, 1.0 / 255.0, new Size(640, 640), new Scalar(0, 0, 0), true, false, CvType.CV_32F);
        net.setInput(blob);;
        // 将图像传递给模型进行目标检测
        List<Mat> result = new ArrayList<>();
        List<String> outBlobNames = net.getUnconnectedOutLayersNames();
        net.forward(result, outBlobNames);

        // 处理检测结果
        float confThreshold = 0.4f;
        float nmsThreshold = 0.4f;
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

            log.info("检测到类别数: " + actualClassCount + " (期望: " + classNames.size() + ")");
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
        boolean flag;
        String savepath = uploadpath + File.separator + "push" + File.separator;
        if (confidences.size() <= 0||confidences.size()>200) {
            log.warn(pushInfo.getName() + ":当前未检测到内容：{}-{}",netPush.getTabAiModel().getAiName(),confidences.size());
            //setBeforeImg(image,"end");

            if(netPush.getWarinngMethod()==1){//0 是识别到报警  1 是未识别到报警
                log.info("[未识别到推送数据]:{}",netPush.getIsFollow());
                String   saveName = savepath + System.currentTimeMillis() + ".jpg";
                Imgcodecs.imwrite(saveName, image);
                isOk(pushInfo,netPush,redisTemplate,saveName,tabAiModel,netPush.getNoDifText(),1,netPush.getNoDifText(),netPush.getNoDifText(),savepath);
            }
            return false;
        }
        log.info("NMS前检测框数量: " + boxes2d.size());
        // 执行非最大抑制，消除重复的边界框
        MatOfRect2d boxes_mat = new MatOfRect2d();
        boxes_mat.fromList(boxes2d);

        MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
        MatOfInt indices = new MatOfInt();
        Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        if (!boxes_mat.empty() && !confidences_mat.empty()) {
            log.info("不为空");
            Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
        }

        int[] indicesArray = indices.toArray();

        // 获取保留的边界框
        if(indicesArray.length>50){

            setErrorImg(image,"maxIndex");
            log.info("最大消除后最大识别数量50 不可能大于50 "+indicesArray.length);
            return false;
        }
        log.info(confidences.size() + "类别下标-NMS后检测框数量" + indicesArray.length);
        // 在图像上绘制保留的边界框
        int c = 0;
        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";
        //保存识别前的图片
        setBeforeImg(image,"end");
        // 计算letterbox的缩放参数
        double scale = Math.min(640.0 / image.cols(), 640.0 / image.rows());
        double dx = (640 - image.cols() * scale) / 2;
        double dy = (640 - image.rows() * scale) / 2;

        for (int idx : indicesArray) {
            // 添加类别标签
            Rect2d box = boxes2d.get(idx);
            Integer ab = classIds.get(idx);
            String name = classNames.get(ab);
            float conf = confidences.get(idx);
            // 还原到原图坐标
            double xzb = (box.x - dx) / scale;
            double yzb = (box.y - dy) / scale;
            double width = box.width / scale;
            double height = box.height / scale;
            // 确保坐标在图像范围内
            xzb = Math.max(0, Math.min(xzb, image.cols() - 1));
            yzb = Math.max(0, Math.min(yzb, image.rows() - 1));
            width = Math.min(width, image.cols() - xzb);
            height = Math.min(height, image.rows() - yzb);

            if(netPush.getIsFollow()==0&&netPush.getIsBefor()==0){
                log.info("[只要识别前置模型内的内容x:{},y:{},JSON:{}] ",xzb,yzb, JSON.toJSONString(retureBoxInfos));
                boolean followFlag= retureBoxInfo.getLocalhost(retureBoxInfos,xzb,yzb,netPush.getFollowPosition());
                if(!followFlag){
                    log.info("[不在范围内到直接跳过]");
                    continue;
                }else{
                    log.info("[在范围内开始推送： ]");
                }
            }

            if(netPush.getIsBy()==0){//开启了 0开启 1未开启
             //   boolean isPointFlag= isPointInArea(box.x, box.y,  Double.parseDouble(netPush.getTabVideoUtil().getCanvasStartx()), Double.parseDouble(netPush.getTabVideoUtil().getCanvasStarty()),  Double.parseDouble(netPush.getTabVideoUtil().getCanvasWidth()), Double.parseDouble(netPush.getTabVideoUtil().getCanvasHeight()));
                TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                boolean inArea;

                // 关键修复：将640×640坐标转换为原图坐标
                if (videoUtil.getBzType() == null) {

                    inArea = isPointInArea(box.x, box.y,
                            Double.parseDouble(videoUtil.getCanvasStartx()),
                            Double.parseDouble(videoUtil.getCanvasStarty()),
                            Double.parseDouble(videoUtil.getCanvasWidth()),
                            Double.parseDouble(videoUtil.getCanvasHeight()));
                } else {
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    inArea = isPointInShapeData(box.x, box.y, videoUtil.getShapeData());
                }
                log.info("[是否在区域内]:"+inArea);
                if(inArea){
                    log.info("[在区域内]");
                }else{
                    log.info("[不在区域内]");
                    continue;
                }
            }

            Scalar color=CommonColors(c);
            TabAiBase aiBase = VideoSendReadCfg.map.get(name);
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(name);

            }else{
                if(StringUtils.isNotEmpty(aiBase.getSpaceThree())&&aiBase.getSpaceThree().equals("N")){
                    log.warn("【当前不推送：{}】",name);
                    continue;
                }

                color=getColor(aiBase.getRgbColor());
            }
            log.error("【当前推送：{}】",name);


            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText,StringUtils.isEmpty(aiBase.getRemark()) == true ? aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName,aiBase.getChainName());
            // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
            Imgproc.rectangle(image,
                    new Point(xzb, yzb),
                    new Point(xzb + width, yzb + height),
                    color, 2);
            //    log.info( "类别下标"+ab);
            image = AIModelYolo3.addChineseText(image, aiBase.getChainName() + conf, new Point(xzb, yzb),color);
            //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
            c++;
        }
        if(warnNumber>0){
            redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(), time, TimeUnit.SECONDS);
        }else{
            log.error("【当前不推送：{}】");
            return false;
        }


        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();
        }
//        String saveName = settingId;//pushInfo.getId();
//        if (StringUtils.isNotBlank(saveName)) {
//            saveName = savepath + saveName + ".jpg";
//        } else {
        String   saveName = savepath + System.currentTimeMillis() + ".jpg";
//        }

        log.info("存储地址{}", saveName);
        File imageFile = new File(saveName);
        if (imageFile.exists()) {
            imageFile.delete();
        }
        Imgcodecs.imwrite(saveName, image);

        try {
//            while(true){
//                log.info("会一直卡在这吗?");
//            }


//            int endTime= (int) ((b-LastTime)/1000);
//            if(LastTime==0L){
//                log.info("当前时间未赋值："+endTime);
//                LastTime=b;
//            }else if(endTime>=pushInfo.getTime()){
//                LastTime=b;
//                log.info("当前时间频率赋值："+endTime);
//            }else if(endTime<pushInfo.getTime()){
//                log.info("当前时间小于间隔："+endTime);
//                return  false;
//            }
            Long b = System.currentTimeMillis();
            log.info("识别消耗时间V5-v11：" + (b - a) + "ms");
            isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel, audioText, warnNumber, warnText, warnName, savepath);
//            if(pushInfo.getAudioStatic()==0){
//                log.info("语音播报："+audioText);
//                String token= getToken(pushInfo.getAudioId());
//                postAudioText(token,pushInfo.getAudioId(),audioText);
//            }else{
//                log.info("语音不播报：");
//            }

//            LastTime=b;


        } catch (Exception ex) {
            ex.printStackTrace();
            log.info("连接失败");
            return false;

        }


        return true;
    }


    /**
     * 改造后的检测方法：对每个前置检测框进行ROI放大检测
     */
    public boolean detectObjectsDifyV5WithROI(TabAiSubscriptionNew pushInfo, Mat image,
                                              NetPush netPush, RedisTemplate redisTemplate,
                                              List<retureBoxInfo> retureBoxInfos) {

        long time = Long.parseLong(pushInfo.getEventNumber());
        Object beforTime = redisTemplate.opsForValue().get(netPush.getId());
        if (beforTime != null) {
            return false;
        }

        Net net = netPush.getNet();
        List<String> classNames = netPush.getClaseeNames();
        String uploadpath = netPush.getUploadPath();
        TabAiModel tabAiModel = netPush.getTabAiModel();
        Long startTime = System.currentTimeMillis();

        // ========== 关键改造：判断是否需要ROI检测 ==========
        boolean useROIDetection = (retureBoxInfos != null && !retureBoxInfos.isEmpty());

        List<FinalDetectionResult> allDetections = new ArrayList<>();

        if (useROIDetection) {
            // ========== 方案A：ROI放大检测（针对小目标） ==========
            log.info("启用ROI检测模式，前置检测框数量: {}", retureBoxInfos.size());
            allDetections = detectInROIRegions(image, net, classNames, retureBoxInfos, netPush);
        }else{
            log.info("未检测到前置");
            return  false;
        }

        // ========== 后续处理：区域过滤、NMS、绘制、推送 ==========
        return processDetectionResults(allDetections, image, pushInfo, netPush,
                redisTemplate, uploadpath, tabAiModel, startTime);
    }

    /**
     * 核心方法：在每个ROI区域内进行放大检测
     */
    private List<FinalDetectionResult> detectInROIRegions(Mat image, Net net,
                                                          List<String> classNames,
                                                          List<retureBoxInfo> retureBoxInfos,
                                                          NetPush netPush) {
        List<FinalDetectionResult> finalResults = new ArrayList<>();
        float confThreshold = 0.35f;  // ROI内可以降低阈值
        float nmsThreshold = 0.4f;

        for (int roiIndex = 0; roiIndex < retureBoxInfos.size(); roiIndex++) {
            retureBoxInfo personBox = retureBoxInfos.get(roiIndex);

            log.info("处理ROI[{}]: x={}, y={}, w={}, h={}",
                    roiIndex, personBox.getX(), personBox.getY(),
                    personBox.getWidth(), personBox.getHeight());

            // 1. 扩展ROI区域（重要：包含更多上下文）
            Rect expandedROI = expandROI(personBox, image, 1.3);  // 扩展30%

            if (expandedROI.area() < 2500) {  // 最小50x50像素
                log.warn("ROI[{}]面积过小，跳过", roiIndex);
                continue;
            }

            // 2. 裁剪ROI
            Mat roiMat = new Mat(image, expandedROI);
            Size originalROISize = roiMat.size();
            // ✅ 保存调试图（可选）
            if (netPush.getIsBeforZoom() == 0) {
                saveROIForDebug(roiMat, roiIndex, netPush.getUploadPath());
            }
            // 3. 放大到640x640（关键：让小目标变大）
            Mat resizedROI = letterboxResize(roiMat, 640, 640);

            // 4. 对ROI进行检测
            Mat blob = Dnn.blobFromImage(resizedROI, 1.0 / 255.0,
                    new Size(640, 640),
                    new Scalar(0, 0, 0), true, false, CvType.CV_32F);
            net.setInput(blob);

            List<Mat> outputs = new ArrayList<>();
            net.forward(outputs, net.getUnconnectedOutLayersNames());

            // 5. 解析检测结果
            List<Rect2d> boxes = new ArrayList<>();
            List<Float> confidences = new ArrayList<>();
            List<Integer> classIds = new ArrayList<>();

            parseYOLOOutputs(outputs, boxes, confidences, classIds, confThreshold, classNames.size());

            log.info("ROI[{}]检测到{}个候选框", roiIndex, boxes.size());

            // 6. NMS去重
            if (!boxes.isEmpty()) {
                MatOfRect2d boxesMat = new MatOfRect2d();
                boxesMat.fromList(boxes);
                MatOfFloat confMat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
                MatOfInt indices = new MatOfInt();

                Dnn.NMSBoxes(boxesMat, confMat, confThreshold, nmsThreshold, indices);
                int[] indicesArray = indices.toArray();

                log.info("ROI[{}]经NMS后保留{}个检测框", roiIndex, indicesArray.length);

                // 7. 坐标转换：模型坐标 -> ROI坐标 -> 原图坐标
                for (int idx : indicesArray) {
                    Rect2d box = boxes.get(idx);

                    // 计算letterbox参数
                    double scale = Math.min(640.0 / originalROISize.width,
                            640.0 / originalROISize.height);
                    double dx = (640 - originalROISize.width * scale) / 2;
                    double dy = (640 - originalROISize.height * scale) / 2;

                    // 还原到ROI原始坐标
                    double roiX = (box.x - dx) / scale;
                    double roiY = (box.y - dy) / scale;
                    double roiW = box.width / scale;
                    double roiH = box.height / scale;

                    // 转换到原图坐标
                    double originalX = roiX + expandedROI.x;
                    double originalY = roiY + expandedROI.y;

                    // 边界检查
                    originalX = Math.max(0, Math.min(originalX, image.cols() - 1));
                    originalY = Math.max(0, Math.min(originalY, image.rows() - 1));
                    roiW = Math.min(roiW, image.cols() - originalX);
                    roiH = Math.min(roiH, image.rows() - originalY);

                    FinalDetectionResult result = new FinalDetectionResult();
                    result.x = originalX;
                    result.y = originalY;
                    result.width = roiW;
                    result.height = roiH;
                    result.confidence = confidences.get(idx);
                    result.classId = classIds.get(idx);
                    result.className = classNames.get(classIds.get(idx));
                    result.fromROIIndex = roiIndex;
                    result.personBox = personBox;  // 保存关联的人体框

                    finalResults.add(result);

                    log.info("检测到: {} (置信度:{:.2f}) 原图坐标:({:.1f},{:.1f},{:.1f},{:.1f})",
                            result.className, result.confidence,
                            originalX, originalY, roiW, roiH);
                }
            }

            // 释放资源
            roiMat.release();
            resizedROI.release();
        }

        return finalResults;
    }


    /**
     * 保存ROI用于调试
     */
    private void saveROIForDebug(Mat roiMat, int roiIndex, String uploadPath) {
        try {
            String debugPath = uploadPath + File.separator + "debug" + File.separator;
            File debugDir = new File(debugPath);
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }
            long count = Files.list(Paths.get(debugPath)).filter(Files::isRegularFile).count();
            if(count>50000){
                log.info("裁剪图片大于50000就删除 以免磁盘满");
                //删除所有重新存储
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(debugPath))) {
                        paths.filter(Files::isRegularFile)
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .limit(5000)
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }
            String filename = debugPath + "roi_" + roiIndex + "_" +
                    System.currentTimeMillis() + ".jpg";
            Imgcodecs.imwrite(filename, roiMat);
            log.debug("ROI[{}]已保存至: {}", roiIndex, filename);
        } catch (Exception ex) {
            log.error("保存ROI失败", ex);
        }
    }
    /**
     * 扩展ROI区域
     */
    private Rect expandROI(retureBoxInfo box, Mat image, double expandRatio) {
        int centerX = (int) (box.getX() + box.getWidth() / 2);
        int centerY = (int) (box.getY() + box.getHeight() / 2);

        int newWidth = (int) (box.getWidth() * expandRatio);
        int newHeight = (int) (box.getHeight() * expandRatio);

        int newX = centerX - newWidth / 2;
        int newY = centerY - newHeight / 2;

        // 边界裁剪
        newX = Math.max(0, newX);
        newY = Math.max(0, newY);
        newWidth = Math.min(image.cols() - newX, newWidth);
        newHeight = Math.min(image.rows() - newY, newHeight);

        return new Rect(newX, newY, newWidth, newHeight);
    }

    /**
     * 解析YOLO输出（支持v5/v8/v11格式）
     */
    private void parseYOLOOutputs(List<Mat> outputs, List<Rect2d> boxes,
                                  List<Float> confidences, List<Integer> classIds,
                                  float confThreshold, int expectedClassCount) {

        for (Mat output : outputs) {
            int dims = output.dims();
            long dim0 = output.size(0);
            long dim1 = output.size(1);
            long dim2 = output.size(2);

            Mat detectionMat;
            int rows, cols;
            boolean hasObjectness;

            // 格式识别（复用原有逻辑）
            if (dims == 3) {
                if (dim1 < 100 && dim2 > 1000) {
                    // YOLOv11: [1, C, N]
                    Mat reshaped = output.reshape(1, (int) dim1);
                    Mat transposed = new Mat();
                    Core.transpose(reshaped, transposed);
                    detectionMat = transposed;
                    rows = (int) dim2;
                    cols = (int) dim1;
                    hasObjectness = false;
                } else {
                    // YOLOv5: [1, N, C]
                    detectionMat = output.reshape(1, (int) dim1);
                    rows = (int) dim1;
                    cols = (int) dim2;
                    hasObjectness = (cols > 5);
                }
            } else {
                continue;
            }

            int scoresStartCol = hasObjectness ? 5 : 4;

            for (int i = 0; i < rows; i++) {
                Mat detection = detectionMat.row(i);

                Mat scores = detection.colRange(scoresStartCol, cols);
                Core.MinMaxLocResult minMaxResult = Core.minMaxLoc(scores);
                float maxClassScore = (float) minMaxResult.maxVal;
                int classId = (int) minMaxResult.maxLoc.x;

                float confidence;
                if (hasObjectness) {
                    float objectness = (float) detection.get(0, 4)[0];
                    confidence = objectness * maxClassScore;
                } else {
                    confidence = maxClassScore;
                }

                if (confidence > confThreshold) {
                    float centerX = (float) detection.get(0, 0)[0];
                    float centerY = (float) detection.get(0, 1)[0];
                    float width = (float) detection.get(0, 2)[0];
                    float height = (float) detection.get(0, 3)[0];

                    boxes.add(new Rect2d(centerX - width/2, centerY - height/2, width, height));
                    confidences.add(confidence);
                    classIds.add(classId);
                }
            }
        }
    }

    /**
     * 处理检测结果（区域过滤、绘制、推送）
     */
    private boolean processDetectionResults(List<FinalDetectionResult> allDetections,
                                            Mat image, TabAiSubscriptionNew pushInfo,
                                            NetPush netPush, RedisTemplate redisTemplate,
                                            String uploadpath, TabAiModel tabAiModel,
                                            Long startTime) {

        if (allDetections.isEmpty()) {
            log.warn("未检测到任何目标");
            if (netPush.getWarinngMethod() == 1) {
                String savepath = uploadpath + File.separator + "push" + File.separator;
                String saveName = savepath + System.currentTimeMillis() + ".jpg";
                Imgcodecs.imwrite(saveName, image);
                isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel,
                        netPush.getNoDifText(), 1, netPush.getNoDifText(),
                        netPush.getNoDifText(), savepath);
            }
            return false;
        }

        String audioText = "";
        Integer warnNumber = 0;
        String warnText = "";
        String warnName = "";
        int drawCount = 0;

        for (FinalDetectionResult det : allDetections) {
            // 区域过滤（保留原有逻辑）
            if (netPush.getIsBy() == 0) {

                TabVideoUtil videoUtil = netPush.getTabVideoUtil();
                boolean inArea;

                // 关键修复：将640×640坐标转换为原图坐标
                if (videoUtil.getBzType() == null) {

                    inArea = isPointInArea(det.x, det.y,
                            Double.parseDouble(videoUtil.getCanvasStartx()),
                            Double.parseDouble(videoUtil.getCanvasStarty()),
                            Double.parseDouble(videoUtil.getCanvasWidth()),
                            Double.parseDouble(videoUtil.getCanvasHeight()));
                } else {
                    // 新版多形状区域判断（需要解析shapeData并转换坐标）
                    inArea = isPointInShapeData(det.x, det.y, videoUtil.getShapeData());
                }
                if (!inArea) {
                    log.info("[不在指定区域内，跳过]");
                    continue;
                }
            }

            // 获取配置
            TabAiBase aiBase = VideoSendReadCfg.map.get(det.className);
            if (aiBase == null) {
                aiBase = new TabAiBase();
                aiBase.setChainName(det.className);
            } else {
                if (StringUtils.isNotEmpty(aiBase.getSpaceThree()) &&
                        aiBase.getSpaceThree().equals("N")) {
                    log.warn("【当前不推送：{}】", det.className);
                    continue;
                }
            }

            Scalar color = getColor(aiBase.getRgbColor());

            // 绘制检测框
            Imgproc.rectangle(image,
                    new Point(det.x, det.y),
                    new Point(det.x + det.width, det.y + det.height),
                    color, 3);

            String label = String.format("%s %.2f", aiBase.getChainName(), det.confidence);
            if (det.fromROIIndex >= 0) {
                label += " [ROI" + det.fromROIIndex + "]";  // 标记来自哪个ROI
            }
            image = AIModelYolo3.addChineseText(image, label, new Point(det.x, det.y), color);

            audioText += aiBase.getRemark() + aiBase.getSpaceOne();
            warnNumber += aiBase.getSpaceTwo() == null ? 1 : aiBase.getSpaceTwo();
            warnText = setNmsName(warnText, StringUtils.isEmpty(aiBase.getRemark()) ?
                    aiBase.getChainName() : aiBase.getRemark());
            warnName = setNmsName(warnName, aiBase.getChainName());

            drawCount++;
        }

        if (warnNumber <= 0) {
            log.error("【所有检测都被过滤，不推送】");
            return false;
        }

        // 保存和推送
        String savepath = uploadpath + File.separator + "push" + File.separator;
        File file = new File(savepath);
        if (!file.exists()) {
            file.mkdirs();
        }

        String saveName = savepath + System.currentTimeMillis() + ".jpg";
        Imgcodecs.imwrite(saveName, image);

        Long endTime = System.currentTimeMillis();
        log.info("检测完成，用时: {}ms，绘制{}个目标", (endTime - startTime), drawCount);

        redisTemplate.opsForValue().set(netPush.getId(), System.currentTimeMillis(),
                Long.parseLong(pushInfo.getEventNumber()),
                TimeUnit.SECONDS);

        isOk(pushInfo, netPush, redisTemplate, saveName, tabAiModel,
                audioText, warnNumber, warnText, warnName, savepath);

        return true;
    }

    private double scaleCoordinate(double coord, int modelSize, double originalSize) {
        return coord * (originalSize / modelSize);
    }
    private boolean isPointInShapeData(double x640, double y640, String shapeDataJson) {
        try {
            JSONObject shapeData = JSON.parseObject(shapeDataJson);

            // 获取原图尺寸
            int imageWidth = shapeData.getIntValue("imageWidth");
            int imageHeight = shapeData.getIntValue("imageHeight");

            // ✅ 坐标转换：640×640 → 原图尺寸
            double xOriginal = scaleCoordinate(x640, 640, imageWidth);
            double yOriginal = scaleCoordinate(y640, 640, imageHeight);

            log.debug("坐标转换: 640系({}, {}) → 原图系({}, {}) [原图尺寸: {}×{}]",
                    x640, y640, xOriginal, yOriginal, imageWidth, imageHeight);

            // 获取所有形状
            JSONArray shapes = shapeData.getJSONArray("shapes");
            if (shapes == null || shapes.isEmpty()) {
                log.warn("shapeData 中没有定义任何形状");
                return false;
            }

            // 遍历所有形状，只要在任意一个形状内就返回true
            for (int i = 0; i < shapes.size(); i++) {
                JSONObject shape = shapes.getJSONObject(i);
                String type = shape.getString("type");
                JSONObject coordinates = shape.getJSONObject("coordinates");

                boolean inThisShape = false;

                if ("rect".equals(type)) {
                    // 矩形判断
                    double startX = coordinates.getDoubleValue("startX");
                    double startY = coordinates.getDoubleValue("startY");
                    double endX = coordinates.getDoubleValue("endX");
                    double endY = coordinates.getDoubleValue("endY");

                    inThisShape = xOriginal >= startX && xOriginal <= endX
                            && yOriginal >= startY && yOriginal <= endY;

                    log.debug("矩形{} 判断: ({}, {}) in [{}, {}] - [{}, {}] = {}",
                            i + 1, xOriginal, yOriginal, startX, startY, endX, endY, inThisShape);

                } else if ("polygon".equals(type)) {
                    // 多边形判断（使用射线法）
                    JSONArray points = coordinates.getJSONArray("points");
                    inThisShape = isPointInPolygon(xOriginal, yOriginal, points);

                    log.debug("多边形{} 判断: ({}, {}) 顶点数={} 结果={}",
                            i + 1, xOriginal, yOriginal, points.size(), inThisShape);
                }

                if (inThisShape) {
                    log.info("✅ 点在区域内 - {}{}内", type.equals("rect") ? "矩形" : "多边形", i + 1);
                    return true;
                }
            }

            log.info("❌ 点不在任何定义的区域内");
            return false;

        } catch (Exception e) {
            log.error("解析 shapeData 失败", e);
            return false;
        }
    }
    /**
     * 检测结果封装类
     */
    static class FinalDetectionResult {
        double x, y, width, height;
        float confidence;
        int classId;
        String className;
        int fromROIIndex;  // -1表示全图检测，>=0表示来自第几个ROI
        retureBoxInfo personBox;  // 关联的人体框
    }

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

    public static boolean isPointInArea(double px, double py, double x, double y, double width, double height) {
        double x2 = x + width;
        double y2 = y + height;

        // 检查点是否在区域内
        return px >= x && px <= x2 && py >= y && py <= y2;
    }
    public static boolean isPointInArea(double px, double py, String json) {
        JSONObject object = JSONObject.parseObject(json);
        JSONArray jsonArray = object.getJSONArray("shapes");

        // 遍历所有形状，只要点在任意一个形状内就返回 true
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject shape = jsonArray.getJSONObject(i);
            String type = shape.getString("type");
            JSONObject coordinates = shape.getJSONObject("coordinates");

            if ("rect".equals(type)) {
                // 矩形判定
                double startX = coordinates.getDoubleValue("startX");
                double startY = coordinates.getDoubleValue("startY");
                double endX = coordinates.getDoubleValue("endX");
                double endY = coordinates.getDoubleValue("endY");

                // 判断点是否在矩形内
                if (px >= startX && px <= endX && py >= startY && py <= endY) {
                    return true;
                }

            } else if ("polygon".equals(type)) {
                // 多边形判定 - 使用射线法
                JSONArray points = coordinates.getJSONArray("points");
                if (isPointInPolygon(px, py, points)) {
                    return true;
                }
            }
        }

        return false;
    }
    /**
     * 射线法判断点是否在多边形内
     * 原理：从点向右发射一条射线，计算与多边形边界的交点数
     * 交点数为奇数则点在多边形内，偶数则在外
     */
    private static boolean isPointInPolygon(double px, double py, JSONArray points) {
        int n = points.size();
        boolean inside = false;

        for (int i = 0, j = n - 1; i < n; j = i++) {
            JSONObject pi = points.getJSONObject(i);
            JSONObject pj = points.getJSONObject(j);

            double xi = pi.getDoubleValue("x");
            double yi = pi.getDoubleValue("y");
            double xj = pj.getDoubleValue("x");
            double yj = pj.getDoubleValue("y");

            // 判断射线是否与边相交
            if (((yi > py) != (yj > py)) &&
                    (px < (xj - xi) * (py - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }

        return inside;
    }

    public String setNmsName(String WareText,String name){

        if (WareText == null || WareText.isEmpty()) {
            // WareText为空时，直接返回name
            return name;
        }
        log.info("[当前内容{}:替换内容{}]",WareText,name);
        if (WareText.contains(name)) {
        // 已经包含，不拼接
            return WareText;
        }
        return WareText + "," + name;

    }

    public void setErrorImg(Mat image,String txt){
        String saveName="D://error";
        try {
            log.info("错误存储地址{}", saveName);
            File imageFile = new File(saveName);
            if (!imageFile.exists()) {
                imageFile.mkdirs();
            }
            long count = Files.list(Paths.get(saveName)).filter(Files::isRegularFile).count();
            if(count>10000){
                log.info("错误文件大于5000不再存储 以免磁盘满");
                //只删 2000 张最旧的
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(saveName))) {
                        paths.filter(Files::isRegularFile)
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .limit(5000)
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }

            Imgcodecs.imwrite(saveName+"/"+txt+System.currentTimeMillis()+".jpg", image);
        }catch (Exception exception){
            exception.printStackTrace();
        }
    }

    public static void setTestImg(Mat image,String txt){
        String saveName="D://error/test";
        try {
            log.info("错误存储地址{}", saveName);
            File imageFile = new File(saveName);
            if (!imageFile.exists()) {
                imageFile.mkdirs();
            }
            long count = Files.list(Paths.get(saveName)).filter(Files::isRegularFile).count();
            if(count>10000){
                log.info("错误文件大于5000不再存储 以免磁盘满");
                //只删 2000 张最旧的
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(saveName))) {
                        paths.filter(Files::isRegularFile)
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .limit(5000)
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }

            Imgcodecs.imwrite(saveName+"/"+txt+System.currentTimeMillis()+".jpg", image);
        }catch (Exception exception){
            exception.printStackTrace();
        }
    }
    //不通过的图片
    public void setBeforeImg(Mat image,String txt){
        String saveName="D://error//"+txt;
        try {
            log.info("错误存储地址{}", saveName);
            File imageFile = new File(saveName);
            if (!imageFile.exists()) {
                imageFile.mkdirs();
            }
            long count = Files.list(Paths.get(saveName)).filter(Files::isRegularFile).count();
            if(count>10000){
                log.info("不通过前置图片文件大于10000不再存储 以免磁盘满");
                //删除所有重新存储
                new Thread(() -> {
                    try (Stream<Path> paths = Files.list(Paths.get(saveName))) {
                        paths.filter(Files::isRegularFile)
                                .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                                .limit(5000)
                                .forEach(path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                });
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }

            SimpleDateFormat simpleDateFormat=new SimpleDateFormat("yyyyMMddHHmmss");
            Random random = new Random();
            int number = 10000 + random.nextInt(90000); // 10000 ~ 99999
            String c=simpleDateFormat.format(new Date())+number;
            log.info("当前文件名称{}",c);
            Imgcodecs.imwrite(saveName+"/"+txt+c+".jpg", image);
        }catch (Exception exception){
            exception.printStackTrace();
            log.info("【存储错误】");
        }
    }

    /***
     * 是否推送 /并录像
     * @param pushInfo
     * @param netPush
     * @param redisTemplate
     * @param saveName
     * @param tabAiModel
     * @param audioText
     * @param warnNumber
     * @param warnText
     * @param warnName
     * @param savePath
     * @return
     */
    public boolean isOk(TabAiSubscriptionNew pushInfo, NetPush netPush, RedisTemplate redisTemplate, String saveName, TabAiModel tabAiModel,
                        String audioText,
                        Integer warnNumber,
                        String warnText,
                        String warnName,
                        String savePath
    ) {

        log.warn("model{}-{}", tabAiModel.getAiName(), warnText);
        Thread t = new Thread(() -> {
            try {


                String base64Img = base64Image(saveName);
                //组装参数
                pushEntity push = new pushEntity();
                push.setCameraName(pushInfo.getName());
                push.setType("图片");
                push.setCameraUrl(pushInfo.getBeginEventTypes());
                push.setAlarmPicData(base64Img);
                push.setTime(System.currentTimeMillis() + "");
                push.setModelId(tabAiModel.getAiName());
                push.setIndexCode(pushInfo.getIndexCode());
                push.setModelName(warnName);
                push.setAiNumber(warnNumber);
                push.setModelText(warnText);


                String recordVideo = "";
                //是否录像
                if (netPush.getTabAiVideoSetting().getIsRecording() == 0) {
                    log.info("开启录像 录像时常{}", netPush.getTabAiVideoSetting().getRecordTime());
                    long recordTime = netPush.getTabAiVideoSetting().getRecordTime();
                    recordVideo = RecordVideo(pushInfo.getBeginEventTypes(), savePath, recordTime, netPush.getId());
                    if (StringUtils.isNotEmpty(recordVideo)) {
                        log.error("录像完成:{}", recordVideo);
                        if (netPush.getTabAiVideoSetting().getIsAnalysis() == 0) { //是否开启分析
                            //需要分析录像视频逐帧分析
                            log.error("开始分析视频");
                            recordVideo = analysisVideo(recordVideo, netPush, savePath);
                        }
                    }
                } else {
                    log.info("[未开启录像]");
                }

                //`临时使用播报
                if(netPush.getTabAiVideoSetting().getIsAudio() == 0){
                    log.info("开始播报！~~~~~~播报内容！~~~~~~{}",audioText);
                    if(StringUtils.isNotEmpty(audioText)){
                        TabAudioDevice tabAudioDevice=new TabAudioDevice();
                        tabAudioDevice.setDeivceUrl("http://192.168.0.160:8307");
                        String token= getToken(tabAudioDevice);
                        postAudioText(token,tabAudioDevice,audioText);
                    }
                }

                if (pushInfo.getPushStatic() == 0) {// 0 开启 1未开启
                    log.info("[已经开启推送第三方]：");

                    if(StringUtils.isNotEmpty(recordVideo)){
                        String base64Mp4 = base64Image(recordVideo);
                        push.setVideo(base64Mp4);
                    }
                    JSONObject ob = RestUtil.post(pushInfo.getEventUrl(), (JSONObject) JSONObject.toJSON(push));
                    log.info("返回内容：" + ob);

                } else {
                    log.info("[当前设置为：不推送第三方]");
                }

                if(netPush.getTabAiVideoSetting().getSaveLocalhost()==0){ //保存到本地
                    log.info("[本地也保存]");
                    push.setAlarmPicData(saveName);
                    push.setVideo(recordVideo);
                    // 获取 pushInfo 的路径部分

                    JSONObject ob = RestUtil.post("http://127.0.0.1:9998/wgai/video/tabAiWarning/addPush", (JSONObject) JSONObject.toJSON(push));

                    log.info("返回内容：" + ob);
                }else{
                    log.info("[不保存本地录像]");
                    File imageFile = new File(recordVideo);
                    if (imageFile.exists()) {
                        imageFile.delete();
                    }
                }



            } catch (Exception exception) {
                exception.printStackTrace();
                log.error("[推送失败：{}]", pushInfo.getId());
            }
        });
        t.start();

        log.error("推送结束-间隔时间{}-{}", pushInfo.getId());
        return true;
    }


    //开始录像
    public String RecordVideo(String videoUrl, String savePath, long time, String id) {
        String path = savePath + id + "_" + System.currentTimeMillis() + ".mp4";
        try {

            // 创建抓取器
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoUrl);
            grabber.setOption("rtsp_transport", "tcp"); // 避免 UDP 丢包
            grabber.setOption("stimeout", "3000000");   // 设置超时时间（可选）
            grabber.start();

            // 创建录制器
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    path,
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getAudioChannels()
            );
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("mp4");
            recorder.setFrameRate(grabber.getFrameRate() > 0 ? grabber.getFrameRate() : 25);
            recorder.setVideoBitrate(2000000); // 2Mbps，可调
            recorder.start();

            long startTime = System.currentTimeMillis();
            long recordDuration = time * 1000; // 默认ms *1000 =s

            Frame frame;
            while ((frame = grabber.grab()) != null) {
                recorder.record(frame);
                if (System.currentTimeMillis() - startTime > recordDuration) {
                    break;
                }
            }

            recorder.stop();
            recorder.release();
            grabber.stop();
            grabber.release();
            log.info("录制完成保存为{}", path);
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("[出错了检查一下]");
            return "";
        }


        return path;

    }

    //分解录像
    public synchronized String analysisVideo(String recoredPath, NetPush netPush, String savePath) {
        try {
            String saveMp4Path = recoredPath.substring(0, recoredPath.lastIndexOf("."));
            //    Thread tt = new Thread(() -> {
            log.info("当前开始分解录像{}", saveMp4Path);
            File file = new File(saveMp4Path);
            if (!file.exists()) {
                file.mkdirs();
            }
            saveMp4Path = saveMp4Path + "/avi.mp4";
            VideoCapture capture = new VideoCapture(recoredPath, Videoio.CAP_ANY);
            if (!capture.isOpened()) {
                log.info("Error: Unable to open video file.");
            }
            double fps = capture.get(Videoio.CAP_PROP_FPS);
            double widthVideo = capture.get(Videoio.CAP_PROP_FRAME_WIDTH);
            double heightVideo = capture.get(Videoio.CAP_PROP_FRAME_HEIGHT);
            double frameCount = capture.get(Videoio.CAP_PROP_FRAME_COUNT);
            // 创建 VideoWriter 对象
            VideoWriter writer = new VideoWriter();
            int[] codecs = {
                    VideoWriter.fourcc('X', 'V', 'I', 'D'),
                    VideoWriter.fourcc('M', 'J', 'P', 'G'),
                    VideoWriter.fourcc('a', 'v', 'c', '1'),
            };
            for (int codec : codecs) {
                writer.open(saveMp4Path, codec, fps, new Size(widthVideo, heightVideo), true);
                if (writer.isOpened()) {
                    log.info("打开成功，使用 codec：" + codec);
                    break;
                } else {
                    log.info("打开失败 codec：" + codec);
                }
            }
            Mat image = new Mat();
            Net net = netPush.getNet();
            List<String> classNames = netPush.getClaseeNames();
            int a = 0;
            while (capture.read(image)) {

                log.info("当前帧:{}",a++);

                // 将图像传递给模型进行目标检测
                Mat blob = Dnn.blobFromImage(image, 1.0 / 255, new Size(640, 640), new Scalar(0), true, false);
                net.setInput(blob);
                // 将图像传递给模型进行目标检测
                List<Mat> result = new ArrayList<>();
                List<String> outBlobNames = net.getUnconnectedOutLayersNames();
                net.forward(result, outBlobNames);

                // 处理检测结果
                float confThreshold = 0.42f;
                float nmsThreshold = 0.41f;
                List<Rect2d> boxes2d = new ArrayList<>();
                List<Float> confidences = new ArrayList<>();
                List<Integer> classIds = new ArrayList<>();

                for (Mat output : result) {
                    int dims = output.dims();
                    int index = (int) output.size(0);
                    int rows = (int) output.size(1);
                    int cols = (int) output.size(2);
                    //
                    // Dims: 3, Rows: 25200, Cols: 8 row,Mat [ 1*25200*8*CV_32FC1, isCont=true, isSubmat=false, nativeObj=0x28dce2da990, dataAddr=0x28dd0ebc640 ]index:1
                    //    log.info("Dims: " + dims + ", Rows: " + rows + ", Cols: " + cols+" row,"+output.row(0)+"index:"+index);
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
                            //  log.info("识别到了");
                        }
                    }
                }

                if (confidences.size() <= 0||confidences.size()>200) {
                    log.warn("录像当前未检测到内容");
                }
                // 执行非最大抑制，消除重复的边界框
                MatOfRect2d boxes_mat = new MatOfRect2d();
                boxes_mat.fromList(boxes2d);
                log.info("confidences.size{}", confidences.size());
                MatOfFloat confidences_mat = new MatOfFloat(Converters.vector_float_to_Mat(confidences));
                MatOfInt indices = new MatOfInt();
                Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
                if (!boxes_mat.empty() && !confidences_mat.empty()) {
                    log.info("不为空");
                    Dnn.NMSBoxes(boxes_mat, confidences_mat, confThreshold, nmsThreshold, indices);
                }

                int[] indicesArray = indices.toArray();
                // 获取保留的边界框

                log.info(confidences.size() + "类别下标啊" + indicesArray.length);
                if(indicesArray.length>50){
                    log.error("怎么可能类别太大 20就是上限");
                    writer.write(image);
                    continue;
                }
                // 在图像上绘制保留的边界框
                int c = 0;
                for (int idx : indicesArray) {
                    // 添加类别标签
                    Rect2d box = boxes2d.get(idx);
                    Integer ab = classIds.get(idx);
                    String name = classNames.get(ab);
                    float conf = confidences.get(idx);
                    double x = box.x;
                    double y = box.y;
                    double width = box.width * ((double) image.cols() / 640);
                    double height = box.height * ((double) image.rows() / 640);
                    double xzb = x * ((double) image.cols() / 640);
                    double yzb = y * ((double) image.rows() / 640);

                    TabAiBase aiBase = VideoSendReadCfg.map.get(name);
                    if (aiBase == null) {
                        aiBase = new TabAiBase();
                        aiBase.setChainName(name);

                    }
                    // Imgproc.rectangle(image, new Point(box.x, box.y), new Point(box.x + box.width, box.y + box.height),CommonColors(c), 2);
                    Imgproc.rectangle(image,
                            new Point(xzb, yzb),
                            new Point(xzb + width, yzb + height),
                            CommonColors(c), 2);
                    //    log.info( "类别下标"+ab);
                    image = AIModelYolo3.addChineseText(image, aiBase.getChainName() + conf, new Point(xzb, yzb), CommonColors(c));
                    //  Imgproc.putText(image, classNames.get(ab), new Point(box.x, box.y - 5), Core.FONT_HERSHEY_SIMPLEX, 0.5, CommonColors(c), 1);
                    c++;
                }

                writer.write(image);

            }

            writer.release();
            capture.release();
            log.error("视频合成完成：");
//            });
//            tt.start();
//            tt.join();
            return saveMp4Path + "/avi.mp4";
        } catch (Exception ex) {
            ex.printStackTrace();
            log.error("录制失败");
            return "";
        }


    }


    public Scalar getColor(String color){
        if(StringUtils.isNotEmpty(color)){
            String[] parts = color.split(",");
            log.info("颜色内容{}-数组长度{}",color,parts.length);
            if(parts.length<3){
                return  CommonColors(1);
            }
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());

// 注意：OpenCV 中是 BGR 顺序
            Scalar scalar = new Scalar(b, g, r);
            return  scalar;
        }else{
            return  CommonColors(1);
        }

    }

}
