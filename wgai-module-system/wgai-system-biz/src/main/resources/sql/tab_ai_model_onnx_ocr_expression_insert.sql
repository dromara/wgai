-- ONNX 表情识别 / Paddle OCR 模型配置（MySQL）。
-- 文件路径相对 upload.path；请将模型文件放到：<upload.path>/tabAimodel/...
-- 已存在相同 id 时不会重复插入。文件名请按实际下载结果调整。

-- 识别内容字典：10=OCR识别，40=表情识别
INSERT INTO sys_dict_item
    (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1890000000000000010', id, 'OCR识别', '10', 'Paddle OCR文字识别', 10, 1, 0, 'admin', NOW()
FROM sys_dict
WHERE dict_code = 'dify_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '10');

INSERT INTO sys_dict_item
    (id, dict_id, item_text, item_value, description, sort_order, status, del_flag, create_by, create_time)
SELECT '1890000000000000040', id, '表情识别', '40', 'FER+ ONNX表情识别', 40, 1, 0, 'admin', NOW()
FROM sys_dict
WHERE dict_code = 'dify_type'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_item WHERE dict_id = sys_dict.id AND item_value = '40');

-- 表情模型：下载 emotion-ferplus-8.onnx 后放到 tabAimodel/emotion-ferplus-8.onnx。
-- ai_weights=表情ONNX；model_dify=40；spare_one='20' 表示进入 TabAiHistoryServiceImpl 的 ONNX 分支。
INSERT INTO tab_ai_model
    (id, create_by, create_time, ai_name, ai_weights, spare_one, model_dify, model_dify_type,
     model_jm_type, threshold, nms_threshold, del_flag)
SELECT '1890000000000000400', 'admin', NOW(), 'FER+表情识别(ONNX)',
       'tabAimodel/emotion-ferplus-8.onnx', '20', 40, 20, 0, NULL, NULL, 0
WHERE NOT EXISTS (SELECT 1 FROM tab_ai_model WHERE id = '1890000000000000400');

-- OCR模型字段：ai_weights=检测模型，ai_name_name=文字识别模型，ai_config=中文字符字典。
-- 下列值已按当前上传后的实际文件名填写。
INSERT INTO tab_ai_model
    (id, create_by, create_time, ai_name, ai_weights, ai_config, ai_name_name, spare_one, model_dify,
     model_dify_type, model_jm_type, threshold, nms_threshold, del_flag)
SELECT '1890000000000000100', 'admin', NOW(), 'Paddle OCR文字识别(ONNX)',
       'ch_ppocr_det.onnx',
       'temp/ppocr_keys_v1_1788312933128.txt',
       'temp/ch_ppocr_rec_1788312941735.onnx',
       '20', 10, 20, NULL, NULL, 0
WHERE NOT EXISTS (SELECT 1 FROM tab_ai_model WHERE id = '1890000000000000100');

-- 若之前已执行过旧版SQL，请执行本语句覆盖为当前字段映射。
UPDATE tab_ai_model
SET ai_weights = 'ch_ppocr_det.onnx',
    ai_name_name = 'temp/ch_ppocr_rec_1788312941735.onnx',
    ai_config = 'temp/ppocr_keys_v1_1788312933128.txt',
    end_weights = NULL,
    spare_one = '20',
    model_dify = 10,
    model_dify_type = 20
WHERE id = '1890000000000000100';
