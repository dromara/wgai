package org.jeecg.modules.demo.video.controller;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.audio.entity.TabAudioDevice;
import org.jeecg.modules.demo.audio.service.ITabAudioDeviceService;
import org.jeecg.modules.demo.video.entity.TabAiModelNew;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabAiVideoSetting;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.service.ITabAiVideoSettingService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.demo.video.service.impl.TabAiSubscriptionNewServiceImpl;
import org.jeecg.modules.demo.video.service.impl.TabVideoUtilServiceImpl;
import org.jeecg.modules.tab.AIModel.NetPush;
import org.jeecg.modules.tab.AIModel.OnnxModelWrapper;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.mapper.TabAiModelMapper;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import com.alibaba.fastjson.JSON;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jeecg.common.aspect.annotation.AutoLog;

 /**
 * @Description: AI视频配置
 * @Author: wggg
 * @Date:   2025-05-19
 * @Version: V1.0
 */
@Api(tags="AI视频配置")
@RestController
@RequestMapping("/video/tabAiVideoSetting")
@Slf4j
public class TabAiVideoSettingController extends JeecgController<TabAiVideoSetting, ITabAiVideoSettingService> {
	@Autowired
	private ITabAiVideoSettingService tabAiVideoSettingService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabAiVideoSetting
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "AI视频配置-分页列表查询")
	@Autowired
	private ITabAudioDeviceService tabAudioDeviceService;

	 @ApiOperation(value="AI视频配置-分页列表查询", notes="AI视频配置-分页列表查询")
	 @GetMapping(value = "/list")
	 public Result<IPage<TabAiVideoSetting>> queryPageList(TabAiVideoSetting tabAiVideoSetting,
														   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
														   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
														   HttpServletRequest req) {
		 QueryWrapper<TabAiVideoSetting> queryWrapper = QueryGenerator.initQueryWrapper(tabAiVideoSetting, req.getParameterMap());
		 Page<TabAiVideoSetting> page = new Page<>(pageNo, pageSize);
		 IPage<TabAiVideoSetting> pageList = tabAiVideoSettingService.page(page, queryWrapper);

		 List<TabAiVideoSetting> records = pageList.getRecords();
		 if (CollectionUtils.isNotEmpty(records)) {
			 // 1. 收集所有设备ID（去重）
			 Set<String> allIds = records.stream()
					 .map(TabAiVideoSetting::getAudioDevice)
					 .filter(StringUtils::isNotBlank)
					 .flatMap(s -> Arrays.stream(s.split(",")))
					 .map(String::trim)
					 .filter(StringUtils::isNotBlank)
					 .collect(Collectors.toSet());

			 if (!allIds.isEmpty()) {
				 // 2. 一次性查出所有设备名称，建立 id -> name 映射
				 List<TabAudioDevice> devices = tabAudioDeviceService.listByIds(allIds);
				 Map<String, String> idNameMap = devices.stream()
						 .collect(Collectors.toMap(
								 d -> String.valueOf(d.getId()),
								 TabAudioDevice::getDeviceName,
								 (a, b) -> a));

				 // 3. 回填每条记录
				 for (TabAiVideoSetting record : records) {
					 String ids = record.getAudioDevice();
					 if (StringUtils.isBlank(ids)) continue;
					 String names = Arrays.stream(ids.split(","))
							 .map(String::trim)
							 .map(idNameMap::get)
							 .filter(Objects::nonNull)
							 .collect(Collectors.joining(","));
					 record.setAudioDeviceName(names);
				 }
			 }
		 }

		 return Result.OK(pageList);
	 }
	
	/**
	 *   添加
	 *
	 * @param tabAiVideoSetting
	 * @return
	 */
	@AutoLog(value = "AI视频配置-添加")
	@ApiOperation(value="AI视频配置-添加", notes="AI视频配置-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_video_setting:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabAiVideoSetting tabAiVideoSetting) {

		tabAiVideoSettingService.save(tabAiVideoSetting);
		return Result.OK("添加成功！");
	}
	
	/**
	 *  编辑
	 *
	 * @param tabAiVideoSetting
	 * @return
	 */
	@AutoLog(value = "AI视频配置-编辑")
	@ApiOperation(value="AI视频配置-编辑", notes="AI视频配置-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_video_setting:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabAiVideoSetting tabAiVideoSetting) {
		tabAiVideoSettingService.updateById(tabAiVideoSetting);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "AI视频配置-通过id删除")
	@ApiOperation(value="AI视频配置-通过id删除", notes="AI视频配置-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_video_setting:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabAiVideoSettingService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "AI视频配置-批量删除")
	@ApiOperation(value="AI视频配置-批量删除", notes="AI视频配置-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_ai_video_setting:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabAiVideoSettingService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}



	 /**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "AI视频配置-通过id查询")
	@ApiOperation(value="AI视频配置-通过id查询", notes="AI视频配置-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabAiVideoSetting> queryById(@RequestParam(name="id",required=true) String id) {
		TabAiVideoSetting tabAiVideoSetting = tabAiVideoSettingService.getById(id);
		if(tabAiVideoSetting==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabAiVideoSetting);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabAiVideoSetting
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_video_setting:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabAiVideoSetting tabAiVideoSetting) {
        return super.exportXls(request, tabAiVideoSetting, TabAiVideoSetting.class, "AI视频配置");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_ai_video_setting:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabAiVideoSetting.class);
    }

}
