-- 注意：该页面对应的前台目录为views/video文件夹下
-- 如果你想更改到其他目录，请修改sql中component字段对应的值


INSERT INTO sys_permission(id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description, status, del_flag, rule_flag, create_by, create_time, update_by, update_time, internal_or_external) 
VALUES ('2025052002128940240', NULL, '多程第三方订阅', '/video/tabAiSubscriptionNewList', 'video/TabAiSubscriptionNewList', NULL, NULL, 0, NULL, '1', 0.00, 0, NULL, 1, 1, 0, 0, 0, NULL, '1', 0, 0, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0);

-- 权限控制sql
-- 新增
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950241', '2025052002128940240', '添加多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:add', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);
-- 编辑
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950242', '2025052002128940240', '编辑多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:edit', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);
-- 删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950243', '2025052002128940240', '删除多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:delete', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);
-- 批量删除
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950244', '2025052002128940240', '批量删除多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:deleteBatch', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);
-- 导出excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950245', '2025052002128940240', '导出excel_多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:exportXls', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);
-- 导入excel
INSERT INTO sys_permission(id, parent_id, name, url, component, is_route, component_name, redirect, menu_type, perms, perms_type, sort_no, always_show, icon, is_leaf, keep_alive, hidden, hide_tab, description, create_by, create_time, update_by, update_time, del_flag, rule_flag, status, internal_or_external)
VALUES ('2025052002128950246', '2025052002128940240', '导入excel_多程第三方订阅', NULL, NULL, 0, NULL, NULL, 2, 'org.jeecg.modules.demo:tab_ai_subscription_new:importExcel', '1', NULL, 0, NULL, 1, 0, 0, 0, NULL, 'admin', '2025-05-20 14:12:24', NULL, NULL, 0, 0, '1', 0);