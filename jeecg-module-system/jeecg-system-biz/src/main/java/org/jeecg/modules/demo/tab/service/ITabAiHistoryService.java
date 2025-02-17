package org.jeecg.modules.demo.tab.service;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.vo.LoginUser;
import org.jeecg.modules.demo.audio.entity.TabAuditSetting;
import org.jeecg.modules.demo.tab.entity.TabAiHistory;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.tab.entity.TabAiSubscription;
import org.jeecg.modules.tab.entity.TabAiModel;

/**
 * @Description: AI识别结果历史
 * @Author: WGAI
 * @Date:   2024-03-13
 * @Version: V1.0
 */
public interface ITabAiHistoryService extends IService<TabAiHistory> {



    /**
     * 语音识别内容文字
     * @param path 文件地址
     * @return
     */
    public Result<?> aiAudioSetting(TabAuditSetting tabAuditSetting,String audioPath, String uplopadPath);

    public Result<?> aiAudio(String path,String uplopadPath);

    /**
     * 识别内容文字
     * @param tabAiModelBund
     * @return
     */
    public int saveStr(TabAiModelBund tabAiModelBund,String path);

    /**
     * 识别语音文字
     * @param tabAiModelBund
     * @return
     */
    public int saveAudioStr(TabAiModelBund tabAiModelBund,String path);
    /**
     * 添加车牌识别
     * @param
     */
    public int saveCarIdentify(TabAiModelBund tabAiModelBund,String path);

    /**
     * 添加车牌识别
     * @param
     */
    public int saveCarIdentifyV5(TabAiModelBund tabAiModelBund,String path);
    /**
     * 添加自动识别历史记录
     * @param
     */
    public int saveIdentify(TabAiModelBund tabAiModelBund,String path);

    /***
     * v5识别内容
     * @param tabAiModelBund
     * @param path
     * @return
     */
    public int saveIdentifyYolov5(TabAiModelBund tabAiModelBund,String path);

    /***
     * v8识别内容
     * @param tabAiModelBund
     * @param path
     * @return
     */
    public int saveIdentifyYolov8(TabAiModelBund tabAiModelBund,String path);
    /**
     * 关闭自动识别历史记录
     * @param
     */
    public int closedentify(TabAiModelBund tabAiModelBund,LoginUser sysUser);

    /***
     * 添加自动识别视频历史记录
     * @param tabAiModelBund
     * @param path
     * @return
     */
    public int saveIdentifyVideo(TabAiModelBund tabAiModelBund,String path);

    /***
     * 添加识别历史
     * @param tabAiModelBund
     * @param path
     * @return
     */
    public int saveIdentifyLocalVideo(TabAiModelBund tabAiModelBund,String path,String userId);

    /***
     * 多线程输出V3
     * @param tabAiModelBund
     * @param path
     * @param userId
     * @return
     */
    public int saveIdentifyLocalVideoThread(TabAiModelBund tabAiModelBund,String path,String userId);

    /***
     * 多线程输出V5/V8
     * @param tabAiModelBund
     * @param path
     * @param userId
     * @return
     */
    public int saveIdentifyLocalVideoThreadV5(TabAiModelBund tabAiModelBund,String path,String userId);


    Result<String> startAi(TabAiModelBund tabAiModelBund, String path, String userId);

    /***
     * 多组推送内容识别内容
     *
     * @return
     */
    Result<String> startAiPush(TabAiSubscription tabAiSubscription);

    void sendUrl();
    void sendUrlFLV() throws FFmpegFrameGrabber.Exception, FFmpegFrameRecorder.Exception;


}
