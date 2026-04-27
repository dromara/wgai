package org.jeecg.modules.demo.video.entity;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.math.BigDecimal;
import java.util.List;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.springframework.format.annotation.DateTimeFormat;
import org.jeecgframework.poi.excel.annotation.Excel;
import org.jeecg.common.aspect.annotation.Dict;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * @Description: 多程第三方订阅
 * @Author: wggg
 * @Date:   2025-05-20
 * @Version: V1.0
 */
@Data
@TableName("tab_ai_subscription_new")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_ai_subscription_new对象", description="多程第三方订阅")
public class TabAiSubscriptionNew implements Serializable {
    private static final long serialVersionUID = 1L;

	/**id*/
	@TableId(type = IdType.ASSIGN_ID)
    @ApiModelProperty(value = "id")
    private java.lang.String id;
	/**创建人*/
    @ApiModelProperty(value = "创建人")
    private java.lang.String createBy;
	/**创建日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern="yyyy-MM-dd")
    @ApiModelProperty(value = "创建日期")
    private java.util.Date createTime;
	/**更新人*/
    @ApiModelProperty(value = "更新人")
    private java.lang.String updateBy;
	/**更新日期*/
	@JsonFormat(timezone = "GMT+8",pattern = "yyyy-MM-dd")
    @DateTimeFormat(pattern="yyyy-MM-dd")
    @ApiModelProperty(value = "更新日期")
    private java.util.Date updateTime;
	/**所属部门*/
    @ApiModelProperty(value = "所属部门")
    private java.lang.String sysOrgCode;
	/**解码脚本*/

    @Dict(dicCode = "py_type")
	@Excel(name = "解码脚本", width = 15)
    @ApiModelProperty(value = "解码脚本")
    private java.lang.String pyType;
	/**解码方式*/

    @Dict(dicCode = "jm_type")
	@Excel(name = "解码方式", width = 15)
    @ApiModelProperty(value = "解码方式")
    private java.lang.String eventTypes;
	/**订阅回调地址*/
	@Excel(name = "订阅回调地址", width = 15)
    @ApiModelProperty(value = "订阅回调地址")
    private java.lang.String eventUrl;
	/**设备编号*/
	@Excel(name = "设备编号", width = 15)
    @ApiModelProperty(value = "设备编号")
    private java.lang.String indexCode;
	/**同类型报警间隔*/
	@Excel(name = "同类型报警间隔", width = 15)
    @ApiModelProperty(value = "同类型报警间隔")
    private java.lang.String eventNumber;
	/**报警消息*/
	@Excel(name = "报警消息", width = 15)
    @ApiModelProperty(value = "报警消息")
    private java.lang.String eventInfo;
	/**备注*/
	@Excel(name = "备注", width = 15)
    @ApiModelProperty(value = "备注")
    private java.lang.String remake;
	/**推送状态*/
    @Dict(dicCode = "push_static")
	@Excel(name = "推送状态", width = 15)
    @ApiModelProperty(value = "推送状态")
    private java.lang.Integer pushStatic;
	/**执行状态*/
    @Dict(dicCode = "run_state")
	@Excel(name = "执行状态", width = 15)
    @ApiModelProperty(value = "执行状态")
    private java.lang.Integer runState;
	/**名称*/
	@Excel(name = "名称", width = 15)
    @ApiModelProperty(value = "名称")
    private java.lang.String name;
	/**播报状态*/
	@Excel(name = "播报状态", width = 15)
    @ApiModelProperty(value = "播报状态")
    private java.lang.Integer audioStatic;
	/**播报地址*/
	@Excel(name = "播报地址", width = 15)
    @ApiModelProperty(value = "播报地址")
    private java.lang.String audioId;
	/**是否需要前置*/
    @Dict(dicCode = "push_static")
	@Excel(name = "是否分析保存录像", width = 15)
    @ApiModelProperty(value = "是否分析保存录像")
    private java.lang.Integer isBegin;
	/**前置模型类型*/
	@Excel(name = "视频流", width = 15)
    @ApiModelProperty(value = "视频流")
    private java.lang.String beginEventTypes;
	/**前置模型内容*/
	@Excel(name = "前置模型内容", width = 15)
    @ApiModelProperty(value = "前置模型内容")
    private java.lang.String beginName;
	/**保存目录*/
	@Excel(name = "保存目录", width = 15)
    @ApiModelProperty(value = "保存目录")
    private java.lang.String pathSave;
	/**是否保存图片*/
    @Dict(dicCode = "push_static")
	@Excel(name = "是否保存图片", width = 15)
    @ApiModelProperty(value = "是否保存图片")
    private java.lang.Integer savePic;

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

	/**是否开启报警录像*/
    @Dict(dicCode = "push_static")
	@Excel(name = "是否开启报警录像", width = 15)
    @ApiModelProperty(value = "是否开启报警录像")
    private java.lang.Integer isRecording;
	/**报价录像时间*/
	@Excel(name = "报价录像时间", width = 15)
    @ApiModelProperty(value = "报价录像时间")
    private java.lang.Integer recordTime;
	/**是否本地保存录像*/
    @Dict(dicCode = "push_static")
	@Excel(name = "是否本地保存录像", width = 15)
    @ApiModelProperty(value = "是否本地保存录像")
    private java.lang.Integer saveRecord;

    @Dict(dicCode = "push_static")
    @Excel(name = "是否保存本地报警", width = 15)
    @ApiModelProperty(value = "是否保存本地报警")
    private java.lang.Integer saveLocalhost;

    @Dict(dicCode = "push_static")
    @Excel(name = "是否开启区域识别", width = 15)
    @ApiModelProperty(value = "是否开启区域识别")
    private java.lang.Integer isBy;

    @ApiModelProperty(value = "解码方式")
    @Dict(dicCode = "model_type")
    private Integer modelJmType;

    @ApiModelProperty(value = "推送开始时间")
    private Integer difyStartEnd;

    @ApiModelProperty(value = "推送结束时间")
    private Integer difyStartTime;

    @TableField(exist = false)
    TabVideoUtil tabVideoUtil;

//    @TableField(exist = false)
//    List<TabAiVideoSetting> listSetting;

    @TableField(exist = false)
    List<TabAiModelNew> tabAiModelNewList;
//
    //、不需要从前端接收它
    @JsonIgnore
    @TableField(exist = false)
    List<NetPush> netPushList;
}
