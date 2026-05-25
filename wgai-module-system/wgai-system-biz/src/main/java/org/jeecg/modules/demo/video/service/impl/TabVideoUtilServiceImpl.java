package org.jeecg.modules.demo.video.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.demo.tab.entity.TabAiModelBund;
import org.jeecg.modules.demo.tab.service.ITabAiModelBundService;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import org.jeecg.modules.demo.video.mapper.TabVideoUtilMapper;
import org.jeecg.modules.demo.video.service.ITabVideoUtilService;
import org.jeecg.modules.message.websocket.WebSocket;
import org.jeecg.modules.tab.AIModel.AIModelYolo3;
import org.jeecg.modules.tab.entity.TabAiModel;
import org.jeecg.modules.tab.service.ITabAiModelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 区域入侵配置
 * @Author: WGAI
 * @Date:   2024-08-06
 * @Version: V1.0
 */
@Slf4j
@Service
public class TabVideoUtilServiceImpl extends ServiceImpl<TabVideoUtilMapper, TabVideoUtil> implements ITabVideoUtilService {

    @Resource
    WebSocket webSocket;
    @Resource
    RedisUtil redisUtil;
    @Autowired
    ITabAiModelService iTabAiModelService;
    @Autowired
    ITabAiModelBundService iTabAiModelBundService;
    @Autowired
    public RedisTemplate redisTemplate;
    @Override
    public Result<?> startVideoUtil(TabVideoUtil tabVideoUtil,String path) throws Exception {
        //开启入侵
        redisUtil.set(tabVideoUtil.getId(),true);
        redisUtil.expire(tabVideoUtil.getId(),365*24*60);//开启一次一年
        //入侵内容
        redisUtil.set("WGAI_RQNR"+tabVideoUtil.getId(),tabVideoUtil.getVideoJson());
        redisUtil.expire("WGAI_RQNR"+tabVideoUtil.getId(),365*24*60);//开启一次一年

        if(StringUtils.isNotEmpty(tabVideoUtil.getSpareTwo())){//区域入侵内容默认只使用v5
            //获取模型内容
            TabAiModel tabAiModel1=iTabAiModelService.getById(tabVideoUtil.getSpareTwo());
            //获取视频内容
            TabAiModelBund tabAiModelBund=iTabAiModelBundService.getById(tabVideoUtil.getVideoId());
            //开辟子线程去执行推理计算  2核一个视频流
            AIModelYolo3 modelYolo3=new AIModelYolo3();
            String savePath=modelYolo3.SendVideoLocalhostYoloV5ThreadVideoUtil(tabVideoUtil,tabAiModel1.getAiWeights(),tabAiModel1.getAiConfig(),tabAiModel1.getAiNameName(),tabAiModelBund.getSendUrl(),path,webSocket,redisUtil,redisTemplate);

        }else{
            log.error("[未绑定模型咱不处理]");
            return Result.error("未绑定模型咱不处理");
        }
        //区域入侵 /

        return null;
    }

    @Override
    public Result<?> endVideoUtil(TabVideoUtil tabVideoUtil) {
        redisUtil.set(tabVideoUtil.getId(),false);
        redisUtil.expire(tabVideoUtil.getId(),365*24*60);//开启一次一年
        return Result.OK("结束成功");
    }

    @Override
    public Result<?> getBoxSetting() {
    //    {"version":"1.0","imageWidth":2560,"imageHeight":1440,"drawMode":"multi-rect","shapeCount":2,"shapes":[{"id":"zone_1","type":"rect","name":"16号仓","broadcastEnabled":true,"broadcastContent":"16号仓播报1111！","coordinates":{"startX":236,"startY":424,"endX":772,"endY":1132,"width":536,"height":708}},{"id":"zone_2","type":"polygon","name":"17号仓","broadcastEnabled":true,"broadcastContent":"17号仓开启熏蒸","coordinates":{"points":[{"x":1396,"y":332},{"x":2128,"y":356},{"x":2028,"y":1312},{"x":1088,"y":1200}]}}]}
        List<TabVideoUtil> tabVideoUtilList=this.list(new LambdaQueryWrapper<TabVideoUtil>().ne(TabVideoUtil::getShapeCount,0));
        List<String> videoList =new ArrayList<>();
        for (TabVideoUtil tabVideo:tabVideoUtilList) {
            JSONObject shapeData = JSON.parseObject(tabVideo.getShapeData());
            JSONArray shapes    = shapeData.getJSONArray("shapes");
            boolean flag=false;
            for (int i = 0; i < shapes.size(); i++) {
                JSONObject shape = shapes.getJSONObject(i);
                String id = shape.getString("id");
                if(id.indexOf("zone_")>-1||id.length()<=2){
                    continue;
                }
                flag=true;
            }
            if(flag){
                videoList.add(tabVideo.getId());
            }
        }
        List<TabVideoUtil> tabVideoUtil=new ArrayList<>();
        if (videoList.size()>0) {
            tabVideoUtil=this.listByIds(videoList);
        }

        return Result.OK(tabVideoUtil);
    }
}
