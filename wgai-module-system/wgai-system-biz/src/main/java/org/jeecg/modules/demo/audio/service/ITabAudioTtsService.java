package org.jeecg.modules.demo.audio.service;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.audio.entity.TabAudioTts;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

/**
 * @Description: 文本转TTS
 * @Author: wggg
 * @Date:   2025-03-20
 * @Version: V1.0
 */
public interface ITabAudioTtsService extends IService<TabAudioTts> {


    Result<String> textToSpeed(TabAudioTts tabAudioTts);


    Map<String, Object> textToSpeedMap();
}
