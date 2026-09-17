package org.jeecg.modules.demo.train.controller;

import java.util.Arrays;
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
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.easy.service.ITabEasyPicService;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecg.modules.demo.train.entity.TabModelTryOrg;
import org.jeecg.modules.demo.train.service.ITabModelTryOrgService;
import org.jeecg.modules.demo.train.service.ITabModelTryService;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;

import org.jeecg.modules.demo.train.util.picXml;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.service.ITabAiModelService;
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
 * @Description: 模型预训练
 * @Author: WGAI
 * @Date:   2024-12-17
 * @Version: V1.0
 */
@Api(tags="模型预训练")
@RestController
@RequestMapping("/train/tabModelTry")
@Slf4j
public class TabModelTryController extends JeecgController<TabModelTry, ITabModelTryService> {

	 @Autowired
	 private ITabModelTryService tabModelTryService;
	 @Autowired
	 private ITabAiModelService iTabAiModelService;
	 @Autowired
	 private ITabModelTryOrgService tabModelTryOrgService;
	 @Autowired
	 private ITabEasyPicService tabEasyPicService;

	 @Autowired
	 private org.jeecg.modules.demo.train.service.impl.ModelFileRestoreService modelFileRestoreService;

	 @AutoLog(value = "模型预训练-从XML恢复")
	 @ApiOperation(value = "从XML目录恢复模型和图片标注", notes = "递归恢复标签、标注图、背景图及模型图片关联")
	 @PostMapping("/restoreFromXml")
	 public Result<?> restoreFromXml(@RequestBody com.alibaba.fastjson.JSONObject request) {
		 long started = System.currentTimeMillis();
		 log.info("[模型恢复][收到请求] 模型ID={}，模型名称={}，模型目录={}，XML目录={}，等待执行",
				 request.getString("modelId"), request.getString("modelName"), request.getString("imageDir"), request.getString("xmlDir"));
		 try {
			 com.alibaba.fastjson.JSONObject restored = modelFileRestoreService.restore(request);
			 log.info("[模型恢复][{}][完成，事务已提交] XML总数={}，新增={}，更新={}，标注图={}，背景图={}，保留={}，失败={}，总耗时={}ms",
					 restored.getString("modelId"), restored.get("total"), restored.get("inserted"), restored.get("updated"),
					 restored.get("annotated"), restored.get("backgrounds"), restored.get("skipped"), restored.get("failed"),
					 System.currentTimeMillis() - started);
			 return Result.OK(restored);
		 } catch (Exception e) {
			 log.error("[模型恢复][失败，未完成提交] 模型目录=" + request.getString("imageDir")
					 + "，耗时=" + (System.currentTimeMillis() - started) + "ms", e);
			 return Result.error("恢复失败：" + e.getMessage());
		 }
	 }
	/**
	 * 分页列表查询
	 *
	 * @param tabModelTry
	 * @param pageNo
	 * @param pageSize
	 * @param req
	 * @return
	 */
	//@AutoLog(value = "模型预训练-分页列表查询")
	@ApiOperation(value="模型预训练-分页列表查询", notes="模型预训练-分页列表查询")
	@GetMapping(value = "/list")
	public Result<IPage<TabModelTry>> queryPageList(TabModelTry tabModelTry,
								   @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
								   @RequestParam(name="pageSize", defaultValue="10") Integer pageSize,
								   HttpServletRequest req) {



		QueryWrapper<TabModelTry> queryWrapper = QueryGenerator.initQueryWrapper(tabModelTry, req.getParameterMap());
		Page<TabModelTry> page = new Page<TabModelTry>(pageNo, pageSize);
		IPage<TabModelTry> pageList = tabModelTryService.page(page, queryWrapper);
		return Result.OK(pageList);
	}





