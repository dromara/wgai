-- =============================================================================
-- 数字人形象训练（MuseTalk avatar 预处理）由 Java 平台托管
-- 前提：Java 和驱动服务（Python）在同一台服务器上
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 一、tab_szr_python 增加训练脚本要用的几个路径
--
-- 约定表里有两行（用 py_name 区分，和驱动地址那行一样的做法）：
--   py_name = 'musetalk-driver'  驱动服务，只用 py_url / need_upload
--   py_name = 'musetalk-train'   训练脚本，用下面这几个路径字段
--
-- ⚠ 全部填【绝对路径】。Java 是拿它们直接去读写文件、执行命令的，
--   相对路径会相对 Java 进程的工作目录，几乎必然找错。
-- -----------------------------------------------------------------------------

-- Python 虚拟环境的 activate 脚本。预处理必须先进虚拟环境再跑，
-- 直接用系统 python 会缺 torch/mmcv 一堆依赖。
-- 例：/musetalk/myenv/bin/activate
ALTER TABLE tab_szr_python ADD COLUMN py_venv varchar(255) DEFAULT NULL COMMENT '虚拟环境activate脚本绝对路径';

-- 预处理配置文件。每次点「训练」，Java 会重写这个文件再跑预处理。
-- ⚠ 这个文件会被整体覆盖，不要往里面写要长期保留的内容。
-- 例：/home/shuziren/MuseTalk/configs/inference/realtime.yaml
ALTER TABLE tab_szr_python ADD COLUMN py_conf varchar(255) DEFAULT NULL COMMENT '预处理配置realtime.yaml绝对路径';

-- 驱动服务的 config.yaml。切换数字人时 Java 会改它的
-- avatar.id / avatar.idle_video / extra_avatars 三处。
-- ⚠ Java 只做定点行替换，不会重排文件，里面的注释会原样保留。
-- 另外 MuseTalk 根目录、版本号(v15)也是从这个文件的 musetalk.root / musetalk.version 读的，
-- 所以不用再单独配一遍。
-- 例：/home/shuziren/MuseTalk/streaming/config.yaml
ALTER TABLE tab_szr_python ADD COLUMN py_stream_conf varchar(255) DEFAULT NULL COMMENT '驱动服务config.yaml绝对路径';

-- 驱动服务重启脚本。切换数字人时执行。
-- 例：/home/shuziren/MuseTalk/streaming/restart.sh
ALTER TABLE tab_szr_python ADD COLUMN py_restart varchar(255) DEFAULT NULL COMMENT '驱动服务restart.sh绝对路径';

-- 预填一行训练脚本配置（路径按实际部署改）
INSERT INTO tab_szr_python
    (id, create_by, create_time, py_name, py_type, pysort,
     py_venv, py_conf, py_stream_conf, py_restart)
SELECT '1900000000000000100', 'admin', NOW(), 'musetalk-train', NULL, 2,
       '/musetalk/myenv/bin/activate',
       '/home/shuziren/MuseTalk/configs/inference/realtime.yaml',
       '/home/shuziren/MuseTalk/streaming/config.yaml',
       '/home/shuziren/MuseTalk/streaming/restart.sh'
WHERE NOT EXISTS (SELECT 1 FROM tab_szr_python WHERE py_name = 'musetalk-train');


-- -----------------------------------------------------------------------------
-- 二、tab_szr_dz 增加训练状态
-- -----------------------------------------------------------------------------

-- 0=未训练 1=训练中 2=训练成功 3=训练失败
-- ⚠ 只有 2 的动作才是真正可用的：预处理缓存目录存在，驱动服务能加载。
ALTER TABLE tab_szr_dz ADD COLUMN train_status int(1) DEFAULT 0 COMMENT '训练状态:0未训练 1训练中 2成功 3失败';

-- 失败原因 / 成功时的帧数分辨率摘要，直接显示在列表页，省得去翻服务器日志
ALTER TABLE tab_szr_dz ADD COLUMN train_msg varchar(1000) DEFAULT NULL COMMENT '训练结果说明';

UPDATE tab_szr_dz SET train_status = 0 WHERE train_status IS NULL;


-- -----------------------------------------------------------------------------
-- 三、字典
-- -----------------------------------------------------------------------------
INSERT INTO sys_dict (id, dict_name, dict_code, description, del_flag, create_by, create_time, type)
SELECT '1900000000000000020', '数字人训练状态', 'szr_train_status', '0未训练 1训练中 2成功 3失败', 0, 'admin', NOW(), 0
WHERE NOT EXISTS (SELECT 1 FROM sys_dict WHERE dict_code = 'szr_train_status');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000021', id, '未训练', '0', '还没跑过预处理，不能用来说话', 1, 1, 0, 'admin', NOW()
FROM sys_dict WHERE dict_code = 'szr_train_status'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '0');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000022', id, '训练中', '1', '预处理进行中，GPU 被占用', 2, 1, 0, 'admin', NOW()
FROM sys_dict WHERE dict_code = 'szr_train_status'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '1');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000023', id, '训练成功', '2', '预处理缓存已生成，可用于说话驱动', 3, 1, 0, 'admin', NOW()
FROM sys_dict WHERE dict_code = 'szr_train_status'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '2');

INSERT INTO sys_dict_item (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1900000000000000024', id, '训练失败', '3', '看「训练结果说明」列', 4, 1, 0, 'admin', NOW()
FROM sys_dict WHERE dict_code = 'szr_train_status'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '3');
