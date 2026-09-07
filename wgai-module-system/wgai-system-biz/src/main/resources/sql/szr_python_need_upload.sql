-- 数字人 Python 脚本表：音频是否需要上传
--
-- 1 = 上传：Java 把 wav 以 multipart 推给驱动服务 /speak_upload
--           适用于 Java 和 Python 不在同一台机器
-- 0 = 不上传：只把文件绝对路径传给 /speak，驱动服务自己读
--           前提是两边在同一台服务器或共享挂载，否则驱动服务找不到文件
--
-- 默认给 1（上传），因为跨机是更保险的假设；同机部署时手动改成 0 更快。
ALTER TABLE tab_szr_python ADD COLUMN need_upload int(1) DEFAULT 1 COMMENT '音频是否上传:1=上传文件 0=同机直接给路径';

-- 已有的驱动服务记录默认走上传
UPDATE tab_szr_python SET need_upload = 1 WHERE need_upload IS NULL;