	/**
	 *   添加
	 *
	 * @param tabModelTry
	 * @return
	 */
	@AutoLog(value = "模型预训练-添加")
	@ApiOperation(value="模型预训练-添加", notes="模型预训练-添加")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:add")
	@PostMapping(value = "/add")
	public Result<String> add(@RequestBody TabModelTry tabModelTry) {
		//tabModelTryService.save(tabModelTry);
		if(tabModelTry.getUpdatePic().equals("Y")){
			return tabModelTryService.savePatch( tabModelTry);
		}else{
			boolean  bool=tabModelTryService.save(tabModelTry);
			if(bool){
				return Result.ok("添加成功");
			}else{
				return Result.error("修改失败");
			}
		}

	}


	 /**
	  *   添加
	  *
	  * @param
	  * @return
	  */
	 @AutoLog(value = "模型预训练-添加标注图片")
	 @ApiOperation(value="模型预训练-添加标注图片", notes="模型预训练-添加标注图片")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:add")
	 @PostMapping(value = "/addMarkPic")
	 public Result<String> addMarkPic(@RequestBody List<picXml>  picXml) {
		 //
		 return tabModelTryService.saveMake( picXml);
	 }


	 /**
	  *   添加
	  *
	  * @param
	  * @return
	  */
	 @AutoLog(value = "自动添加标注图片")
	 @ApiOperation(value="自动添加标注图片", notes="自动添加标注图片")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:add")
	 @PostMapping(value = "/autoMarkPic")
	 public Result<String> autoMarkPic(@RequestBody picXml  picXml) {
		 //tabModelTryService.save(tabModelTry);
		 QueryWrapper<TabEasyPic> queryWrapper=new QueryWrapper();

		 if(picXml.getIsMark()==1){//0 是 1 否
			 queryWrapper.and(w -> w.ne("mark_type", "Y").or().isNull("mark_type"));
		 }
		 queryWrapper.eq("model_id",picXml.getModelId());
		 List<TabEasyPic> tabModelTryOrg = tabEasyPicService.list(queryWrapper);
		 TabAiModel tabAiModel=iTabAiModelService.getById(picXml.getAiModel());
		 return tabModelTryService.autoSaveMake( tabModelTryOrg,tabAiModel);
	 }
	/**
	 *  编辑
	 *
	 * @param tabModelTry
	 * @return
	 */
	@AutoLog(value = "模型预训练-编辑")
	@ApiOperation(value="模型预训练-编辑", notes="模型预训练-编辑")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:edit")
	@RequestMapping(value = "/edit", method = {RequestMethod.PUT,RequestMethod.POST})
	public Result<String> edit(@RequestBody TabModelTry tabModelTry) {
		if(tabModelTry.getUpdatePic().equals("Y")){
			return tabModelTryService.savePatch( tabModelTry);
		}else{
			boolean  bool=tabModelTryService.updateById(tabModelTry);
			if(bool){
				return Result.ok("修改成功");
			}else{
				return Result.error("修改失败");
			}
		}
		// tabModelTryService.updateById(tabModelTry);

	}
	 @ApiOperation(value="获取模型下所有图片列表", notes="获取模型下所有图片列表")
	 @GetMapping(value = "/listPic")
	 public Result<?> listPic(@RequestParam(name="id",required=true) String id ,  @RequestParam(name="pageNo", defaultValue="1") Integer pageNo,
							  @RequestParam(name="pageSize", defaultValue="10") Integer pageSize) {

		 List<TabModelTryOrg> tabModelTryOrg = tabModelTryOrgService.list(new QueryWrapper<TabModelTryOrg>().eq("model_id",id));
		 if(tabModelTryOrg.size()<=0) {
			 return Result.error("未找到对应数据");
		 }
		 //List<TabEasyPic> pic=tabEasyPicService.listByIds(tabModelTryOrg.stream().map(TabModelTryOrg::getPicId).collect(Collectors.toList()));

		 Page<TabEasyPic> page = new Page<TabEasyPic>(pageNo, pageSize);
		 QueryWrapper<TabEasyPic> queryWrapper=new QueryWrapper<>();
		 queryWrapper.eq("model_id",id);
		 queryWrapper.orderByAsc("mark_Type","pic_name");
		 IPage<TabEasyPic> pic=tabEasyPicService.page(page,queryWrapper);
		 return  Result.ok(pic);
	 }


