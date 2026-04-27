package org.jeecg.modules.demo.video.entity;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.format.annotation.DateTimeFormat;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.jeecg.common.aspect.annotation.Dict;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * @Description: AI视频配置
 * @Author: wggg
 * @Date:   2025-05-19
 * @Version: V1.0
 */
@Data
@TableName("tab_ai_video_setting")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_ai_video_setting对象", description="AI视频配置")
public class TabAiVideoSetting implements Serializable {
    private static final long serialVersionUID = 1L;

	/**主键*/
	@TableId(type = IdType.ASSIGN_ID)
    @ApiModelProperty(value = "主键")
    private java.lang.String id;
	/**创建人*/
    @ApiModelProperty(value = "创建人")
    private java.lang.String createBy;
	/**创建日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "创建日期")
    private java.util.Date createTime;
	/**更新人*/
    @ApiModelProperty(value = "更新人")
    private java.lang.String updateBy;
	/**更新日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd HH:mm:ss")
    @DateTimeFormat(pattern="yyyy-MM-dd HH:mm:ss")
    @ApiModelProperty(value = "更新日期")
    private java.util.Date updateTime;
	/**所属部门*/
    @ApiModelProperty(value = "所属部门")
    private java.lang.String sysOrgCode;
	/**订阅ID*/
    @Dict(dictTable = "tab_ai_subscription_new", dicCode = "id", dicText = "name")
	@Excel(name = "订阅ID", width = 15)
    @ApiModelProperty(value = "订阅ID")
    private java.lang.String subId;
	/**是否需要前置*/
    @Dict(dicCode = "push_static")
	@Excel(name = "是否需要前置", width = 15)
    @ApiModelProperty(value = "是否需要前置")
    private java.lang.String isBefor;
	/**前置模型*/

    @Dict(dictTable = "tab_ai_model", dicCode = "id", dicText = "ai_name")
	@Excel(name = "前置模型", width = 15)
    @ApiModelProperty(value = "前置模型")
    private java.lang.String modelId;
	/**前置识别内容*/
	@Excel(name = "前置识别内容", width = 15)
    @ApiModelProperty(value = "前置识别内容")
    private java.lang.String modelTxt;
	/**后置模型*/

    @Dict(dictTable = "tab_ai_model", dicCode = "id", dicText = "ai_name")
	@Excel(name = "后置模型", width = 15)
    @ApiModelProperty(value = "后置模型")
    private java.lang.String nextMode;
	/**备用1*/
	@Excel(name = "备用1", width = 15)
    @ApiModelProperty(value = "备用1")
    private java.lang.String spareOne;


    /**是否跟随前置坐标*/
    @Dict(dicCode = "push_static")
    @Excel(name = "是否跟随前置坐标", width = 15)
    @ApiModelProperty(value = "是否跟随前置坐标")
    private Integer isFollow;



    @Excel(name = "跟随最大距离", width = 15)
    @ApiModelProperty(value = "跟随最大距离")
    private Integer followPosition;


    @Dict(dicCode = "push_static")
    @Excel(name = "预警方式", width = 15)
    @ApiModelProperty(value = "预警方式 null/1识别到预警2.未识别到预警")
    private Integer warinngMethod;


    @Excel(name = "未识别到预警文本", width = 15)
    @ApiModelProperty(value = "未识别到预警文本")
    private String noDifText;

    @ApiModelProperty(value = "识别方式")
    @Dict(dicCode = "dify_type")
    private Integer difyType;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否开启区域识别")
    private Integer isBy;
    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否推送报警区域")
    private Integer isByPush;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否推送报警区域")
    private Integer isByWrite;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否跟随前置放大")
    private Integer isBeforZoom;



    @Dict(dicCode = "warn_type")
    @ApiModelProperty(value = "报警方式")
    private Integer warnType;

    @ApiModelProperty(value = "报警时间间隔")
    private Integer warnTime;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否开启报警录像")
    private Integer isRecording;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否分析录像")
    private Integer isAnalysis;

    @ApiModelProperty(value = "报警录像时间")
    private Integer recordTime;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否本地保存录像")
    private Integer saveRecord;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "保存本地")
    private Integer saveLocalhost;

    @Dict(dicCode = "push_static")
    @ApiModelProperty(value = "是否播报")
    private Integer isAudio;

}
