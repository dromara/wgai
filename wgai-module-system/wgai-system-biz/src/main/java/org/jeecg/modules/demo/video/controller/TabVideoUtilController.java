package org.jeecg.modules.demo.video.controller;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.system.query.QueryGenerator;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.service.ITabAiSubscriptionNewService;
import org.jeecg.modules.demo.video.service.ITabVideoUtilService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecgframework.poi.excel.ExcelImportUtil;
import org.jeecgframework.poi.excel.def.NormalExcelConstants;
import org.jeecgframework.poi.excel.entity.ExportParams;
import org.jeecgframework.poi.excel.entity.ImportParams;
import org.jeecgframework.poi.excel.view.JeecgEntityExcelView;
import org.jeecg.common.system.base.controller.JeecgController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import com.alibaba.fastjson.JSON;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.jeecg.common.aspect.annotation.AutoLog;

 /**
 * @Description: 区域入侵配置
 * @Author: WGAI
 * @Date:   2024-08-06
 * @Version: V1.0
 */
@Api(tags="区域入侵配置")
@RestController
@RequestMapping("/video/tabVideoUtil")
@Slf4j
public class TabVideoUtilController extends JeecgController<TabVideoUtil, ITabVideoUtilService> {
	@Autowired
	private ITabVideoUtilService tabVideoUtilService;
	
	/**
	 * 分页列表查询
	 *
	 * @param tabVideoUtil
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "区域入侵配置-分页列表查询")
	@ApiOperation(value="区域入侵配置-分页列表查询", notes="区域入侵配置-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabVideoUtil>> queryPageList(TabVideoUtil tabVideoUtil,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {
		QueryWrapper<TabVideoUtil> queryWrapper = QueryGenerator.initQueryWrapper(tabVideoUtil, req.getParameterMap());
		Page<TabVideoUtil> page = new Page<TabVideoUtil>(pageNo, pageSize);
		IPage<TabVideoUtil> pageList = tabVideoUtilService.page(page, queryWrapper);
		return Result.OK(pageList);
	}
	
	/**
	 *   添加
	 *
	 * @param tabVideoUtil
	 * @return
	 */
	@AutoLog(value = "区域入侵配置-添加")
	@ApiOperation(value="区域入侵配置-添加", notes="区域入侵配置-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabVideoUtil tabVideoUtil) {
		tabVideoUtilService.save(tabVideoUtil);
		return Result.OK("添加成功！");
	}
	
	/**
	 *  编辑
	 *
	 * @param tabVideoUtil
	 * @return
	 */
	@AutoLog(value = "区域入侵配置-编辑")
	@ApiOperation(value="区域入侵配置-编辑", notes="区域入侵配置-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabVideoUtil tabVideoUtil) {
		tabVideoUtilService.updateById(tabVideoUtil);
		return Result.OK("编辑成功!");
	}

