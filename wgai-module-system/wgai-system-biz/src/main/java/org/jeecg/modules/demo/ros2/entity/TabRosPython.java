package org.jeecg.modules.demo.ros2.entity;

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
 * @Description: ROS脚本
 * @Author: wggg
 * @Date:   2026-04-21
 * @Version: V1.0
 */
@Data
@TableName("tab_ros_python")
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = false)
@ApiModel(value="tab_ros_python对象", description="ROS脚本")
public class TabRosPython implements Serializable {
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
    private java.lang.String rosName;
	/**额外前置命令*/
	@Excel(name = "额外前置命令", width = 15)
    @ApiModelProperty(value = "额外前置命令")
    private java.lang.String beforePy;
	/**脚本目录*/
	@Excel(name = "脚本目录", width = 15)
    @ApiModelProperty(value = "脚本目录")
    private java.lang.String pyPath;
	/**脚本文件*/
	@Excel(name = "脚本文件", width = 15)
    @ApiModelProperty(value = "脚本文件")
    private java.lang.String rosFile;
	/**额外后置命令*/
	@Excel(name = "额外后置命令", width = 15)
    @ApiModelProperty(value = "额外后置命令")
    private java.lang.String endPy;
	/**脚本备注*/
	@Excel(name = "脚本备注", width = 15)
    @ApiModelProperty(value = "脚本备注")
    private java.lang.String remake;
	/**脚本类型*/
	@Excel(name = "脚本类型", width = 15, dicCode = "ros_py_type")
	@Dict(dicCode = "ros_py_type")
    @ApiModelProperty(value = "脚本类型")
    private java.lang.String pyType;
	/**执行顺序*/
	@Excel(name = "执行顺序", width = 15)
    @ApiModelProperty(value = "执行顺序")
    private java.lang.String sort;
}
