package org.jeecg.modules.demo.video.service;

import org.jeecg.modules.demo.video.entity.TabAiWarning;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @Description: 报警信息
 * @Author: wggg
 * @Date:   2025-02-20
 * @Version: V1.0
 */
public interface ITabAiWarningService extends IService<TabAiWarning> {
    void savePush(org.jeecg.modules.tab.entity.pushEntity pushInfo);

}
