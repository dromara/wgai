package org.jeecg.modules.demo.tab.service.impl;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import net.ailemon.asrt.sdk.BaseSpeechRecognizer;
import net.ailemon.asrt.sdk.Sdk;
import net.ailemon.asrt.sdk.common.Common;
import net.ailemon.asrt.sdk.models.AsrtApiResponse;
import net.ailemon.asrt.sdk.models.Wave;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.*;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.audio.entity.TabAuditSetting;
import org.jeecg.modules.demo.audio.entity.TabKeyWords;
import org.jeecg.modules.demo.audio.mapper.TabAudioDeviceMapper;
import org.jeecg.modules.demo.audio.mapper.TabKeyWordsMapper;
import org.jeecg.modules.demo.audio.service.ITabKeyWordsService;
import org.jeecg.modules.demo.tab.entity.TabAiBase;
import org.jeecg.modules.demo.tab.entity.TabAiHistory;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.tab.entity.TabAiSubscription;
import org.jeecg.modules.demo.tab.mapper.TabAiBaseMapper;
import org.jeecg.modules.demo.tab.mapper.TabAiHistoryMapper;
import org.jeecg.modules.demo.tab.service.ITabAiHistoryService;
import org.jeecg.modules.demo.tab.util.FfmpegPcmLoader;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.service.impl.TabVideoUtilServiceImpl;
import org.jeecg.modules.demo.video.util.RedisCacheHolder;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.monitor.service.RedisService;
import org.jeecg.modules.system.controller.wavUtil;
import org.jeecg.modules.system.entity.SysAnnouncementSend;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.OnnxModelWrapper;
import org.jeecg.modules.tab.AIModel.VideoSendReadCfg;
import org.jeecg.modules.tab.AIModel.push.AiImgResult;
import org.jeecg.modules.tab.AIModel.push.ReadVideoImg;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.mapper.TabAiModelMapper;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import javax.annotation.Resource;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.jeecg.modules.demo.audio.util.audioSend.getToken;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.bufferedImageToMat;
import static org.jeecg.modules.tab.AIModel.AIModelYolo3.converterToFrame;

/**
 * @Description: AI识别结果历史
 * @Author: WGAI
 * @Date:   2024-03-13
 * @Version: V1.0
 */
@Service
@Slf4j
public class TabAiHistoryServiceImpl extends ServiceImpl<TabAiHistoryMapper, TabAiHistory> implements ITabAiHistoryService {
    @Autowired
    TabAudioDeviceMapper tabAudioDeviceMapper;
    @Autowired
    TabAiBaseMapper tabAiBaseMapper;
    @Autowired
    TabAiModelMapper modelMapper;
    @Autowired
    TabAiHistoryMapper tabAiHistoryMapper;

    @Autowired
    ITabKeyWordsService iTabKeyWordsService;

    @Resource
    WebSocket webSocket;
    @Resource
    RedisUtil redisUtil;

    @Resource
    RedisTemplate redisTemplate;
    @Value(value = "${audio.path}")
    String pathStatic;//="F:\\JAVAAI\\audio\\sherpa-onnx-conformer-zh-stateless2-2023-05-23\\";

    public String WGAIAudio="wgaiaudio";

