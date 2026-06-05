package org.jeecg.modules.demo.audio.controller;

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

import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import org.jeecg.modules.demo.audio.service.ITabAudioTtsService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.tab.AIModel.identify.audioTypeAll;
import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
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
 * @Description: 文本转TTS
 * @Author: wggg
 * @Date:   2025-03-20
 * @Version: V1.0
 */
@Api(tags="文本转TTS")
@RestController
@RequestMapping("/audio/tabAudioTts")
@Slf4j
public class TabAudioTtsController extends JeecgController<TabAudioTts, ITabAudioTtsService> {
	@Autowired
	private ITabAudioTtsService tabAudioTtsService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabAudioTts
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "文本转TTS-分页列表查询")
	@ApiOperation(value="文本转TTS-分页列表查询", notes="文本转TTS-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabAudioTts>> queryPageList(TabAudioTts tabAudioTts,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		QueryWrapper<TabAudioTts> queryWrapper = QueryGenerator.initQueryWrapper(tabAudioTts, req.getParameterMap());
		Page<TabAudioTts> page = new Page<TabAudioTts>(pageNo, pageSize);
		IPage<TabAudioTts> pageList = tabAudioTtsService.page(page, queryWrapper);
		return Result.OK(pageList);
	}
	
	/**
	 *   添加
	 *
	 * @param tabAudioTts
	 * @return
	 */
	@AutoLog(value = "文本转TTS-添加")
	@ApiOperation(value="文本转TTS-添加", notes="文本转TTS-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabAudioTts tabAudioTts) {
		tabAudioTtsService.save(tabAudioTts);
		return Result.OK("添加成功！");
	}


	 @AutoLog(value = "文本转TTS-转换")
	 @ApiOperation(value="文本转TTS-转换", notes="文本转TTS-转换")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:add")
	 @PostMapping(value = "/textToSpeed")
	 public Result<String> textToSpeed(@RequestBody TabAudioTts tabAudioTts) {

		 return tabAudioTtsService.textToSpeed(tabAudioTts);



	 }



	 @AutoLog(value = "文本转TTS-数字人测试")
	 @ApiOperation(value="数字人测试", notes="数字人测试：先写死一个已有音频文件，前端播放并驱动嘴巴")
	 @GetMapping(value = "/demoSpeak")
	 public Result<Map<String, Object>> demoSpeak(HttpServletRequest request) {
		 try {
			 Map<String, Object> data = tabAudioTtsService.textToSpeedMap();

			 // 拼成前端可直接 fetch 的可访问 URL（沿用你 manualAudioUrl 那种静态映射）
			 String audioUrl = "/wgai/sys/common/static/" + data.get("fileName"); // 按你的静态资源映射改
			 data.put("audioUrl", audioUrl);

			 return Result.OK(data);
		 } catch (Exception e) {
			 log.error("数字人测试接口失败", e);
			 return Result.error("数字人测试接口失败：" + e.getMessage());
		 }
	 }
	
	/**
	 *  编辑
	 *
	 * @param tabAudioTts
	 * @return
	 */
	@AutoLog(value = "文本转TTS-编辑")
	@ApiOperation(value="文本转TTS-编辑", notes="文本转TTS-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabAudioTts tabAudioTts) {
		tabAudioTtsService.updateById(tabAudioTts);
		return Result.OK("编辑成功!");
	}
	
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "文本转TTS-通过id删除")
	@ApiOperation(value="文本转TTS-通过id删除", notes="文本转TTS-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabAudioTtsService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "文本转TTS-批量删除")
	@ApiOperation(value="文本转TTS-批量删除", notes="文本转TTS-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabAudioTtsService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "文本转TTS-通过id查询")
	@ApiOperation(value="文本转TTS-通过id查询", notes="文本转TTS-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabAudioTts> queryById(@RequestParam(name="id",required=true) String id) {
		TabAudioTts tabAudioTts = tabAudioTtsService.getById(id);
		if(tabAudioTts==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabAudioTts);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabAudioTts
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_audio_tts:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabAudioTts tabAudioTts) {
        return super.exportXls(request, tabAudioTts, TabAudioTts.class, "文本转TTS");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_audio_tts:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabAudioTts.class);
    }

}
