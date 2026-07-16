package org.jeecg;

import ai.onnxruntime.OrtEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bytedeco.ffmpeg.global.avutil;
import org.jeecg.common.util.oConvertUtils;
import org.opencv.core.Core;
import org.opencv.dnn.Dnn;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
* 单体启动类
* 报错提醒: 未集成mongo报错，可以打开启动类上面的注释 exclude={MongoAutoConfiguration.class}
*/
@Slf4j
@ComponentScan(value = {"org.jeecg","cn.cuiot","org.wgai"})
@SpringBootApplication
//@EnableAutoConfiguration(exclude={MongoAutoConfiguration.class})
public class JeecgSystemApplication extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(JeecgSystemApplication.class);
    }



    public static void main(String[] args) throws UnknownHostException {
       // System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
// 在方法开始时添加，设置全局FFmpeg日志级别
//        System.setProperty("org.bytedeco.javacpp.logger.debug", "false");
//        System.setProperty("org.bytedeco.ffmpeg.logger", "quiet");
       avutil.av_log_set_level(avutil.AV_LOG_QUIET);
       // System.load("C:\\JAVAAI\\opencv\\build\\java\\x64\\opencv_java481.dll");
        ConfigurableApplicationContext application = SpringApplication.run(JeecgSystemApplication.class, args);
        Environment env = application.getEnvironment();
        String ip = InetAddress.getLocalHost().getHostAddress();
        String port = env.getProperty("server.port");
        String path = oConvertUtils.getString(env.getProperty("server.servlet.context-path"));
        String opencvpath = env.getProperty("opencv");
        String audiopath = env.getProperty("audio.dll");
        Integer numThread = Integer.valueOf(env.getProperty("numThread"));

        log.info("\n----------------------------------------------------------\n\t" +
                "Application WGAI is running! Access URLs:\n\t" +
                "Local: \t\thttp://localhost:" + port + path + "/\n\t" +
                "External: \thttp://" + ip + ":" + port + path + "/\n\t" +
                "Swagger文档: \thttp://" + ip + ":" + port + path + "/doc.html\n\t" +
                "opencvpath:"+opencvpath+"\n\t  "+
                "audio:"+audiopath+"\n\t  "+
                "----------------------------------------------------------");
        if(StringUtils.isNotEmpty(opencvpath)){
            String [] opencvList=opencvpath.split(";");
            for (int i = 0; i <opencvList.length ; i++) {
                File opencv=new File(opencvList[i]);
                if(opencv.exists()){
                    System.load(opencvList[i]);
                    // 设置OpenCV使用的CPU线程数
                    org.opencv.core.Core.setNumThreads(numThread); // 根据CPU核数调整
                    // 启用OpenCV并行处理
                    org.opencv.core.Core.setUseOptimized(true);

                //    log.info(Core.getBuildInformation());
                    log.info("[opencv加载成功]:{}",opencvList[i]);
                    log.info("[OpenCV优化配置] 线程数: {}, 优化: enabled",numThread);
                    break;
                }else{
                    log.error("opencv文件不存在！请检查地址是否正确 或 是否编译opencv");
                }
            }
        }

//        if(StringUtils.isNotEmpty(audiopath)){
//            String [] audiopathList=audiopath.split(";");
//            for (int i = 0; i <audiopathList.length ; i++) {
//                File opencv=new File(audiopathList[i]);
//                if(opencv.exists()){
//                    System.load(audiopathList[i]);
//                    log.info("[audio加载成功]:{}",audiopathList[i]);
//                    break;
//                }else{
//                    log.error("[audio文件不存在！请检查地址是否正确 或 是否编译audio]");
//                }
//            }
//        }
//        System.setProperty("onnxruntime.native." + System.getProperty("os.name"),
//                "C:\\Users\\Administrator\\AppData\\Local\\Temp\\onnxruntime-java152680534200004934\\onnxruntime.dll");
        log.info("[ONNX支持的配置：]: " + OrtEnvironment.getAvailableProviders());

    }

}