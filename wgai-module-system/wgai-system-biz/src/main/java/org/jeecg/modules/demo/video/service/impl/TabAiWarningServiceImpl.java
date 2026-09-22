package org.jeecg.modules.demo.video.service.impl;

import org.jeecg.modules.demo.video.entity.TabAiWarning;
import org.jeecg.modules.demo.video.mapper.TabAiWarningMapper;
import org.jeecg.modules.demo.video.service.ITabAiWarningService;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

/**
 * @Description: 报警信息
 * @Author: wggg
 * @Date:   2025-02-20
 * @Version: V1.0
 */
@lombok.extern.slf4j.Slf4j
@Service
public class TabAiWarningServiceImpl extends ServiceImpl<TabAiWarningMapper, TabAiWarning> implements ITabAiWarningService {

    @Override
    public void savePush(org.jeecg.modules.tab.entity.pushEntity pushInfo) {
		 TabAiWarning tabAiWarning=new TabAiWarning();
		 tabAiWarning.setWarningName(pushInfo.getCameraName());
		 tabAiWarning.setWarningType("识别报警");
		 tabAiWarning.setWarningInfo(pushInfo.getModelName());
		 tabAiWarning.setWaringText(pushInfo.getModelText());
		 tabAiWarning.setWarningTime(new java.util.Date());
		 tabAiWarning.setWarningPic(pushInfo.getAlarmPicData());
		 tabAiWarning.setWarningCome(pushInfo.getVideo());
		 tabAiWarning.setWaringAi(pushInfo.getModelId());
		 tabAiWarning.setWaringState("未处理");
		 tabAiWarning.setRemake(pushInfo.getModelArea());
		 if (!save(tabAiWarning)) { throw new IllegalStateException("报警保存失败"); }
         log.info("[本地报警已保存] camera={} model={} image={} video={}", pushInfo.getCameraName(), pushInfo.getModelId(), pushInfo.getAlarmPicData(), pushInfo.getVideo());
    }
}
