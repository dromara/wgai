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
 * @Description: 数字人训练脚本
 * @Author: wggg
 * @Date:   2025-04-17
 * @Version: V1.0
 */
@Data
@TableName("tab_szr_python")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_szr_python对象", description="数字人训练脚本")
public class TabSzrPython implements Serializable {
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
	/**脚本名称*/
	@Excel(name = "脚本名称", width = 15)
    @ApiModelProperty(value = "脚本名称")
    private java.lang.String pyName;
	/**脚本地址*/
	@Excel(name = "脚本地址", width = 15)
    @ApiModelProperty(value = "脚本地址")
    private java.lang.String pyUrl;
	/**脚本文件*/
	@Excel(name = "脚本文件", width = 15)
    @ApiModelProperty(value = "脚本文件")
    private java.lang.String pyPath;
	/**脚本备用*/
	@Excel(name = "脚本备用", width = 15)
    @ApiModelProperty(value = "脚本备用")
    private java.lang.String spareOne;
	/**脚本类型*/
	@Excel(name = "脚本类型", width = 15, dicCode = "py_type")
	@Dict(dicCode = "py_type")
    @ApiModelProperty(value = "脚本类型")
    private java.lang.String pyType;
	/**脚本顺序*/
	@Excel(name = "脚本顺序", width = 15)
    @ApiModelProperty(value = "脚本顺序")
    private java.lang.Integer pysort;
	/**
	 * 音频文件是否需要上传。
	 *
	 * <p>1 = 上传：Java 把 wav 以 multipart 推给驱动服务的 /speak_upload。
	 * 适用于 Java 和 Python 不在同一台机器（跨机、容器、不同挂载）。
	 *
	 * <p>0 = 不上传：只把文件绝对路径传给 /speak，由驱动服务自己去读。
	 * <b>前提是两边在同一台服务器（或共享同一个挂载点），否则驱动服务找不到文件。</b>
	 * 省掉一次文件传输，长音频更快。
	 */
	@Excel(name = "是否上传音频", width = 15, dicCode = "yn")
	@Dict(dicCode = "yn")
    @ApiModelProperty(value = "音频是否上传：1=上传文件 0=同机直接给路径")
    private java.lang.Integer needUpload;
}
