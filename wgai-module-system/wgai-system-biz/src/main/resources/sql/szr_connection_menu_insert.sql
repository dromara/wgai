-- 数字人连接管理菜单；前端组件：views/szr/SzrConnectionList.vue
INSERT INTO sys_permission
    (id, parent_id, name, url, component, component_name, redirect, menu_type, perms, perms_type,
     sort_no, always_show, icon, is_route, is_leaf, keep_alive, hidden, hide_tab, description,
     status, del_flag, rule_flag, create_by, create_time, update_by, update_time, internal_or_external)
VALUES
    ('2026090809000000010', NULL, '数字人连接管理', '/szr/szrConnectionList', 'szr/SzrConnectionList',
     NULL, NULL, 0, NULL, '1', 0.00, 0, NULL, 1, 1, 0, 0, 0,
     '查看并移除当前数字人连接', '1', 0, 0, 'admin', NOW(), NULL, NULL, 0);