    @Override
    public Result<?> aiAudioSetting(TabAuditSetting tabAuditSetting, String audioPath, String uplopadPath) {
        try {
            String waveFilename = uplopadPath + File.separator + audioPath;

            // 直接拿到 float[],不再用 waveInt16 + WaveReader
            FfmpegPcmLoader.AudioData audio = FfmpegPcmLoader.load(waveFilename);
            log.info("【samples.length={}, sampleRate={}, 时长={}s】",
                    audio.samples.length, audio.sampleRate,
                    audio.samples.length / (float) audio.sampleRate);

            OfflineTransducerModelConfig transducer =
                    OfflineTransducerModelConfig.builder()
                            .setEncoder(uplopadPath + File.separator + tabAuditSetting.getEncoderPath())
                            .setDecoder(uplopadPath + File.separator + tabAuditSetting.getDecoderPath())
                            .setJoiner(uplopadPath + File.separator + tabAuditSetting.getJoinerPath())
                            .build();
            OfflineModelConfig modelConfig =
                    OfflineModelConfig.builder()
                            .setTransducer(transducer)
                            .setTokens(uplopadPath + File.separator + tabAuditSetting.getTokenPath())
                            .setNumThreads(1)
                            .setDebug(true)
                            .setModelingUnit(tabAuditSetting.getModeLing())
                            .build();
            OfflineRecognizerConfig config =
                    OfflineRecognizerConfig.builder()
                            .setOfflineModelConfig(modelConfig)
                            .setDecodingMethod(tabAuditSetting.getDecodingMethod())
                            .setHotwordsFile(uplopadPath + File.separator + tabAuditSetting.getHotWord())
                            .setHotwordsScore(2.0f)
                            .build();

            OfflineRecognizer recognizer = new OfflineRecognizer(config);
            OfflineStream stream = recognizer.createStream();
            stream.acceptWaveform(audio.samples, audio.sampleRate);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText();
            System.out.printf("filename:%s\nresult:%s\n", waveFilename, text);
            stream.release();
            recognizer.release();
            log.info("当前结束时间{}", System.currentTimeMillis());
            return Result.OK(changeInfo(text));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return Result.error("识别失败");
    }

    @Override
    public Result<?> aiAudio(String path,String uplpadPath) {
//        String host = "43.142.3.140";
//        String port = "20001";
//        String protocol = "http";
//        BaseSpeechRecognizer sr = Sdk.GetSpeechRecognizer(host, port, protocol);
//        String filename = uplpadPath+ File.separator+path;
//        if(path.indexOf(WGAIAudio)<=-1){
//            Result<String> result=waveInt16(filename,uplpadPath, System.currentTimeMillis()+"_"+WGAIAudio+".wav");
//            if(result.isSuccess()){
//                filename= uplpadPath+ File.separator+result.getMessage();
//                log.info("【转换16通道音频完成 删除原版文件重新保存】");
//                //删除本地文件
//            }else{
//                log.error("【转换16通道音频出现问题】");
//            }
//
//        }
//        //"D:\\opt\\upFiles\\temp\\audio_1722564524738.wav";
//        // ============================================
//        // 直接调用ASRT识别语音文件
//        AsrtApiResponse rsp = sr.RecogniteFile(filename);
//        System.out.println(rsp.statusCode);
//        System.out.println(rsp.statusMessage);
//        System.out.println(rsp.result);
//return Result.OK(rsp.result);
        String encoder =
                pathStatic+"encoder-epoch-99-avg-1.int8.onnx";
        String decoder =
                pathStatic+"decoder-epoch-99-avg-1.onnx";
        String joiner =
                pathStatic+ "joiner-epoch-99-avg-1.onnx";
        String tokens =  pathStatic+"tokens.txt";

        String hotwords=pathStatic+"hotwords_cn.txt";

        String waveFilename = uplpadPath+ File.separator+path;;
        Result<String> result=waveInt16(waveFilename,uplpadPath, System.currentTimeMillis()+"_"+WGAIAudio+".wav");
        if(result.isSuccess()){
            waveFilename= uplpadPath+ File.separator+result.getMessage();
            log.info("【转换16通道音频完成 删除原版文件重新保存】");
        }else{
            log.error("【转换16通道音频出现问题】");
        }
        WaveReader reader = new WaveReader(waveFilename);
        OfflineTransducerModelConfig transducer =
                OfflineTransducerModelConfig.builder()
                        .setEncoder(encoder)
                        .setDecoder(decoder)
                        .setJoiner(joiner)
                        .build();
        OfflineModelConfig modelConfig =
                OfflineModelConfig.builder()
                        .setTransducer(transducer)
                        .setTokens(tokens)
                        .setNumThreads(1)
                        .setDebug(true)
                        .setModelingUnit("cjkchar")
                        .build();
        // .build();
        OfflineRecognizerConfig config =
                OfflineRecognizerConfig.builder()
                        .setOfflineModelConfig(modelConfig)
                        .setDecodingMethod("modified_beam_search")
                        .setHotwordsFile(hotwords)
                        .setHotwordsScore(5.0f)
                        .build();
        OfflineRecognizer recognizer = new OfflineRecognizer(config);
        OfflineStream stream = recognizer.createStream();
        stream.acceptWaveform(reader.getSamples(), reader.getSampleRate());
        recognizer.decode(stream);
        String text = recognizer.getResult(stream).getText();
        System.out.printf("filename:%s\nresult:%s\n", waveFilename, text);
        stream.release();
        recognizer.release();
        log.info("当前结束时间{}",System.currentTimeMillis());
        return Result.OK(changeInfo(text));
    }


    public  String  changeInfo(String text){
        log.info("未转换{}",text);
        if(text!=null){
            List<TabKeyWords> tabKeyWords=(List<TabKeyWords>) redisTemplate.opsForValue().get("KeyWord");
            for (TabKeyWords keyword:tabKeyWords) {
                text=  text.replaceAll(keyword.getKeyName(),keyword.getHotName());
            }
          return text;
        }
        return "";
    }
    public static void main(String[] args) {
        // TODO Auto-generated method stub
        String path="wgaiaudio";
        System.out.println(path.indexOf("wgaiaudio"));

        System.out.println(path.indexOf("222"));
//        String host = "43.142.3.140";
//        String port = "20001";
//        String protocol = "http";
//        BaseSpeechRecognizer sr = Sdk.GetSpeechRecognizer(host, port, protocol);
//        String filename = "D:\\opt\\upFiles\\temp\\audio_1722564524738.wav";
//        if(args.length > 0){
//            filename = args[0];
//        }

//
//        // ============================================
//        // 直接调用ASRT识别语音文件
//        AsrtApiResponse rsp = sr.RecogniteFile(filename);
//        System.out.println(rsp.statusCode);
//        System.out.println(rsp.statusMessage);
//        System.out.println(rsp.result);
//
//        // ============================================
//        // 调用ASRT识别语音序列
//        byte[] wavBytes = Common.readBinFile(filename);
//        Wave wav = new Wave();
//        wav.deserialize(wavBytes);
//        byte[] sampleBytes = wav.getRawSamples();
//        int sampleRate = wav.sampleRate;
//        int channels = wav.channels;
//        int byteWidth = wav.sampleWidth;
//        rsp = sr.Recognite(sampleBytes, sampleRate, channels, byteWidth);
//        System.out.println("zzz"+rsp.statusCode);
//        System.out.println("zzz"+rsp.statusMessage);
//        System.out.println("zzz"+rsp.result);
//
//        // ============================================
//        // 调用ASRT声学模型识别语音序列
//        wavBytes = Common.readBinFile(filename);
//        wav = new Wave();
//        wav.deserialize(wavBytes);
//        sampleBytes = wav.getRawSamples();
//        sampleRate = wav.sampleRate;
//        channels = wav.channels;
//        byteWidth = wav.sampleWidth;
//        rsp = sr.RecogniteSpeech(sampleBytes, sampleRate, channels, byteWidth);
//        System.out.println("jjj"+rsp.statusCode);
//        System.out.println("jjj"+rsp.statusMessage);
//        System.out.println("jjj"+rsp.result);
//
//        // ============================================
//        // 调用ASRT语言模型识别拼音序列1
//        String[] pinyins = ((String)rsp.result).split(", ");
//        rsp = sr.RecogniteLanguage(pinyins);
//        System.out.println("xxx"+rsp.statusCode);
//        System.out.println("xxx"+rsp.statusMessage);
//        System.out.println("xxx"+rsp.result);
//
//        // ============================================
//        // 调用ASRT语言模型识别拼音序列2
//        pinyins = new String[]{"ni3", "hao"};
//        rsp = sr.RecogniteLanguage(pinyins);
//        System.out.println("yyy"+rsp.statusCode);
//        System.out.println("yyy"+rsp.statusMessage);
//        System.out.println("yyy"+rsp.result);
     //   waveInt16("","");
    }

    @Override
    public int saveStr(TabAiModelBund tabAiModelBund,String path) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            String savePath=modelYolo3.imageStr(tabAiModelBund.getSaveUrl(),path);
            log.info("识别文字内容{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl(tabAiModelBund.getSaveUrl());
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistory.setSendMsg(savePath);
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }

    }

