package org.jeecg.modules.demo.szr.entity;

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
 * @Description: 数字人动作
 * @Author: wggg
 * @Date:   2025-04-30
 * @Version: V1.0
 */
@Data
@TableName("tab_szr_dz")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_szr_dz对象", description="数字人动作")
public class TabSzrDz implements Serializable {
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
	/**数字人id*/
	@Excel(name = "数字人id", width = 15, dictTable = "tab_szr_video", dicText = "szr_name", dicCode = "id")
	@Dict(dictTable = "tab_szr_video", dicText = "szr_name", dicCode = "id")
    @ApiModelProperty(value = "数字人id")
    private java.lang.String szrId;
	/**数字人name*/
	@Excel(name = "数字人name", width = 15)
    @ApiModelProperty(value = "数字人name")
    private java.lang.String szrName;
	/**数字人动作*/
	@Excel(name = "数字人动作", width = 15)
    @ApiModelProperty(value = "数字人动作")
    private java.lang.String szrTitle;
	/**数字人文件*/
	@Excel(name = "数字人文件", width = 15)
    @ApiModelProperty(value = "数字人文件")
    private java.lang.String szrFile;
	/**数字人帧率*/
	@Excel(name = "数字人帧率", width = 15)
    @ApiModelProperty(value = "数字人帧率")
    private java.lang.Double szrFps;
	/**背景色*/
	@Excel(name = "背景色", width = 15)
    @ApiModelProperty(value = "背景色")
    private java.lang.String szrColor;
	/**数字人标签*/
	@Excel(name = "数字人标签", width = 15)
    @ApiModelProperty(value = "数字人标签")
    private java.lang.String szrBq;
	/**驱动形象ID*/
	/*
	 * MuseTalk 预处理缓存的目录名，对应驱动服务上的
	 * <MuseTalk根目录>/results/<版本>/avatars/<avatar_id>/
	 *
	 * ⚠ 每个要说话的动作视频都必须单独跑过一次 avatar 预处理才会有这个目录 ——
	 *   说话画面的底板只来自这个缓存，驱动服务不会去读原始视频文件。
	 * ⚠ 填了不存在的 id 不会报错，驱动服务会退回默认形象并打告警，
	 *   现象是"选了动作但画面没变"。用 GET /szr/speak/avatars 核对实际加载了哪些。
	 */
	@Excel(name = "驱动形象ID", width = 20)
    @ApiModelProperty(value = "驱动形象ID")
    private java.lang.String avatarId;
	/**动作类型*/
	/* 0=静置待机（不说话时循环播放，播报时用不到）  1=说话驱动 */
	@Excel(name = "动作类型", width = 15, replace = {"静置待机_0", "说话驱动_1"})
	@Dict(dicCode = "szr_dz_type")
    @ApiModelProperty(value = "动作类型:0=静置待机 1=说话驱动")
    private java.lang.Integer dzType;
	/**是否默认说话动作*/
	/* 播报接口没传 dzId 时用这条。同一个数字人下应当只有一条为 1 */
	@Excel(name = "默认动作", width = 15, replace = {"否_0", "是_1"})
	@Dict(dicCode = "yn")
    @ApiModelProperty(value = "是否默认说话动作:1=是 0=否")
    private java.lang.Integer isDefault;
	/**训练状态*/
	/*
	 * 0=未训练 1=训练中 2=成功 3=失败。
	 * ⚠ 只有 2 才是真正可用的：预处理缓存目录存在、驱动服务能加载。
	 *   其余状态即使填了 avatar_id，播报时也会被驱动服务退回默认形象。
	 */
	@Excel(name = "训练状态", width = 15, dicCode = "szr_train_status")
	@Dict(dicCode = "szr_train_status")
    @ApiModelProperty(value = "训练状态:0未训练 1训练中 2成功 3失败")
    private java.lang.Integer trainStatus;
	/**训练结果说明*/
	/* 失败原因，或成功时的帧数/分辨率摘要。直接显示在列表页，省得去翻服务器日志 */
	@Excel(name = "训练结果说明", width = 40)
    @ApiModelProperty(value = "训练结果说明")
    private java.lang.String trainMsg;
}