	 @ApiOperation(value="获取模型下所有图片列表", notes="获取模型下所有图片列表")
	 @GetMapping(value = "/getTabModelTry")
	 public Result<?> getTabModelTry(@RequestParam(name="id",required=true) String id) {

		TabModelTry tabModelTry=this.tabModelTryService.getById(id);
		return  Result.ok(tabModelTry);
	 }

	 @ApiOperation(value="更新标记数", notes="更新标记数")
	 @GetMapping(value = "/startMakeNum")
	 public Result<?> startMakeNum() {


		 return  tabModelTryService.saveMakeNum();
	 }
	/**
	 *   通过id删除
	 *
	 * @param id
	 * @return
	 */
	@AutoLog(value = "模型预训练-通过id删除")
	@ApiOperation(value="模型预训练-通过id删除", notes="模型预训练-通过id删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:delete")
	@DeleteMapping(value = "/delete")
	public Result<String> delete(@RequestParam(name="id",required=true) String id) {
		tabModelTryService.removeById(id);
		return Result.OK("删除成功!");
	}


	 /**
	  *  批量恢复
	  *
	  * @param ids
	  * @return
	  */
	 @AutoLog(value = "模型预训练-批量恢复")
	 @ApiOperation(value="模型预训练-批量恢复", notes="模型预训练-批量恢复")
	 //@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:deleteBatch")
	 @GetMapping(value = "/getBatchPic")
	 public Result<String> getBatchPic(@RequestParam(name="ids",required=true) String ids) {
		 List<String> info=Arrays.asList(ids.split(","));
		 QueryWrapper<TabModelTry> queryWrapper=new QueryWrapper<>();
		 queryWrapper.in("id",info);
		 List<TabModelTry> list=this.tabModelTryService.list(queryWrapper);

		 return  tabModelTryService.getBatchPic(list);
	 }
	
	/**
	 *  批量删除
	 *
	 * @param ids
	 * @return
	 */
	@AutoLog(value = "模型预训练-批量删除")
	@ApiOperation(value="模型预训练-批量删除", notes="模型预训练-批量删除")
	//@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:deleteBatch")
	@DeleteMapping(value = "/deleteBatch")
	public Result<String> deleteBatch(@RequestParam(name="ids",required=true) String ids) {
		this.tabModelTryService.removeByIds(Arrays.asList(ids.split(",")));
		return Result.OK("批量删除成功!");
	}
	
	/**
	 * 通过id查询
	 *
	 * @param id
	 * @return
	 */
	//@AutoLog(value = "模型预训练-通过id查询")
	@ApiOperation(value="模型预训练-通过id查询", notes="模型预训练-通过id查询")
	@GetMapping(value = "/queryById")
	public Result<TabModelTry> queryById(@RequestParam(name="id",required=true) String id) {
		TabModelTry tabModelTry = tabModelTryService.getById(id);
		if(tabModelTry==null) {
			return Result.error("未找到对应数据");
		}
		return Result.OK(tabModelTry);
	}

    /**
    * 导出excel
    *
    * @param request
    * @param tabModelTry
    */
    //@RequiresPermissions("org.jeecg.modules.demo:tab_model_try:exportXls")
    @RequestMapping(value = "/exportXls")
    public ModelAndView exportXls(HttpServletRequest request, TabModelTry tabModelTry) {
        return super.exportXls(request, tabModelTry, TabModelTry.class, "模型预训练");
    }

    /**
      * 通过excel导入数据
    *
    * @param request
    * @param response
    * @return
    */
    //@RequiresPermissions("tab_model_try:importExcel")
    @RequestMapping(value = "/importExcel", method = RequestMethod.POST)
    public Result<?> importExcel(HttpServletRequest request, HttpServletResponse response) {
        return super.importExcel(request, response, TabModelTry.class);
    }

}
