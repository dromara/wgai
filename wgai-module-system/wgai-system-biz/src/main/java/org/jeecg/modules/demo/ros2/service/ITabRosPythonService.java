package org.jeecg.modules.demo.ros2.service;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.demo.ros2.entity.TabRosPython;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * @Description: ROS脚本
 * @Author: wggg
 * @Date:   2026-04-21
 * @Version: V1.0
 */
public interface ITabRosPythonService extends IService<TabRosPython> {


    Result<String> startPy(TabRosPython rosPython);

}
