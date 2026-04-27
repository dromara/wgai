package org.jeecg.modules.tab.entity;

import lombok.Data;

/**
 * @author Administrator
 * @date 2024/4/9 19:31
 * 
 */
@Data
public class pushEntity {

//"modelId":"模型id",
//        "modelName":"模型名称",
//        "type":"识别类型(图片|视频)",
//        "time":"报警时间(yyyy-MM-ddHH:mm:ss)",
//        "alarmPicName":"图片名称",
//        "alarmPicData":"Base64的报警图片",
//        "imageWidth":"1920(冗余参数,非必要)",
//        "imageHeight":"1080(冗余参数,非必要)",
//        "cameraUrl":"摄像头流地址",
//        "video":"报警视频播放地址(冗余参数)"
public String cameraName;
    public String indexCode;

    public String modelId;
    public String modelName;
    public String time;
    public String type;
    public String alarmPicName;
    public String alarmPicData;
    public String imageWidth;
    public String imageHeight;
    public String cameraUrl;
    public String video;

    //报警数量
    public Integer aiNumber;
    //报警内容
    private String  modelText;

    //报警区域
    private String  modelArea;
}
