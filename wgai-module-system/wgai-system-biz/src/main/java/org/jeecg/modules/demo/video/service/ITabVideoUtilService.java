package org.jeecg.modules.demo.video.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.video.entity.TabVideoUtil;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @Description: 区域入侵配置
 * @Author: WGAI
 * @Date:   2024-08-06
 * @Version: V1.0
 */
public interface ITabVideoUtilService extends IService<TabVideoUtil> {

    //开始区域检测内容
    public Result<?> startVideoUtil(TabVideoUtil tabVideoUtil,String path) throws Exception;

    //结束区域检测内容
    public Result<?> endVideoUtil(TabVideoUtil tabVideoUtil);

    //结束区域检测内容
    public Result<?> getBoxSetting();

}
