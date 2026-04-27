-- 注意：该页面对应的前台目录为views/ros2文件夹下
-- 如果你想更改到其他目录，请修改sql中component字段对应的值


INSERT INTO sys_permission(id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, status, del_flag, rule_flag, create_by, create_time, update_by, update_time, internal_or_external) 
VALUES ('202604210539120430', NULL, 'ROS脚本', '/ros2/tabRosPythonList', 'ros2/TabRosPythonList', NULL, NULL, 0, NULL, '1', 0.00, 0, NULL, 1, 1, 0, 0, 0, NULL, '1', 0, 0, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0);

-- 权限控制sql
-- 新增
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120431', '202604210539120430', '添加ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:add', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);
-- 编辑
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120432', '202604210539120430', '编辑ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:edit', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);
-- 删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120433', '202604210539120430', '删除ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:delete', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);
-- 批量删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120434', '202604210539120430', '批量删除ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:deleteBatch', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);
-- 导出excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120435', '202604210539120430', '导出excel_ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:exportXls', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);
-- 导入excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('202604210539120436', '202604210539120430', '导入excel_ROS脚本', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ros_python:importExcel', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2026-04-21 17:39:43', NULL, NULL, 0, 0, '1', 0);