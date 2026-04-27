package org.jeecg.modules.demo.audio.util;

import com.alibaba.fastjson.JSONObject;
import com.k2fsa.sherpa.onnx.*;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

import static org.jeecg.common.util.RestUtil.*;

/**
 * @author wggg
 * @date 2025/3/7 14:12
 */

@Slf4j
public class audioSend {


    /**
     * 获取token
     * @param tabAudioDevice
     * @return
     */
    public  static String getToken(TabAudioDevice tabAudioDevice){
        HttpHeaders headers = new HttpHeaders();

        headers.add("Accept", MediaType.TEXT_PLAIN_VALUE);
        headers.add("ManageCode", "12345668");
        headers.setBasicAuth("admin","123456");
        ResponseEntity<String> json= request(tabAudioDevice.getDeivceUrl()+"/NAS/API/Login",  HttpMethod.GET, headers, null, null, String.class);
        log.info(json.getBody());
        return json.getBody();
    }


    /***
     * 上传音频文件
     * @param token
     * @param tabAudioDevice
     * @return
     */
    public  static String  postAudioFileOpen(String token,TabAudioDevice tabAudioDevice){

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.parseMediaType(MediaType.APPLICATION_JSON_VALUE));
        headers.add("NASAuthToken", token);
        headers.add("UploadCommand", "OPEN");
        JSONObject jsonObject=new JSONObject();
        jsonObject.put("DataType","UploadFileOpen");
        jsonObject.put("FileName","Test"+ File.separator+System.currentTimeMillis()+".mp3");
        ResponseEntity<JSONObject> json= request(tabAudioDevice.getDeivceUrl()+"/NAS/API/MediaFileSet/System/Upload",  HttpMethod.GET, headers, null, jsonObject, JSONObject.class);
        JSONObject rsultJosn=json.getBody();
        log.info("Open:{}",rsultJosn.toJSONString());
        return rsultJosn.getString("UnionCode");
    }



    public  static JSONObject  postAudioFileData(String file,String UploadUnionCode,String token,TabAudioDevice tabAudioDevice) throws IOException {

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.parseMediaType("audio/mpeg"));
        headers.add("NASAuthToken", token);
        headers.add("UploadUnionCode", UploadUnionCode);
        headers.add("UploadCommand", "Data");

        byte[] mp3Bytes = Files.readAllBytes(Paths.get(file));

        ResponseEntity<JSONObject> json= requestByte(tabAudioDevice.getDeivceUrl()+"/NAS/API/MediaFileSet/System/Upload",  HttpMethod.GET, headers, null,mp3Bytes , JSONObject.class);
        JSONObject rsultJosn=json.getBody();
        log.info("DATA:{}",rsultJosn.toJSONString());
        return rsultJosn;
    }


    public  static JSONObject postAudioText(String token,TabAudioDevice tabAudioDevice,String text){


        StringBuilder unicode = new StringBuilder();

        for (char c : text.toCharArray()) {
            // 将字符转换为 Unicode 编码的十六进制表示
            unicode.append("\\u").append(Integer.toHexString(c | 0x10000).substring(1));
        }

        log.info("汉字的 Unicode 编码为: " + unicode.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(MediaType.APPLICATION_JSON_VALUE));
        headers.add("Accept", "*/*");
        headers.add("NASAuthToken", token);
        JSONObject jsonObject=new JSONObject();
        jsonObject.put("DataType","TempTaskRequest"); //数据类型，必须为"TempTaskRequest
        long time = System.currentTimeMillis() % 1_000_000; // 6位
        int random = ThreadLocalRandom.current().nextInt(1000, 9999); //4位
        jsonObject.put("TaskName","WGAI_Send"+time+random);
        jsonObject.put("TaskType",7);
        jsonObject.put("Priority",0);
        jsonObject.put("AutoPause",1);
        jsonObject.put("StartMode",2);
        jsonObject.put("EndMode",0);
        jsonObject.put("PlayMode",2);
        jsonObject.put("LoopTimes",0);
        jsonObject.put("TaskVolume",14);
        jsonObject.put("VoiceText",unicode);
        jsonObject.put("PlayerList",new String[]{"0001E28"});
        log.info(jsonObject.toJSONString());
        ResponseEntity<JSONObject> json= requestUniCode(tabAudioDevice.getDeivceUrl()+"/NAS/API/TaskRequest/TempTask",  HttpMethod.POST, headers, null, jsonObject, JSONObject.class);
        JSONObject rsultJosn=json.getBody();
        log.info("postAudioText:{}",rsultJosn.toJSONString());
        return rsultJosn;

    }

    public  static JSONObject  postAudioFileClose(String UploadUnionCode,String token,TabAudioDevice tabAudioDevice){

        HttpHeaders headers = new HttpHeaders();


        headers.add("NASAuthToken", token);
        headers.add("UploadUnionCode", UploadUnionCode);
        headers.add("UploadCommand", "Close");

        ResponseEntity<JSONObject> json= request(tabAudioDevice.getDeivceUrl()+"/NAS/API/MediaFileSet/System/Upload",  HttpMethod.POST, headers, null, null, JSONObject.class);
        JSONObject rsultJosn=json.getBody();
        log.info("Close:FileID{}",rsultJosn.toJSONString());
        return rsultJosn;
    }

    public static void main(String[] args) {

        TabAudioDevice tabAudioDevice=new TabAudioDevice();
        tabAudioDevice.setDeivceUrl("http://192.168.0.160:8307");
        String token=getToken(tabAudioDevice);

        postAudioText(token,tabAudioDevice,"请勿抽烟 请勿在办公室抽烟");

       // wavToMp3(  sendWav(), "F:\\JAVAAI\\audio", "test.mp3");
    }

    public  static Result<String> wavToMp3(String url, String path, String name){

        String ffmpegCommand = "ffmpeg  -i "+url+"  -vn -ac 2 -ar 44100  -b:a 192k "+path+File.separator+name;

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
    public static String   sendWav(){

//        TabAudioDevice tabAudioDevice=new TabAudioDevice();
//        tabAudioDevice.setDeivceUrl("http://192.168.0.160:8307");
//        getToken(tabAudioDevice);


            // please visit
            // https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
            // to download model files
            String model = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C.onnx";
            String tokens = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\tokens.txt";
            String lexicon = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\lexicon.txt";
            String dictDir = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\dict";
            String ruleFsts = "F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\phone.fst,F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\date.fst,F:\\JAVAAI\\audio\\vits-zh-hf-fanchen-C\\vits-zh-hf-fanchen-C\\number.fst";
            String text = "办公室禁止抽烟,请勿在办公室抽烟 进入办公室请佩戴安全帽 ";

            OfflineTtsVitsModelConfig vitsModelConfig =
                    OfflineTtsVitsModelConfig.builder()
                            .setModel(model)
                            .setTokens(tokens)
                            .setLexicon(lexicon)
                            .setDictDir(dictDir)
                            .build();

            OfflineTtsModelConfig modelConfig =
                    OfflineTtsModelConfig.builder()
                            .setVits(vitsModelConfig)
                            .setNumThreads(10)
                            .setDebug(true)
                            .build();

            OfflineTtsConfig config =
                    OfflineTtsConfig.builder().setModel(modelConfig).setRuleFsts(ruleFsts).build();

            OfflineTts tts = new OfflineTts(config);

            int sid = 100;
            float speed = 1.0f;
            long start = System.currentTimeMillis();
            GeneratedAudio audio = tts.generate(text, sid, speed);
            long stop = System.currentTimeMillis();

            float timeElapsedSeconds = (stop - start) / 1000.0f;

            float audioDuration = audio.getSamples().length / (float) audio.getSampleRate();
            float real_time_factor = timeElapsedSeconds / audioDuration;

            String waveFilename = "F:\\JAVAAI\\audio\\tts-vits-zh.wav";
            audio.save(waveFilename);
            System.out.printf("-- elapsed : %.3f seconds\n", timeElapsedSeconds);
            System.out.printf("-- audio duration: %.3f seconds\n", timeElapsedSeconds);
            System.out.printf("-- real-time factor (RTF): %.3f\n", real_time_factor);
            System.out.printf("-- text: %s\n", text);
            System.out.printf("-- Saved to %s\n", waveFilename);

            tts.release();
            return  waveFilename;
        }

}
