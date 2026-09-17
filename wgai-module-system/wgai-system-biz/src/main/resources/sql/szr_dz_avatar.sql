-- =============================================================================
-- 数字人动作表：接入 MuseTalk 多形象驱动
-- =============================================================================
--
-- 一个数字人（tab_szr_video，如「小张模型」）下面挂多条动作（tab_szr_dz）：
--     正常站立 / 左介绍 / 右介绍 ...
-- 静默时循环播放【静置动作】，收到播报时用调用方指定的【说话动作】驱动口型。
--
-- ⚠ avatar_id 不是随便起的名字，必须是驱动服务上真实存在的预处理缓存目录：
--       <MuseTalk根目录>/results/v15/avatars/<avatar_id>/
--   每个要说话的动作视频都得单独跑一次 avatar 预处理才会有这个目录。
--   填了不存在的 id，驱动服务会退回默认形象并打告警，不会报错 ——
--   现象是「选了左介绍但画面没变」，容易误判成前端没传参。
--   可以调 GET /szr/speak/avatars 核对哪些 id 真正被驱动服务加载了。
--
-- ⚠ 同一台驱动服务上所有形象的画面尺寸必须一致（推流管道分辨率启动时就固定了），
--   尺寸不一致的会被驱动服务跳过。
ALTER TABLE tab_szr_dz ADD COLUMN avatar_id varchar(100) DEFAULT NULL COMMENT '驱动形象ID:对应 results/<版本>/avatars/<id>/ 预处理目录';

-- 0 = 静置：不说话时循环播放的待机画面。由驱动服务 config.yaml 的 avatar.idle_video 决定，
--           这里登记一条只是为了在页面上看得见，播报时不会用到。
-- 1 = 说话：可被播报接口指定的驱动动作，必须有 avatar_id。
ALTER TABLE tab_szr_dz ADD COLUMN dz_type int(1) DEFAULT 1 COMMENT '动作类型:0=静置待机 1=说话驱动';

-- 该数字人默认用哪个说话动作。播报接口没传 dzId 时用它。
-- 同一个 szr_id 下应当只有一条为 1，多条时取 create_time 最早的那条。
ALTER TABLE tab_szr_dz ADD COLUMN is_default int(1) DEFAULT 0 COMMENT '是否默认说话动作:1=是 0=否';

-- 存量数据：老记录都当成说话动作，avatar_id 需要人工补
UPDATE tab_szr_dz SET dz_type = 1 WHERE dz_type IS NULL;
UPDATE tab_szr_dz SET is_default = 0 WHERE is_default IS NULL;


-- =============================================================================
-- 动作类型字典 szr_dz_type（页面下拉用）
-- =============================================================================
INSERT INTO sys_dict (id, dict_name, dict_code, description, del_flag, create_by, create_time, type)
SELECT '1900000000000000010', '数字人动作类型', 'szr_dz_type', '0=静置待机 1=说话驱动', 0, 'admin', NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'szr_dz_type');

INSERT INTO sys_dict_item
    (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000011', id, '静置待机', '0', '不说话时循环播放的画面', 1, 1, 0, 'admin', NOW()
FROM sys_dict
WHERE dict_code = 'szr_dz_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '0');

INSERT INTO sys_dict_item
    (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000012', id, '说话驱动', '1', '播报时可指定的驱动动作，必须有驱动形象ID', 2, 1, 0, 'admin', NOW()
FROM sys_dict
WHERE dict_code = 'szr_dz_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '1');
