-- MySQL: restore the five configuration rows consumed by startPyV11().
-- Server training root confirmed by the user (the directory containing config.yaml).
-- This script restores configuration only and does not start training.
SET @v11_root = '/home/yolov11/ultralytics/ultralytics';
SET @v11_train_command = 'yolo task=detect mode=train model=yolo11n.pt data=config.yaml epochs=300 imgsz=640 batch=16 patience=0 save_period=10 cache=True workers=8 device=0 optimizer=AdamW lr0=0.003 lrf=0.1 momentum=0.937 weight_decay=0.0005 warmup_epochs=3 warmup_momentum=0.8 warmup_bias_lr=0.1 box=7.5 cls=0.5 dfl=1.5 hsv_h=0.015 hsv_s=0.7 hsv_v=0.4 degrees=10 translate=0.1 scale=0.9 shear=2.0 perspective=0.0005 flipud=0.5 fliplr=0.5 mosaic=1.0 mixup=0.15 copy_paste=0.3 close_mosaic=10 amp=True multi_scale=True cos_lr=True';
-- Only export arguments belong here, not a complete yolo export command.
-- Correct argument spelling is opset (not optset).
SET @v11_export_arguments = 'opset=12 simplify=True imgsz=640 dynamic=False';

-- The old repository schema has spare_one VARCHAR(32), too short for this command.
-- Expand only short columns; leave existing larger columns unchanged.
SET @v11_column_length = (
  SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_train_python' AND COLUMN_NAME = 'spare_one'
);
SET @v11_ddl = IF(@v11_column_length < CHAR_LENGTH(@v11_train_command),
  'ALTER TABLE tab_train_python MODIFY COLUMN spare_one TEXT NULL COMMENT ''训练命令''',
  'SELECT ''spare_one length unchanged'' AS message');
PREPARE v11_schema_statement FROM @v11_ddl;
EXECUTE v11_schema_statement;
DEALLOCATE PREPARE v11_schema_statement;

-- The complete export arguments also exceed the old spare_two VARCHAR(32).
SET @v11_export_column_length = (
  SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tab_train_python' AND COLUMN_NAME = 'spare_two'
);
SET @v11_export_ddl = IF(@v11_export_column_length < CHAR_LENGTH(@v11_export_arguments),
  'ALTER TABLE tab_train_python MODIFY COLUMN spare_two TEXT NULL COMMENT ''导出附加参数''',
  'SELECT ''spare_two length unchanged'' AS message');
PREPARE v11_export_schema_statement FROM @v11_export_ddl;
EXECUTE v11_export_schema_statement;
DEALLOCATE PREPARE v11_export_schema_statement;

SET @v11_root = TRIM(TRAILING '/' FROM TRIM(@v11_root));
START TRANSACTION;
-- Preserve existing rows. A repeated execution inserts only missing steps.
INSERT INTO tab_train_python
  (id, create_time, py_name, py_url, py_path, py_remake, spare_one, spare_two, py_type, py_sort)
SELECT REPLACE(UUID(), '-', ''), NOW(), steps.py_name, NULL,
       CASE WHEN steps.py_sort <= 3 THEN CONCAT(@v11_root, '/wgdata') ELSE @v11_root END,
       steps.py_remake,
       CASE WHEN steps.py_sort = 5 THEN @v11_train_command ELSE NULL END,
       CASE WHEN steps.py_sort = 5 THEN @v11_export_arguments ELSE NULL END,
       '11', steps.py_sort
FROM (
  SELECT 1 AS py_sort, 'V11创建目录并复制图片和XML' AS py_name, '生成wgdata/data下的训练素材；训练时会清空该临时数据目录' AS py_remake
  UNION ALL SELECT 2, 'V11划分训练验证测试集', '生成wgdata/data/ImageSets下的数据集列表'
  UNION ALL SELECT 3, 'V11转换XML标注', '生成wgdata/data/labels及train.txt、val.txt、test.txt'
  UNION ALL SELECT 4, 'V11生成数据集配置', '在训练根目录生成config.yaml，指向wgdata/data'
  UNION ALL SELECT 5, 'V11训练并导出ONNX', 'spare_one为完整训练命令；spare_two仅为导出附加参数'
) steps
WHERE @v11_root IS NOT NULL AND @v11_root <> '' AND LEFT(@v11_root, 1) = '/'
  AND CHAR_LENGTH(CONCAT(@v11_root, '/wgdata')) <= 320
  AND NOT EXISTS (
    SELECT 1 FROM tab_train_python existing
    WHERE existing.py_type = '11' AND existing.py_sort = steps.py_sort
  );
-- Also apply the confirmed export setting if the earlier recovery SQL was already run.
UPDATE tab_train_python
SET spare_two = @v11_export_arguments
WHERE py_type = '11' AND py_sort = 5 AND py_path = @v11_root;
COMMIT;

SELECT id, py_name, py_type, py_sort, py_path, spare_one, spare_two
FROM tab_train_python WHERE py_type = '11' ORDER BY py_sort;
SELECT py_sort, COUNT(*) AS row_count FROM tab_train_python
WHERE py_type = '11' GROUP BY py_sort HAVING COUNT(*) > 1;

-- Code executes these two commands in separate bash processes:
-- cd <root> && <spare_one>
-- cd <root> && yolo export model=<parsed training result>/weights/best.pt format=onnx <spare_two>
-- A virtual environment activated only inside spare_one does not carry over to export.
