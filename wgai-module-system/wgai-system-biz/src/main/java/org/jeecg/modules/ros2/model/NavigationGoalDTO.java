package org.jeecg.modules.ros2.model;


import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 导航目标点 DTO
 */
@Data
public class NavigationGoalDTO {

    /**
     * 目标 X 坐标 —— **车头雷达**停车位置(和初始点位、车图标同一锚点)，车身从这里往后伸 5m。
     * 后端(NavigationService)再换算成 Nav2 的 base_link。
     */
    @NotNull(message = "X坐标不能为空")
    private Double x;

    /**
     * 目标 Y 坐标
     */
    @NotNull(message = "Y坐标不能为空")
    private Double y;

    /**
     * 目标方向角（弧度）。**null = 不指定**，后端取"当前位置 → 目标点"的方位角。
     * ⚠ 以前默认 0.0、前端点地图也一直传 0 —— 等于每个目标都要求车头朝地图 +X 停下，
     *   往北开的目标到了终点还得原地拐 90°，5.5m 的车 Smac 根本搜不出来，每次卡满超时(2026-09-14)。
     */
    private Double theta;

    /**
     * 终点侧向对位距离(m)，null/0 = 不用。车身左侧为正。
     * Nav2 先开到"终点往右挪这么多"的预备位，到位后平移模式横移过去 ——
     * 用于贴料堆/墙边停车这种弧线拐不进去、但横着挪得进去的位置。
     */
    private Double sideApproachM;

    /**
     * 精确停车。null/false(默认) = 非固定目标：Nav2 到了附近(位置容差内、不管朝向)就算到达，不再调整；
     * true = 到达后原地转到目标朝向 + 平移消横向偏差。有侧向对位时总是精确停车。
     */
    private Boolean precise;
}
