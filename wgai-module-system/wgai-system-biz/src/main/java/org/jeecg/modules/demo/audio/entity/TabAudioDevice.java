package org.jeecg.modules.demo.audio.entity;

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
 * @Description: 播报设备
 * @Author: wggg
 * @Date:   2025-03-07
 * @Version: V1.0
 */
@Data
@TableName("tab_audio_device")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_audio_device对象", description="播报设备")
public class TabAudioDevice implements Serializable {
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
	/**设备名称*/
	@Excel(name = "设备名称", width = 15)
    @ApiModelProperty(value = "设备名称")
    private java.lang.String deviceName;
	/**设备URL*/
	@Excel(name = "设备URL", width = 15)
    @ApiModelProperty(value = "设备URL")
    private java.lang.String deivceUrl;
	/**设备唯一值*/
	@Excel(name = "设备唯一值", width = 15)
    @ApiModelProperty(value = "设备唯一值")
    private java.lang.String deviceUid;
	/**用户名*/
	@Excel(name = "用户名", width = 15)
    @ApiModelProperty(value = "用户名")
    private java.lang.String username;
	/**密码*/
	@Excel(name = "密码", width = 15)
    @ApiModelProperty(value = "密码")
    private java.lang.String pwd;
	/**是否启用*/
	@Excel(name = "是否启用", width = 15)
    @ApiModelProperty(value = "是否启用")
    private java.lang.String isState;
	/**token超时*/
	@Excel(name = "token超时", width = 15)
    @ApiModelProperty(value = "token超时")
    private java.lang.Integer tokenTime;
	/**厂家*/
	@Excel(name = "厂家", width = 15, dicCode = "device_fac")
	@Dict(dicCode = "device_fac")
    @ApiModelProperty(value = "厂家")
    private java.lang.String deviceFac;
	/**文字转语音*/
	@Excel(name = "文字转语音", width = 15)
    @ApiModelProperty(value = "文字转语音")
    private java.lang.String isAudio;


    @Excel(name = "速度", width = 15)
    @ApiModelProperty(value = "速度")
    private Integer  speed;

    @Excel(name = "音量", width = 15)
    @ApiModelProperty(value = "音量")
    private Integer  volume;

    @Excel(name = "测试播放内容", width = 15)
    @ApiModelProperty(value = "测试播放内容")
    private String  testPlay;
}
