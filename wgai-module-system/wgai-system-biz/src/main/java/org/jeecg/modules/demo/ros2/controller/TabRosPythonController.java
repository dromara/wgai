package org.jeecg.modules.demo.ros2.controller;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.ros2.entity.TabRosPython;
import org.jeecg.modules.demo.ros2.service.ITabRosPythonService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import com.alibaba.fastjson.JSON;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jeecg.common.aspect.annotation.AutoLog;

 /**
 * @Description: ROS脚本
 * @Author: wggg
 * @Date:   2026-04-21
 * @Version: V1.0
 */
@Api(tags="ROS脚本")
@RestController
@RequestMapping("/ros2/tabRosPython")
@Slf4j
public class TabRosPythonController extends JeecgController<TabRosPython, ITabRosPythonService> {
	@Autowired
	private ITabRosPythonService tabRosPythonService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabRosPython
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "ROS脚本-分页列表查询")
	@ApiOperation(value="ROS脚本-分页列表查询", notes="ROS脚本-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabRosPython>> queryPageList(TabRosPython tabRosPython,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		Map<String, String[]> paramMap = new HashMap<>(req.getParameterMap());

// 移除你不想要的参数，比如 "xxx"
		paramMap.remove("column");
		paramMap.remove("order");

		QueryWrapper<TabRosPython> queryWrapper = QueryGenerator.initQueryWrapper(tabRosPython, paramMap);
		queryWrapper.orderByAsc("py_type","sort");
		Page<TabRosPython> page = new Page<TabRosPython>(pageNo, pageSize);
		IPage<TabRosPython> pageList = tabRosPythonService.page(page, queryWrapper);
		return Result.OK(pageList);
	}

	 /***
	  * 单步执行脚本 训练模型
	  * @return
	  */
	 @AutoLog(value = "训练脚本模板-训练模型")
	 @ApiOperation(value="训练脚本模板-训练模型", notes="训练脚本模板-训练模型")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_train_python:add")
	 @GetMapping(value = "/startOnePy")
	 public Result<String> startOnePy(@RequestParam(name="id",required=true) String id) {

		 TabRosPython modelTryList=tabRosPythonService.getById(id);

		 tabRosPythonService.startPy(modelTryList);
		 return Result.OK("添加成功！");
	 }
	
	/**
	 *   添加
	 *
	 * @param tabRosPython
	 * @return
	 */
	@AutoLog(value = "ROS脚本-添加")
	@ApiOperation(value="ROS脚本-添加", notes="ROS脚本-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ros_python:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabRosPython tabRosPython) {
		tabRosPythonService.save(tabRosPython);
		return Result.OK("添加成功！");
	}
	
	/**
	 *  编辑
	 *
	 * @param tabRosPython
	 * @return
	 */
	@AutoLog(value = "ROS脚本-编辑")
	@ApiOperation(value="ROS脚本-编辑", notes="ROS脚本-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ros_python:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabRosPython tabRosPython) {
		tabRosPythonService.updateById(tabRosPython);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "ROS脚本-通过id删除")
	@ApiOperation(value="ROS脚本-通过id删除", notes="ROS脚本-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ros_python:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabRosPythonService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "ROS脚本-批量删除")
	@ApiOperation(value="ROS脚本-批量删除", notes="ROS脚本-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ros_python:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabRosPythonService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "ROS脚本-通过id查询")
	@ApiOperation(value="ROS脚本-通过id查询", notes="ROS脚本-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabRosPython> queryById(@RequestParam(name="id",required=true) String id) {
		TabRosPython tabRosPython = tabRosPythonService.getById(id);
		if(tabRosPython==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabRosPython);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabRosPython
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_ros_python:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabRosPython tabRosPython) {
        return super.exportXls(request, tabRosPython, TabRosPython.class, "ROS脚本");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_ros_python:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabRosPython.class);
    }

}
