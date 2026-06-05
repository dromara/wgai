package org.jeecg.modules.demo.audio.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import org.jeecg.modules.demo.audio.mapper.TabAudioTtsMapper;
import org.jeecg.modules.demo.audio.service.ITabAudioTtsService;
import org.jeecg.modules.tab.AIModel.identify.audioTypeAll;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.Map;

/**
 * @Description: 文本转TTS
 * @Author: wggg
 * @Date:   2025-03-20
 * @Version: V1.0
 */
@Service
public class TabAudioTtsServiceImpl extends ServiceImpl<TabAudioTtsMapper, TabAudioTts> implements ITabAudioTtsService {

    @Value("${jeecg.path.upload}")
    private String upLoadPath;
    @Override
    public Result<String> textToSpeed(TabAudioTts tabAudioTts) {

        if(StringUtils.isNotEmpty(tabAudioTts.getId())){
            String path="";
            TabAudioTts tabAudioTts1=this.getById(tabAudioTts.getId());
            if(StringUtils.isNotEmpty(tabAudioTts1.getDictDir())){
                path=    audioTypeAll.textToTtsDict(upLoadPath,tabAudioTts1);
            }else{
                path=audioTypeAll.textToTtsNotDict(upLoadPath,tabAudioTts1);
            }
            tabAudioTts.setSavePath(path);
            this.updateById(tabAudioTts);
            return Result.OK("转换成功！");
        }else{
            return Result.OK("转换失败！");
        }

    }


    @Override
    public Map<String, Object> textToSpeedMap() {
        TabAudioTts tabAudioTts=this.getById("1902621058529599489");
        return audioTypeAll.textToTtsNotDictMap(upLoadPath,tabAudioTts);

    }
}
