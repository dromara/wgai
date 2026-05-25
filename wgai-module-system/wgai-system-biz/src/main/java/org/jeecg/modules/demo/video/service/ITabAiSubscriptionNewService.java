package org.jeecg.modules.demo.video.service;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.video.entity.TabAiSubscriptionNew;
import com.baomidou.mybatisplus.extension.service.IService;

import java.io.IOException;

/**
 * @Description: 多程第三方订阅
 * @Author: wggg
 * @Date:   2025-05-20
 * @Version: V1.0
 */
public interface ITabAiSubscriptionNewService extends IService<TabAiSubscriptionNew> {

    public void startAi(TabAiSubscriptionNew tabAiSubscriptionNew) throws IOException;
    public void stopAi(TabAiSubscriptionNew tabAiSubscriptionNew);

    public void setBox(TabAiSubscriptionNew tabAiSubscriptionNew);

    /***
     * 获取第一张图片
     * @param id
     */
    public Result<String> getVideoPic(String id);


    public Result<?> test(String id);

    public void restartAi(String videoId);
}