    @Override
    public int saveAudioStr(TabAiModelBund tabAiModelBund, String path) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());

        try {
            Result<?>  savePath=this.aiAudio(tabAiModelBund.getSaveUrl(),path);
            log.info("语音识别文字内容{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl(tabAiModelBund.getSaveUrl());
            tabAiHistory.setSendTime(b-a+"");
            if(savePath.isSuccess()){
                tabAiHistory.setSendMsg((String) savePath.getResult());
            }else{
                tabAiHistory.setSendMsg("未识别出");
            }
            tabAiHistory.setRemake(" 音频识别不显示图片");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
    }

    public  static  Result<String> waveInt16(String url,String path,String name){

        String ffmpegCommand = "ffmpeg -y -i "+url+" -ac 1 -ar 16000  "+path+File.separator+name;

        System.out.println(ffmpegCommand);
        try {
            // 启动进程
            Process process = Runtime.getRuntime().exec(ffmpegCommand);

            // 获取进程的输出流（标准输出和错误输出）
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String line;
            // 读取标准输出
            while ((line = stdInput.readLine()) != null) {
                System.out.println(line);
            }

            // 读取错误输出
            while ((line = stdError.readLine()) != null) {
                System.err.println(line);
            }

            // 等待进程完成
            int exitCode = process.waitFor();
            System.out.println("进程退出码: " + exitCode);

        } catch (Exception e) {
            e.printStackTrace();
            return Result.error("失败");
        }

        return Result.OK(name);
    }
    @Override
    public int saveCarIdentify(TabAiModelBund tabAiModelBund, String path) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            String savePath=modelYolo3.SendPicOpencvCar(tabAiModel1.getAiWeights(),tabAiModelBund.getSaveUrl(),null,path);
            if(savePath.equals("error")){
                return 1;
            }
            log.info("识别存储文件地址{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath);
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }

    }

    @Override
    public int saveCarIdentifyV5(TabAiModelBund tabAiModelBund, String path) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            Map<String,Object> savePath=modelYolo3.SendPicOpencvCarV5(tabAiModel1.getAiWeights(),tabAiModelBund.getSaveUrl(),null,path);
            log.info("savePath.get(\"isOk\"){}",savePath.get("isOk"));
            if((boolean)savePath.get("isOk")==false){
                return 1;
            }
            log.info("识别存储文件地址{}",savePath);
            log.info("识别存储文件color{}",savePath);
            log.info("识别存储文件plate{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath.get("url"));
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistory.setRemake(savePath.get("color")+"");
            tabAiHistory.setSendMsg(savePath.get("plate")+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
    }

    @Override
    public int saveIdentify(TabAiModelBund tabAiModelBund,String path) {

        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
           String savePath=modelYolo3.SendPicYoloV3(tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSaveUrl(),null,path);
           if(savePath.equals("error")){
               return 1;
           }
           log.info("识别存储文件地址{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath);
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }

    }

    @Override
    public int saveIdentifyYolov(TabAiModelBund tabAiModelBund, String path,String type) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            String savePath="error";
            boolean gpuFlag=false;
                if(tabAiModel1.getModelJmType()!=null&&tabAiModel1.getModelJmType()==1){
                    log.info("使用 GPU");
                    gpuFlag=true;
                }
           // if(type.equals("11")){
                log.info("yolov5-11开始预测节点---------------------");
                if(tabAiModel1.getModelDify()!=null&&tabAiModel1.getModelDify()==2){ // 1 图像识别  2 姿态识别  3 多边形识别  4图像分割 5.
                    if(tabAiModel1.getModelDifyType()!=null&&tabAiModel1.getModelDifyType()==20){
                        log.info("使用ONNX推理-姿态识别！！！");
                        savePath=modelYolo3.SendPicYoloV11ONNXPose(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);
                    }else{
                        log.info("使用opencV推理-姿态识别！！！");
                        savePath=modelYolo3.SendPicYoloV11CVPose(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path);
                    }
                }else if((tabAiModel1.getModelDify()!=null&&tabAiModel1.getModelDify()==4)||(tabAiModel1.getModelDify()!=null&&tabAiModel1.getModelDify()==3)){
                    if(tabAiModel1.getModelDifyType()!=null&&tabAiModel1.getModelDifyType()==20){
                        log.info("使用ONNX推理-图像分割！！！");
                        savePath=modelYolo3.SendPicOnnxYoloV11Seg(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);
                    }else{
                        log.info("使用opencV推理-图像分割！！！");
                        savePath=modelYolo3.SendPicYoloV11Seg(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);
                    }
                }else{
                    if(tabAiModel1.getModelDifyType()!=null&&tabAiModel1.getModelDifyType()==20){
                        log.info("使用ONNX推理！！！");
                        if(tabAiModel1.getModelDify()!=null&&tabAiModel1.getModelDify()==8){//人脸定位
                            log.info("人脸定位！");
                            savePath=modelYolo3.SendPicOnnxInsightFace(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);

                        }else{
                            savePath=modelYolo3.SendPicOnnxYoloV11(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);

                        }
                  }else{
                        log.info("使用opencV推理！！！");
                        savePath=modelYolo3.SendPicYoloV11(tabAiModel1,tabAiModelBund.getSaveUrl(),null,path,gpuFlag);
                    }

                }

//            }else{
//                savePath=modelYolo3.SendPicYoloV5(tabAiModel1.getAiWeights(),tabAiModel1.getAiNameName(),tabAiModelBund.getSaveUrl(),null,path);
//            }

            if(savePath.equals("error")){
                return 1;
            }
            log.info("识别存储文件地址{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath);
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
    }

    @Override
    public int saveIdentifyYolov8(TabAiModelBund tabAiModelBund, String path) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            String savePath=modelYolo3.SendPicYoloV5(tabAiModel1.getAiWeights(),tabAiModel1.getAiNameName(),tabAiModelBund.getSaveUrl(),null,path);
            if(savePath.equals("error")){
                return 1;
            }
            log.info("识别存储文件地址{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath);
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
    }

    @Override
    public int closedentify(TabAiModelBund tabAiModelBund, LoginUser sysUser) {
        //使用redis 设置关闭当前流 使用的用户关闭识别 超时时间设置3s失效


        TabAiModel aiModel=modelMapper.selectById(tabAiModelBund.getModelName());
        String vtype = "";
        String vTime = "time";
        if(aiModel!=null) {
            switch (aiModel.getSpareOne()) {
                case "1": //v3
                {
                    log.info("啥也没有进入");
                }
                case "2"://v5
                case "3"://v8
                {
                    vtype = "V5";
                    vTime = "timeV5";
                }
            }
        }
        redisUtil.set(tabAiModelBund.getSendUrl()+vtype+sysUser.getId(),false);
        redisUtil.expire(tabAiModelBund.getSendUrl()+vtype+sysUser.getId(),3000);
        //
        redisUtil.set(tabAiModelBund.getSendUrl()+vTime+sysUser.getId(),0);
        redisUtil.expire(tabAiModelBund.getSendUrl()+vTime+sysUser.getId(),3000);
        return 0;
    }

    @Override
    public int saveIdentifyVideo(TabAiModelBund tabAiModelBund, String path) {

        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        try {
            String savePath=modelYolo3.SendVideoYoloV3(tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path);
            if(savePath.equals("error")){
                return 1;
            }
            log.info("识别存储文件地址{}",savePath);
            Long b=System.currentTimeMillis();
            TabAiHistory tabAiHistory=new TabAiHistory();
            tabAiHistory.setBundName(tabAiModel1.getAiName());
            tabAiHistory.setModelName(tabAiModel1.getAiName());
            tabAiHistory.setModelId(tabAiModelBund.getModelName());
            tabAiHistory.setSendUrl("temp/"+savePath);
            tabAiHistory.setSendTime(b-a+"");
            tabAiHistoryMapper.insert(tabAiHistory);
            return 0;
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
    }

    @Override
    public int saveIdentifyLocalVideo(TabAiModelBund tabAiModelBund, String path,String userId) {
        Long a=System.currentTimeMillis();
        LambdaQueryWrapper< TabAiModel> query = new LambdaQueryWrapper<>();
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        redisUtil.set(tabAiModelBund.getSendUrl()+""+userId,true);
        redisUtil.expire(tabAiModelBund.getSendUrl()+""+userId,( 24*60*60*365*1000));
        try {
            String savePath=modelYolo3.SendVideoLocalhostYoloV3(userId,tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil);
            if(savePath.equals("error")){
                return 1;
            }
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
        return 0;
    }

    @Override
    public int saveIdentifyLocalVideoThread(TabAiModelBund tabAiModelBund, String path, String userId) {

        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        //处置结束符
        redisUtil.set(tabAiModelBund.getSendUrl()+""+userId,true);
        redisUtil.expire(tabAiModelBund.getSendUrl()+""+userId,( 24*60*60*365*1000));
        //处置时间戳
        redisUtil.set(tabAiModelBund.getSendUrl()+"time"+userId,0);
        redisUtil.expire(tabAiModelBund.getSendUrl()+"time"+userId,( 24*60*60*365*1000));
        //处置基础库
        Object object = redisUtil.get("AIModelBase");
        if(object==null){
            LambdaQueryWrapper< TabAiBase> querybase = new LambdaQueryWrapper<>();
            List<TabAiBase> base=tabAiBaseMapper.selectList(querybase);
            redisUtil.set("AIModelBase", JSONObject.toJSONString(base));
            redisUtil.expire("AIModelBase",(24*60*60*365*1000));
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }else{
            String jsonObject= object.toString();
            JSONArray array=JSONArray.parseArray(jsonObject);
            List<TabAiBase> base= array.toJavaList(TabAiBase.class);
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }
        try {
            TabAudioDevice tabAudioDevice=new TabAudioDevice();
            if(tabAiModelBund.getIsAudio().equals("Y")&& StringUtils.isNotEmpty(tabAiModelBund.getAudioId())){
                tabAudioDevice=tabAudioDeviceMapper.selectById(tabAiModelBund.getAudioId());
            }
            String savePath=modelYolo3.SendVideoLocalhostYoloV5Thread(  tabAudioDevice,tabAiModelBund,userId,tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil,redisTemplate);
            if(savePath.equals("error")){
                return 1;
            }
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
        return 0;
    }

    @Override
    public int saveIdentifyLocalVideoThreadV5(TabAiModelBund tabAiModelBund, String path, String userId) {
        log.info("V5识别内容开始！！！！！！！！！！！！！！！！！！！！！！！！！！！！！");
        TabAiModel tabAiModel1=modelMapper.selectById(tabAiModelBund.getModelName());
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        //处置结束符
        redisUtil.set(tabAiModelBund.getSendUrl()+"V5"+userId,true);
        redisUtil.expire(tabAiModelBund.getSendUrl()+"V5"+userId,( 24*60*60*365*1000));
        //处置时间戳
        redisUtil.set(tabAiModelBund.getSendUrl()+"timeV5"+userId,0);
        redisUtil.expire(tabAiModelBund.getSendUrl()+"timeV5"+userId,( 24*60*60*365*1000));
        //处置基础库
        Object object = redisUtil.get("AIModelBaseV5");
        if(object==null){
            LambdaQueryWrapper< TabAiBase> querybase = new LambdaQueryWrapper<>();
            List<TabAiBase> base=tabAiBaseMapper.selectList(querybase);
            redisUtil.set("AIModelBaseV5", JSONObject.toJSONString(base));
            redisUtil.expire("AIModelBaseV5",(24*60*60*365*1000));
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }else{
            String jsonObject= object.toString();
            JSONArray array=JSONArray.parseArray(jsonObject);
            List<TabAiBase> base= array.toJavaList(TabAiBase.class);
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }
        try {
            TabAudioDevice tabAudioDevice=new TabAudioDevice();
            if(StringUtils.isNotEmpty(tabAiModelBund.getAudioId())&& tabAiModelBund.getIsAudio().equals("Y")){
                tabAudioDevice=tabAudioDeviceMapper.selectById(tabAiModelBund.getAudioId());

            }
            String savePath=modelYolo3.SendVideoLocalhostYoloV5Thread(tabAudioDevice,tabAiModelBund,userId,tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil,redisTemplate);
            if(savePath.equals("error")){
                return 1;
            }
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
        return 0;
    }
    @Autowired
    TabVideoUtilServiceImpl tabVideoUtilServiceImpl;
    @Override
    public int saveIdentifyLocalVideoThreadOnnx(TabAiModelBund tabAiModelBund, String path, String userId) {


        log.info("V5识别内容开始！！！！！！！！！！！！！！！！！！！！！！！！！！！！！");
        TabAiModel tabAiModel1=getTabAiModelInfo(modelMapper.selectById(tabAiModelBund.getModelName()),"xxx",path);
        AIModelYolo3  modelYolo3=new AIModelYolo3();
        //处置结束符
        redisUtil.set(tabAiModelBund.getSendUrl()+"V5"+userId,true);
        redisUtil.expire(tabAiModelBund.getSendUrl()+"V5"+userId,( 24*60*60*365*1000));
        //处置时间戳
        redisUtil.set(tabAiModelBund.getSendUrl()+"timeV5"+userId,0);
        redisUtil.expire(tabAiModelBund.getSendUrl()+"timeV5"+userId,( 24*60*60*365*1000));
        //处置基础库
        Object object = redisUtil.get("AIModelBaseV5");
        if(object==null){
            LambdaQueryWrapper< TabAiBase> querybase = new LambdaQueryWrapper<>();
            List<TabAiBase> base=tabAiBaseMapper.selectList(querybase);
            redisUtil.set("AIModelBaseV5", JSONObject.toJSONString(base));
            redisUtil.expire("AIModelBaseV5",(24*60*60*365*1000));
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }else{
            String jsonObject= object.toString();
            JSONArray array=JSONArray.parseArray(jsonObject);
            List<TabAiBase> base= array.toJavaList(TabAiBase.class);
            VideoSendReadCfg.map=VideoSendReadCfg.getMap(base);
        }
        try {
            TabAudioDevice tabAudioDevice=new TabAudioDevice();
            if(StringUtils.isNotEmpty(tabAiModelBund.getAudioId())&& tabAiModelBund.getIsAudio().equals("Y")){
                tabAudioDevice=tabAudioDeviceMapper.selectById(tabAiModelBund.getAudioId());

            }

            NetPush beforePush=new NetPush();
            beforePush.setId(tabAiModelBund.getId());
            OnnxModelWrapper endNet=getOnnxModel(  tabAiModel1);
            beforePush.setSession(endNet.getSession());
            beforePush.setEnv(endNet.getEnv());
            beforePush.setClaseeNames(Files.readAllLines(Paths.get(tabAiModel1.getAiNameName())));
            TabVideoUtil tabVideoUtil = new TabVideoUtil();

            if (tabAiModelBund.getIsBy() != null && tabAiModelBund.getIsBy() == 0) {
                tabVideoUtil = tabVideoUtilServiceImpl.getOne(
                        new QueryWrapper<TabVideoUtil>().eq("video_id", tabAiModelBund.getId())
                );
                log.info("开启区域入侵识别{}-{}", tabAiModelBund.getSpaceTwo(), tabVideoUtil);
            } else {
                log.info("未开启区域入侵识别{}", tabAiModelBund.getSpaceTwo());
            }
            beforePush.setIsBy(tabAiModelBund.getIsBy()!=null?tabAiModelBund.getIsBy():1);
            beforePush.setTabVideoUtil(tabVideoUtil);
            RedisCacheHolder.put(tabAiModelBund.getId()  + "videoRead",true);
            String savePath="";
            if(tabAiModel1.getModelDify()!=null&&tabAiModel1.getModelDify()==2){  //姿态识别
                log.info("姿态识别");
                savePath=modelYolo3.SendVideoLocalhostYoloV11ThreadPose(beforePush,tabAudioDevice,tabAiModelBund,userId,tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil,redisTemplate);

            }else{
                log.info("图像识别");
                savePath=modelYolo3.SendVideoLocalhostYoloV11Thread(beforePush,tabAudioDevice,tabAiModelBund,userId,tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil,redisTemplate);

            }
            if(savePath.equals("error")){
                return 1;
            }
        }catch (Exception ex){
            log.warn("出错{}",ex);
            ex.printStackTrace();
            return 1;
        }
        return 0;

    }

    private static final Map<String, OnnxModelWrapper> GLOBAL_NET_CACHE_ONNX = new ConcurrentHashMap<>();

    public TabAiModel getTabAiModelInfo(TabAiModel tabAiModel,String name,String path){
        if(tabAiModel.getSpareOne().equals("1")){ //v3
            tabAiModel.setAiConfig(path+ File.separator +tabAiModel.getAiConfig());
        }
        tabAiModel.setAiWeights(path+ File.separator +tabAiModel.getAiWeights());
        tabAiModel.setAiNameName(path+ File.separator +tabAiModel.getAiNameName());
        if(StringUtils.isNotEmpty(name)){
            tabAiModel.setAiName(name);
        }

        return  tabAiModel;
    }
    public OnnxModelWrapper getOnnxModel( TabAiModel tabAiModel) {
        String cacheKey =  tabAiModel.getId();
        OnnxModelWrapper wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);

        if (wrapper == null) {
            synchronized (this) { // 添加同步锁防止并发创建
                wrapper = GLOBAL_NET_CACHE_ONNX.get(cacheKey);
                if (wrapper == null) {
                    try {
                        OrtEnvironment env = OrtEnvironment.getEnvironment();
                        OrtSession.SessionOptions options = new OrtSession.SessionOptions();

                        if (tabAiModel.getModelJmType()!=null&&tabAiModel.getModelJmType()==1) { // GPU
                            options.addCUDA();
                            log.info("[ONNX推理规则：GPU]");
                        } else { // CPU
                            options.setIntraOpNumThreads(1); // 每个session单算子只用1核
                            options.setInterOpNumThreads(1);
                            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
                            options.addCPU(true);

                            log.info("[ONNX推理规则：CPU]");
                        }

                        System.out.println(OrtEnvironment.getEnvironment().getVersion());

                        OrtSession session = env.createSession(tabAiModel.getAiWeights(), options);
                        wrapper = new OnnxModelWrapper(env, session);
                        GLOBAL_NET_CACHE_ONNX.put(cacheKey, wrapper);
                        log.info("【ONNX模型加载成功并缓存】key: {}", cacheKey);
                    } catch (Exception ex) {
                        log.error("【ONNX模型加载失败】", ex);
                        throw new RuntimeException("ONNX模型加载失败", ex);
                    }
                }
            }
        } else {
            log.info("【已存在ONNX模型，直接返回】key: {}", cacheKey);
        }

        return wrapper;
    }

    @Override
    public Result<String>  startAi(TabAiModelBund tabAiModelBund, String path, String userId) {


        TabAiModel aiModel=modelMapper.selectById(tabAiModelBund.getModelName());
        if(aiModel!=null){


            switch (aiModel.getSpareOne()){
                case "1": //v3
                {
                    log.info("【进入V3开始识别内容】{}",tabAiModelBund.getSpaceTwo());
                    if(tabAiModelBund.getSpaceOne().equals("0")){ //当前为图片
                        int a=this.saveIdentify(tabAiModelBund,path);
                        if(a==0){
                            return Result.OK("识别图片成功！");
                        }
                    }else{
                        // 输出视频
                        // tabAiHistoryService.saveIdentifyVideo(tabAiModelBund,uploadpath);
                        // 输出坐标 延迟3-5s
                        //tabAiHistoryService.saveIdentifyLocalVideo(tabAiModelBund,uploadpath,sysUser.getId());
                        //多线程输出坐标
                        this.saveIdentifyLocalVideoThread(tabAiModelBund,path,userId);
                        return Result.OK("视频识别开始");
                    }
                    break;
                }
                case "2":
                {
                    log.info("【进入V5开始识别内容】{}",tabAiModelBund.getSpaceTwo());
                    if(tabAiModelBund.getSpaceOne().equals("0")){ //当前为图片
                        if(tabAiModelBund.getSpaceTwo().indexOf("车牌")>-1||tabAiModelBund.getSpaceTwo().indexOf("区域")>-1){
                            log.info("【进入车牌识别内容】{}",tabAiModelBund.getSpaceTwo());
                            int a=this.saveCarIdentifyV5(tabAiModelBund,path);
                            if(a==0){
                                return Result.OK("识别图片成功！");
                            }
                        }else{
                            int a=this.saveIdentifyYolov(tabAiModelBund,path,aiModel.getSpareOne());
                            if(a==0){
                                return Result.OK("识别图片成功！");
                            }
                        }

                    }else{
                        if(aiModel.getModelDifyType()!=null&&aiModel.getModelDifyType()==20){
                            this.saveIdentifyLocalVideoThreadOnnx(tabAiModelBund,path,userId);
                        }else{
                            this.saveIdentifyLocalVideoThreadV5(tabAiModelBund,path,userId);
                        }
                        // 输出视频
                        // tabAiHistoryService.saveIdentifyVideo(tabAiModelBund,uploadpath);
                        // 输出坐标 延迟3-5s
                        //tabAiHistoryService.saveIdentifyLocalVideo(tabAiModelBund,uploadpath,sysUser.getId());
                        //多线程输出坐标

                        return Result.OK("视频识别开始");
                    }
                    break;
                }//v5
                case "11":
                case "26":
                {
                    if(tabAiModelBund.getSpaceOne().equals("0")) { //当前为图片
                        int a = this.saveIdentifyYolov(tabAiModelBund, path, aiModel.getSpareOne());
                        if (a == 0) {
                            return Result.OK("识别图片成功！");
                        }
                    }else{
                        if(aiModel.getModelDifyType()!=null&&aiModel.getModelDifyType()==20){
                            this.saveIdentifyLocalVideoThreadOnnx(tabAiModelBund,path,userId);
                        }else{
                            this.saveIdentifyLocalVideoThreadV5(tabAiModelBund,path,userId);
                        }

                        return Result.OK("视频识别开始");
                    }
                    break;
                }
                    case "3":
                {  log.info("【进入V8开始识别内容】{}",tabAiModelBund.getSpaceTwo());
                    log.info("【进入V8开始识别内容】{}",tabAiModelBund.getSpaceTwo());
                    if(tabAiModelBund.getSpaceOne().equals("0")){ //当前为图片
                        int a=this.saveIdentifyYolov8(tabAiModelBund,path);
                        if(a==0){
                            return Result.OK("识别图片成功！");
                        }
                    }else{
                        // 输出视频
                        // tabAiHistoryService.saveIdentifyVideo(tabAiModelBund,uploadpath);
                        // 输出坐标 延迟3-5s
                        //tabAiHistoryService.saveIdentifyLocalVideo(tabAiModelBund,uploadpath,sysUser.getId());
                        //多线程输出坐标
                        if(aiModel.getModelDifyType()!=null&&aiModel.getModelDifyType()==20){
                            log.info("onnx 视频识别内容");
                        }else{
                            this.saveIdentifyLocalVideoThread(tabAiModelBund,path,userId);
                        }

                        return Result.OK("视频识别开始");
                    }
                    break;
                }//v8
                case "4": {    break;}//json
                case "5": {    break;}//other
                case "6": { //cv
                    log.info("【进入cv开始识别内容】{}",tabAiModelBund.getSpaceTwo());
                    if(tabAiModelBund.getSpaceTwo().equals("车牌识别")){
                        int a=this.saveCarIdentify(tabAiModelBund,path);
                        if(a==0){
                            return Result.OK("识别图片成功！");
                        }
                    }
                    break;
                }//json
                case "7":{
                    log.info("【进入OCR文字识别内容】{}",tabAiModelBund.getSpaceTwo());

                        int a=this.saveStr(tabAiModelBund,path);
                        if(a==0){
                            return Result.OK("识别图片成功！");
                        }

                    break;
                }
                case "9":{
                    log.info("【进入音频内容】{}",tabAiModelBund.getSpaceTwo());

                    int a=this.saveAudioStr(tabAiModelBund,path);
                    if(a==0){
                        return Result.OK("识别图片成功！");
                    }

                    break;
                }
                case "20":{
                    log.info("【ONNX】{}",tabAiModelBund.getSpaceTwo());



                    break;
                }
            }



        }
        return Result.error("识别失败未发现识别内容");
    }

    @Override
    public Result<?> startAiReturn(TabAiModelBund tabAiModelBund, String path) {
        String modelId=tabAiModelBund.getModelId();
        String name=tabAiModelBund.getModelName();
        String pic=tabAiModelBund.getSendUrl(); //base64;


        return null;
    }


    @Override
    public Result<String>  startAiPush(TabAiSubscription tabAiSubscription) {

        List<String> typesList= Arrays.asList(tabAiSubscription.getEventTypes().split(","));
        QueryWrapper<TabAiModel> queryWrapper=new QueryWrapper<>();
        queryWrapper.in("id",typesList);
        List<TabAiModel> aiModel=modelMapper.selectList(queryWrapper);
        BlockingQueue<Mat> queue = new LinkedBlockingQueue<>();
        if(aiModel!=null){
            new Thread(new ReadVideoImg(tabAiSubscription,redisTemplate,queue)).start();
            new Thread(new AiImgResult(aiModel,tabAiSubscription,redisTemplate,queue)).start();
        }


        return Result.error("识别失败未发现识别内容");
    }

    @Override
    public void sendUrl() {
        log.info("进入：！！！！！！！！");

        //   SendPicYoloV3("yolov3.weights","yolov3.cfg","coco.names","car.jpg","test","F:\\JAVAAI\\yolo3\\yuanshi");
        //     SendPicYoloV5("NBplate.onnx","coco.names","writecat.jpg","","F:\\JAVAAI\\yolov5");
        //    SendPicYoloV5Car("NBplate.onnx","coco.names","bluecar.jpg","","F:\\JAVAAI\\yolov5");
        String rtspUrl="rtsp://admin:ch255899@192.168.0.200/Streaming/Channels/102";
//        String rtspUrl = "rtsp://[用户名]:[密码]@[IP地址]:[端口]/[码流类型]";
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(rtspUrl);
        OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();
        VideoWriter videoWriter = new VideoWriter();
        try {
            grabber.setOption("rtsp_transport", "tcp"); // 使用TCP而不是UDP
            grabber.start();
            System.out.println("连接到RTSP流成功");
            Java2DFrameConverter converter = new Java2DFrameConverter();
            Frame frame;
            int a=0;

            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(byteArrayOutputStream, grabber.getImageWidth(), grabber.getImageHeight());
//			 recorder = new FFmpegFrameRecorder(new org.bytedeco.javacv.FrameRecorder.FrameRecorderAVIO(
//					 (org.bytedeco.javacv.FrameRecorder.FrameRecorderAVIOCallback) bufferCallback::call, null
//			 ), 1280, 720);
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("flv");
            System.out.println("帧率"+grabber.getFrameRate());
            recorder.setFrameRate(grabber.getFrameRate());
            recorder.setGopSize(30); // 每30帧一个关键帧
            recorder.setVideoQuality(0); // 最好的质量
            recorder.setVideoOption("preset", "ultrafast");
            recorder.setVideoOption("tune", "zerolatency");
            recorder.start();


            byte[] flvHeader = createFLVHeader();
            SendWebSocket(flvHeader);
            while ((frame = grabber.grab()) != null) {

                // 将Frame转换为JavaCV的Mat
                int width=frame.imageWidth;
                int height=frame.imageHeight;
                System.out.println(a+"成功获取帧宽度"+width+"高度:"+height);
                Mat opencvMat=bufferedImageToMat(   converter.getBufferedImage(frame));
                if(a<=500) {
                    // 录制帧 b释放才会保存
                    Frame processedFrame = converterToFrame(opencvMat);
                    //重置byteArrayOutputStream
                    // byteArrayOutputStream.reset();
                    //编码写入FLV
                    recorder.record(processedFrame);
                    // 强制刷新recorder，确保数据写入ByteArrayOutputStream
                    //	 recorder.flush();
                    // 获取编码后的数据
                    byte[] encodedData = byteArrayOutputStream.toByteArray();
                    if (encodedData.length > 0) {
                        SendWebSocket(encodedData);
                        byteArrayOutputStream.reset(); // 重置输出流
                    }
               //     Imgcodecs.imwrite("F:\\JAVAAI\\model\\test3\\test"+a+".jpg", opencvMat);
                    // saveVideo(opencvMat, frame, videoWriter, "F:\\JAVAAI\\model\\test3\\test1.mp4");
                }else{
                    break;
                }
                a++;


                // 显示帧（如果您想要显示视频）
                //   canvasFrame.showImage(frame);

            }

            grabber.stop();
            grabber.release();

            recorder.stop();
            recorder.release();
            //     canvasFrame.dispose();
        } catch (Exception e) {
            System.err.println("发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private static ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
    boolean isStreaming=false;
    FFmpegFrameGrabber grabber;
    FFmpegFrameRecorder  recorder;
    @Override
    public void sendUrlFLV() throws FFmpegFrameGrabber.Exception, FFmpegFrameRecorder.Exception {
        try{
            String rtspUrl="rtsp://admin:ch255899@192.168.0.200/Streaming/Channels/102";
//        String rtspUrl = "rtsp://[用户名]:[密码]@[IP地址]:[端口]/[码流类型]";
            grabber = new FFmpegFrameGrabber(rtspUrl);
            grabber.start();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            recorder = new FFmpegFrameRecorder(outputStream, grabber.getImageWidth(), grabber.getImageHeight());
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_FLV1);
            recorder.setFormat("flv");
            recorder.setFrameRate(30);
            recorder.setGopSize(30 * 2);
            recorder.setVideoOption("preset", "ultrafast");
            recorder.setVideoOption("tune", "zerolatency");
            recorder.start();
            isStreaming = true;
            byte[] flvHeader = {0x46, 0x4C, 0x56, 0x01, 0x05, 0x00, 0x00, 0x00, 0x09};
            SendWebSocket(flvHeader);
            Frame frame;
            int a=0;
            while (isStreaming && (frame = grabber.grab()) != null) {
                //    processFrame(frame);

                if(a<=500){
                    // 将Frame转换为JavaCV的Mat

                    int width=frame.imageWidth;
                    int height=frame.imageHeight;
                    log.info(a+"成功获取帧宽度"+width+"高度:"+height);
//                    buffer.clear();
                    recorder.record(frame);
                    byte[] flvTag = outputStream.toByteArray();
                    if (flvTag.length > 0) {
                        SendWebSocket (flvTag);
                        outputStream.reset();
                    }
                //    buffer.flip();
                    // 获取编码后的FLV数据
//                    if (buffer.remaining() > 0) {
//                        webSocket.broadcastFrame(buffer);
//                    }
                }else {
                    break;
                }
                a++;

            }
        }catch (Exception ex){

        }finally {
            stopStreaming();
        }


    }
    private void stopStreaming() {
        isStreaming = false;
        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
            }
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (FFmpegFrameGrabber.Exception | FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        }
    }
    private static class CustomOutputStream extends java.io.OutputStream {
        private final ByteBuffer buffer;

        public CustomOutputStream(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void write(int b) {
            buffer.put((byte) b);
        }

        @Override
        public void write(byte[] bytes, int off, int len) {
            buffer.put(bytes, off, len);
        }
    }
    public void SendWebSocket(byte[] text ){
        webSocket.pushMessageByte(text);
    }

    private byte[] createFLVHeader() {
        byte[] header = new byte[13];
        header[0] = 'F';
        header[1] = 'L';
        header[2] = 'V';
        header[3] = 1;
        header[4] = 5; // 有视频和音频
        header[5] = 0;
        header[6] = 0;
        header[7] = 0;
        header[8] = 9;
        return header;
    }
}