	 @AutoLog(value = "获取视频原图")
	 @ApiOperation(value="获取视频原图", notes="获取视频原图")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/getVideoPic", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<TabVideoUtil> getVideoPic(@RequestParam(name="id",required=true) String id) {
		 QueryWrapper<TabVideoUtil> queryWrapper=new QueryWrapper<>();
		 queryWrapper.eq("video_id",id);
		 TabVideoUtil tabVideoUtil1= tabVideoUtilService.getOne(queryWrapper);
		 if(tabVideoUtil1!=null){
			 return Result.ok(tabVideoUtil1);
		 }
		 return Result.error("未找到");

	 }
	 @Autowired
	 RedisTemplate redisTemplate;
	 @AutoLog(value = "获取视频原图")
	 @ApiOperation(value="获取视频原图", notes="获取视频原图")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/getVideoPicSetting", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<TabVideoUtil> getVideoPicSetting(@RequestParam(name="id",required=true) String id) {

		 TabVideoUtil tabVideoUtil1= tabVideoUtilService.getById(id);
		 if(tabVideoUtil1!=null){
			 if(StringUtils.isNotEmpty(tabVideoUtil1.getVideoId())){
				 Object 	pic= redisTemplate.opsForValue().get(tabVideoUtil1.getVideoId());
				 if(pic!=null){
					 tabVideoUtil1.setRemerk(tabVideoUtil1.getVideoId()+".jpg");
				 }
			 }
			 return Result.ok(tabVideoUtil1);
		 }
		 return Result.error("未找到");

	 }



	 @AutoLog(value = "获取视频原图")
	 @ApiOperation(value="获取视频原图", notes="获取视频原图")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_ai_subscription_new:edit")
	 @RequestMapping(value = "/getVideoVideoSetting", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<TabVideoUtil> getVideoVideoSetting(@RequestParam(name="id",required=true) String id) {

		 QueryWrapper<TabVideoUtil> queryWrapper=new QueryWrapper<>();
		 queryWrapper.eq("video_id",id);
		 TabVideoUtil tabVideoUtil1= tabVideoUtilService.getOne(queryWrapper);
		 if(tabVideoUtil1!=null){

			 return Result.ok(tabVideoUtil1);
		 }
		 return Result.error("未找到");

	 }
	 /**
	  *  第一次新增后续修改
	  *
	  * @param tabVideoUtil
	  * @return
	  */
	 @AutoLog(value = "识别区域配置")
	 @ApiOperation(value="区域入侵配置-编辑", notes="区域入侵配置-编辑")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:edit")
	 @RequestMapping(value = "/saveBox", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> saveBox(@RequestBody TabVideoUtil tabVideoUtil) {

		 String videoId=tabVideoUtil.getVideoId();
		 QueryWrapper<TabVideoUtil> queryWrapper=new QueryWrapper<>();
		 queryWrapper.eq("video_id",videoId);
		 TabVideoUtil tabVideoUtil1= tabVideoUtilService.getOne(queryWrapper);
		 if(tabVideoUtil1==null){
			 tabVideoUtilService.save(tabVideoUtil);
		 }else{
			 tabVideoUtil.setId(tabVideoUtil1.getId());
			 tabVideoUtilService.updateById(tabVideoUtil);
		 }
		 return Result.OK("编辑成功!");
	 }

	 @AutoLog(value = "识别区域配置")
	 @ApiOperation(value="区域入侵配置-编辑", notes="区域入侵配置-编辑")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:edit")
	 @RequestMapping(value = "/saveBoxSetting", method = {RequestMethod.PUT,RequestMethod.POST})
	 public Result<String> saveBoxSetting(@RequestBody TabVideoUtil tabVideoUtil) {

		 String videoId=tabVideoUtil.getId();
//		 QueryWrapper<TabVideoUtil> queryWrapper=new QueryWrapper<>();
//		 queryWrapper.eq("video_id",videoId);
		 TabVideoUtil tabVideoUtil1= tabVideoUtilService.getById(videoId);
		 if(tabVideoUtil1==null){
			 tabVideoUtil.setId(tabVideoUtil.getId());
			 tabVideoUtilService.save(tabVideoUtil);
		 }else{
			 tabVideoUtil.setId(tabVideoUtil1.getId());
			 tabVideoUtilService.updateById(tabVideoUtil);
		 }
		 return Result.OK("编辑成功!");
	 }



	 @AutoLog(value = "识别区域配置获取")
	 @ApiOperation(value="区域入侵配置-获取", notes="区域入侵配置-获取")
	 @RequestMapping(value = "/getBoxSetting", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<?> getBoxSetting() {
		 return tabVideoUtilService.getBoxSetting();
	 }


	 @AutoLog(value = "识别区域配置获取")
	 @ApiOperation(value="区域入侵配置-配置", notes="区域入侵配置-配置")
	 @RequestMapping(value = "/setBoxSetting", method = {RequestMethod.GET,RequestMethod.POST})
	 public Result<?> setBoxSetting(@RequestParam(name="id",required=true) String id,
									@RequestParam(name="shapeId",required=true) String shapeId,
									@RequestParam(name="name",required=true) String name,
									@RequestParam(name="broadcastContent",required=true) String broadcastContent,
									@RequestParam(name="broadcastEnabled",required=true) Boolean broadcastEnabled
									) {

		TabVideoUtil tabVideoUtil=tabVideoUtilService.getById(id);
		JSONObject shapeData = JSON.parseObject(tabVideoUtil.getShapeData());
  		JSONArray shapes    = shapeData.getJSONArray("shapes");
		 for (int i = 0; i < shapes.size(); i++) {
			 JSONObject shape = shapes.getJSONObject(i);
			 String shapeid = shape.getString("id");
			 if(shapeid.equals(shapeId)){
				 shape.put("name",name);
				 shape.put("broadcastEnabled",broadcastEnabled);
				 shape.put("broadcastContent",broadcastContent);
			 }

		 }
		 tabVideoUtil.setShapeData(shapeData.toJSONString());
		 tabVideoUtilService.updateById(tabVideoUtil);

		 tabAiSubscriptionNewService.restartAi(tabVideoUtil.getVideoId());
//		 new Thread(() -> {
//
//			 TabAiSubscriptionNew tabAiSubscriptionNew=tabAiSubscriptionNewService.getById(tabVideoUtil.getVideoId());
//			 try {
//				 log.info("停止执行");
//				 tabAiSubscriptionNewService.stopAi(tabAiSubscriptionNew);
//				 Thread.sleep(3000);
//				 log.info("开始执行");
//				 tabAiSubscriptionNewService.startAi(tabAiSubscriptionNew);
//			 } catch (Exception e) {
//				 throw new RuntimeException(e);
//			 }
//		 }).start();

		 return Result.OK("配置成功!");
	 }
	 @Autowired
	 private ITabAiSubscriptionNewService tabAiSubscriptionNewService;

	 @Value(value = "${jeecg.path.upload}")
	 private String uploadpath;
	 /**
	  *  开始区域检测
	  *
	  * @param tabVideoUtil
	  * @return
	  */
	 @AutoLog(value = "区域入侵配置-开启")
	 @ApiOperation(value="区域入侵配置-开启", notes="区域入侵配置-开启")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:edit")
	 @RequestMapping(value = "/startVideoUtil", method = {RequestMethod.POST})
	 public Result<String> startVideoUtil(@RequestBody TabVideoUtil tabVideoUtil) {
		 try {
			 CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
				 try {
					 tabVideoUtilService.startVideoUtil(tabVideoUtil, uploadpath);
				 } catch (Exception e) {
					 throw new RuntimeException(e);
				 }
			 }, Executors.newSingleThreadExecutor());
			 TabVideoUtil tabVideoUtil1=tabVideoUtilService.getById(tabVideoUtil.getId());
			 tabVideoUtil1.setSpareOne("1");
			 tabVideoUtilService.updateById(tabVideoUtil1);
		 } catch (Exception e) {
			 e.printStackTrace();
			 return Result.error("开启失败!");
		 }
		 return Result.OK("开启成功!");
	 }



	 /**
	  *  结束区域检测
	  *
	  * @param tabVideoUtil
	  * @return
	  */
	 @AutoLog(value = "区域入侵配置-结束")
	 @ApiOperation(value="区域入侵配置-结束", notes="区域入侵配置-结束")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:edit")
	 @RequestMapping(value = "/stopVideoUtil", method = {RequestMethod.POST})
	 public Result<String> stopVideoUtil(@RequestBody TabVideoUtil tabVideoUtil) {
		 tabVideoUtilService.endVideoUtil(tabVideoUtil);
		 TabVideoUtil tabVideoUtil1=tabVideoUtilService.getById(tabVideoUtil.getId());
		 tabVideoUtil1.setSpareOne("0");
		 tabVideoUtilService.updateById(tabVideoUtil1);
		 return Result.OK("结束成功!");
	 }
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "区域入侵配置-通过id删除")
	@ApiOperation(value="区域入侵配置-通过id删除", notes="区域入侵配置-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabVideoUtilService.removeById(id);
		return Result.OK("删除成功!");
	}
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "区域入侵配置-批量删除")
	@ApiOperation(value="区域入侵配置-批量删除", notes="区域入侵配置-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabVideoUtilService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "区域入侵配置-通过id查询")
	@ApiOperation(value="区域入侵配置-通过id查询", notes="区域入侵配置-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabVideoUtil> queryById(@RequestParam(name="id",required=true) String id) {
		TabVideoUtil tabVideoUtil = tabVideoUtilService.getById(id);
		if(tabVideoUtil==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabVideoUtil);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabVideoUtil
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_video_util:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabVideoUtil tabVideoUtil) {
        return super.exportXls(request, tabVideoUtil, TabVideoUtil.class, "区域入侵配置");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_video_util:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabVideoUtil.class);
    }

}
