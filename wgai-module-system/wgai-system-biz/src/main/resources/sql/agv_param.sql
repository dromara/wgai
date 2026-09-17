-- =============================================================================
-- AGV 运行参数：挂到已有的 ROS 脚本表 tab_ros_python 上，只加一列 JSON。
--
-- 目的：把 application.yml 里 plc.* 那批**纯热改**参数搬进数据库，改完立即生效，
--       不用再重启 Java 服务。yml 保留同名配置作为**默认值兜底** ——
--       JSON 里没写的 key、或 Java 起来时连不上库，仍然走 yml 的值，不会开不了机。
--
-- 为什么是一列 JSON 而不是 29 个具体列：
--   参数有 29 个且还在增加，一个一个开列会把脚本表撑成 29 列宽，而且以后每加一个
--   参数都要 ALTER + 改实体 + 改页面。JSON 只存"改过的 key"，参数的名称/分组/单位/
--   取值范围/说明统一在 Java 的 AgvParamDef 里定义，前端从接口拿着渲染。
--
-- 生效方式：AgvParamService 启动时读本列覆盖各 Service 上 @Value 注入的字段；
--           页面保存时写回本列并就地重新下发，无需重启。
--
-- 取哪一行的值：只认 ros_name = 'AGV运行参数' 的那一条固定记录，别的脚本记录上的
--               agv_param 一概不看。这条记录不存在时，Java 启动会**按 application.yml
--               的现值自动建出来**，所以整个流程是：改 yml → 重启一次 → 以后全在页面上调。
--               下面的 INSERT 不用手工执行，留着只是说明这条记录长什么样。
--
-- ⚠ 只收纯热改参数。下面这几类写进 JSON 不会报错，但也不会生效，别这么干：
--   · plc.enabled / plc.bridge.enabled —— 启动期开关，现场用导航页「连接 PLC」按钮控制
--   · plc.host / port / rack / slot     —— 改了必须重连 PLC，不是改个数就完事
--
-- 数据库：MySQL 5.7+。达梦(dm8) 需把 COMMENT 改成 COMMENT ON 语句。
-- =============================================================================

ALTER TABLE `tab_ros_python`
  ADD COLUMN `agv_param` text DEFAULT NULL COMMENT 'AGV运行参数JSON(key为plc.*配置项，仅存改过的项)';


-- -----------------------------------------------------------------------------
-- 参数载体记录长这样，Java 启动时会自动建，**不需要手工执行下面这条**。
-- 只有在想提前铺好数据、或者不小心把它删了又不想重启时才用得上。
-- key 必须和 application.yml 里的 plc.* 完全一致，写错的 key 启动会打 warn 并忽略。
-- -----------------------------------------------------------------------------
-- INSERT INTO `tab_ros_python` (`id`, `ros_name`, `remake`, `sort`, `agv_param`)
-- VALUES (REPLACE(UUID(),'-',''), 'AGV运行参数', 'AGV运行参数载体，不是脚本，请勿删除', '9999', '{
--   "plc.auto-nav.max-rpm": "500",
--   "plc.wheel.rpm-scale": "10.0",
--   "plc.obstacle.stop.distance": "1.0"
-- }');


-- -----------------------------------------------------------------------------
-- 早先那版独立参数表 tab_agv_param / 两个字典已作废，本方案不再用。
-- 如果之前执行过，确认没别的地方引用后再手动清理（默认注释掉，不替你删）：
-- -----------------------------------------------------------------------------
-- DROP TABLE IF EXISTS `tab_agv_param`;
-- DELETE FROM `sys_dict_item` WHERE `dict_id` IN ('agv_param_group_dict', 'agv_param_type_dict');
-- DELETE FROM `sys_dict`      WHERE `id`      IN ('agv_param_group_dict', 'agv_param_type_dict');
