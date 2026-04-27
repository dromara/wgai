package org.jeecg.modules.demo.video.controller;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.tab.entity.PushInfo;
import org.jeecg.modules.demo.video.entity.TabAiWarning;
import org.jeecg.modules.demo.video.service.ITabAiWarningService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.tab.entity.pushEntity;
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
 * @Description: 报警信息
 * @Author: wggg
 * @Date:   2025-02-20
 * @Version: V1.0
 */
@Api(tags="报警信息")
@RestController
@RequestMapping("/video/tabAiWarning")
@Slf4j
public class TabAiWarningController extends JeecgController<TabAiWarning, ITabAiWarningService> {
	@Autowired
	private ITabAiWarningService tabAiWarningService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabAiWarning
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "报警信息-分页列表查询")
	@ApiOperation(value="报警信息-分页列表查询", notes="报警信息-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabAiWarning>> queryPageList(TabAiWarning tabAiWarning,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		QueryWrapper<TabAiWarning> queryWrapper = QueryGenerator.initQueryWrapper(tabAiWarning, req.getParameterMap());
		Page<TabAiWarning> page = new Page<TabAiWarning>(pageNo, pageSize);
		IPage<TabAiWarning> pageList = tabAiWarningService.page(page, queryWrapper);
		return Result.OK(pageList);
	}
	
	/**
	 *   添加
	 *
	 * @param tabAiWarning
	 * @return
	 */
	@AutoLog(value = "报警信息-添加")
	@ApiOperation(value="报警信息-添加", notes="报警信息-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabAiWarning tabAiWarning) {
		tabAiWarningService.save(tabAiWarning);
		return Result.OK("添加成功！");
	}


	 @AutoLog(value = "报警信息-添加")
	 @ApiOperation(value="报警信息-添加", notes="报警信息-添加")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:add")
	 @PostMapping(value = "/addPush")
	 public Result<String> addPush(@RequestBody pushEntity pushInfo) {
		 TabAiWarning tabAiWarning=new TabAiWarning();
		 tabAiWarning.setWarningName(pushInfo.getCameraName());
		 tabAiWarning.setWarningType("识别报警");
		 tabAiWarning.setWarningInfo(pushInfo.getModelName());
		 tabAiWarning.setWaringText(pushInfo.getModelText());
		 tabAiWarning.setWarningTime(new Date());
		 tabAiWarning.setWarningPic(pushInfo.getAlarmPicData());
		 tabAiWarning.setWarningCome(pushInfo.getVideo());
		 tabAiWarning.setWaringAi(pushInfo.getModelId());
		 tabAiWarning.setWaringState("未处理");
		 tabAiWarning.setRemake(pushInfo.getModelArea());
	//	 tabAiWarning.setw
		 tabAiWarningService.save(tabAiWarning);
		 return Result.OK("添加成功！");
	 }
	
	/**
	 *  编辑
	 *
	 * @param tabAiWarning
	 * @return
	 */
	@AutoLog(value = "报警信息-编辑")
	@ApiOperation(value="报警信息-编辑", notes="报警信息-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabAiWarning tabAiWarning) {
		tabAiWarningService.updateById(tabAiWarning);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "报警信息-通过id删除")
	@ApiOperation(value="报警信息-通过id删除", notes="报警信息-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabAiWarningService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "报警信息-批量删除")
	@ApiOperation(value="报警信息-批量删除", notes="报警信息-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabAiWarningService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "报警信息-通过id查询")
	@ApiOperation(value="报警信息-通过id查询", notes="报警信息-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabAiWarning> queryById(@RequestParam(name="id",required=true) String id) {
		TabAiWarning tabAiWarning = tabAiWarningService.getById(id);
		if(tabAiWarning==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabAiWarning);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabAiWarning
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_warning:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabAiWarning tabAiWarning) {
        return super.exportXls(request, tabAiWarning, TabAiWarning.class, "报警信息");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_ai_warning:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabAiWarning.class);
    }

}
