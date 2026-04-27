package org.jeecg.modules.tab.AIModel;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.ToString;
import org.jeecg.common.aspect.annotation.Dict;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabAiVideoSetting;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.opencv.dnn.Net;

import java.util.List;

/**
 * @author wggg
 * @date 2025/2/24 11:26
 *
 * FIXED: Added @JsonIgnore and @ToString.Exclude to break circular references
 */
@Data
public class NetPush {

    String id;

    Integer index;

    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    Net net;

    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    OrtSession session;

    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    OrtEnvironment env;

    String modelType;

    List<String> claseeNames;

    Integer isBefor;

    String beforText;

    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止自引用循环
    List<NetPush> listNetPush;

    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    TabAiModel tabAiModel;

    String uploadPath;

    /**是否跟随前置坐标 0 是 1 否*/
    @Dict(dicCode = "push_static")
    @Excel(name = "是否跟随前置坐标", width = 15)
    @ApiModelProperty(value = "是否跟随前置坐标")
    private Integer isFollow;

    @Dict(dicCode = "push_static")
    @Excel(name = "是否跟随前置放大", width = 15)
    @ApiModelProperty(value = "是否跟随前置放大")
    private Integer isBeforZoom;

    @Excel(name = "跟随最大距离", width = 15)
    @ApiModelProperty(value = "跟随最大距离")
    private Integer followPosition;


    @Dict(dicCode = "push_static")
    @Excel(name = "是否识别预警 默认0 1否", width = 15)
    @ApiModelProperty(value = "是否识别预警 默认0 1否")
    private Integer warinngMethod;


    @Excel(name = "未识别到预警文本", width = 15)
    @ApiModelProperty(value = "未识别到预警文本")
    private String noDifText;


    @ApiModelProperty(value = "是否开启区域识别")
    Integer isBy;

    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    TabVideoUtil tabVideoUtil;

    @ApiModelProperty(value = "识别类型1.图像识别 2.姿态 3.多边形")
    Integer difyType;


    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环
    TabAiVideoSetting tabAiVideoSetting;


    @Dict(dicCode = "push_static")
    @Excel(name = "是否保存图片", width = 15)
    @ApiModelProperty(value = "是否保存失败图片")
    private java.lang.Integer saveErrorPic;

    @Dict(dicCode = "push_static")
    @Excel(name = "是否保存图片", width = 15)
    @ApiModelProperty(value = "是否保存前置调试图片")
    private java.lang.Integer saveBeforePic;


    @Dict(dicCode = "push_static")
    @Excel(name = "是否保存图片", width = 15)
    @ApiModelProperty(value = "是否保存放大调试图")
    private java.lang.Integer saveRoiPic;


    @JsonIgnore  // JSON 序列化时忽略
    @ToString.Exclude  // toString() 方法中排除 - 防止循环引用
    TabAiSubscriptionNew tabAiSubscriptionNew;
}