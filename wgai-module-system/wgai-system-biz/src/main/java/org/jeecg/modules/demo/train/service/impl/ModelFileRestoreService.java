package org.jeecg.modules.demo.train.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.apache.commons.lang3.StringUtils;
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.easy.mapper.TabEasyPicMapper;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecg.modules.demo.train.entity.TabModelTryOrg;
import org.jeecg.modules.demo.train.entity.TabTrainResult;
import org.jeecg.modules.demo.train.mapper.TabModelTryMapper;
import org.jeecg.modules.demo.train.mapper.TabModelTryOrgMapper;
import org.jeecg.modules.demo.train.mapper.TabTrainResultMapper;
import org.jeecg.modules.demo.train.util.RestoreAnnotationXml;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class ModelFileRestoreService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelFileRestoreService.class);
    @Autowired private TabModelTryMapper models;
    @Autowired private TabEasyPicMapper pictures;
    @Autowired private TabModelTryOrgMapper relations;
    @Autowired private TabTrainResultMapper trainingResults;
    @Value("${jeecg.path.upload}") private String uploadPath;

    /** Database changes roll back together; source images and XML are never rewritten. */
    @Transactional(rollbackFor = Exception.class)
    public synchronized JSONObject restore(JSONObject request) throws Exception {
        long started = System.currentTimeMillis();
        Set<String> allowedLabels = new LinkedHashSet<>();
        String allowedText = StringUtils.trimToEmpty(request.getString("allowedLabels"));
        if (!allowedText.isEmpty()) {
            for (String label : allowedText.replace('，', ',').split(",", -1)) {
                String value = label.trim();
                if (value.isEmpty() || value.contains("\n") || value.contains("\r"))
                    throw new IllegalArgumentException("允许标签请用逗号分隔，不能包含空项或换行");
                allowedLabels.add(value);
            }
            log.info("[模型恢复][标签限制] 仅恢复指定标签={}，按此顺序保存模型标签", allowedLabels);
        }
        log.info("[模型恢复][开始] 模型ID={}，模型名称={}，模型目录={}，XML目录={}，覆盖已有标注={}",
                request.getString("modelId"), request.getString("modelName"), request.getString("imageDir"),
                request.getString("xmlDir"), request.getBooleanValue("overwrite"));
        Path upload = Paths.get(uploadPath).toRealPath();
        Path imageDir = directory(upload, request.getString("imageDir"));
        String xmlValue = request.getString("xmlDir");
        Path xmlDir = StringUtils.isBlank(xmlValue)
                ? (Files.isDirectory(imageDir.resolve("xml")) ? imageDir.resolve("xml").toRealPath() : imageDir)
                : directory(upload, xmlValue);
        String picFolder = relative(upload, imageDir);
        if (picFolder.isEmpty()) throw new IllegalArgumentException("图片目录必须是上传目录下的模型子目录");
        String modelId = StringUtils.trimToEmpty(request.getString("modelId"));
        boolean create = modelId.isEmpty();
        TabModelTry model;
        if (create) {
            String name = StringUtils.trimToEmpty(request.getString("modelName"));
            String type = StringUtils.trimToEmpty(request.getString("modelType"));
            if (name.isEmpty() || type.isEmpty()) throw new IllegalArgumentException("请填写模型名称和模型类型");
            // Different models may restore from the same source directory.
            // Picture records and annotation JSON are scoped to the new model ID.
            modelId = UUID.randomUUID().toString().replace("-", "");
            model = new TabModelTry().setId(modelId).setModelName(name).setModelType(type)
                    .setPicName(picFolder).setIsInsert("N").setUpdatePic("N").setOnnxIsok(0).setRunState(0);
        } else {
            model = models.selectById(modelId);
            if (model == null) throw new IllegalArgumentException("目标模型不存在，请使用顶部恢复按钮重建模型");
            if (StringUtils.isNotBlank(model.getPicName()) &&
                    !directory(upload, model.getPicName()).equals(imageDir))
                throw new IllegalArgumentException("图片目录须与目标模型的图片简称目录一致");
        }

        log.info("[模型恢复][{}][扫描开始] XML目录={}，递归扫描子目录", modelId, xmlDir);
        List<Path> xmlFiles;
        final long[] scanProgress = {0, System.currentTimeMillis()};
        try (Stream<Path> paths = Files.walk(xmlDir)) {
            xmlFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                    .peek(p -> {
                        scanProgress[0]++;
                        if (scanProgress[0] % 1000 == 0 || System.currentTimeMillis() - scanProgress[1] >= 3000) {
                            log.info("[模型恢复][扫描中] XML目录={}，已找到={}，当前文件={}", xmlDir, scanProgress[0], p);
                            scanProgress[1] = System.currentTimeMillis();
                        }
                    })
                    .sorted().collect(Collectors.toList());
        }
        log.info("[模型恢复][{}][扫描完成] XML总数={}，开始读取已有图片和关联", modelId, xmlFiles.size());
        Map<String, TabEasyPic> existing = new HashMap<>();
        for (TabEasyPic pic : pictures.selectList(new QueryWrapper<TabEasyPic>().eq("model_id", modelId))) {
            existing.put(normalize(pic.getPicUrl()), pic);
        }
        Set<String> linked = relations.selectList(new QueryWrapper<TabModelTryOrg>().eq("model_id", modelId))
                .stream().map(TabModelTryOrg::getPicId).collect(Collectors.toSet());
        Set<String> labels = new LinkedHashSet<>();
        labels.addAll(allowedLabels);
        if (allowedLabels.isEmpty() && StringUtils.isNotBlank(model.getTxtInfo())) labels.addAll(Arrays.asList(model.getTxtInfo().split(",")));
        String classFile = artifact(upload, imageDir, "className.txt");
        if (labels.isEmpty() && classFile != null) {
            // Keep the original class order used by existing weights.
            for (String line : Files.readAllLines(upload.resolve(classFile), java.nio.charset.StandardCharsets.UTF_8)) {
                String label = line.replace("\uFEFF", "").trim();
                if (!label.isEmpty()) labels.add(label);
            }
            model.setTxtTitle(classFile);
        }
        Set<String> seen = new HashSet<>();
        List<TabEasyPic> restored = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> labelWarnings = new ArrayList<>();
        int rejectedObjects = 0, rejectedImages = 0;
        int failed = 0, skipped = 0, backgrounds = 0, annotated = 0, inserted = 0, updated = 0;
        int processed = 0;
        long lastProgress = System.currentTimeMillis();
        log.info("[模型恢复][{}][解析开始] 已有图片={}，已有关联={}，XML总数={}", modelId, existing.size(), linked.size(), xmlFiles.size());
        for (Path xml : xmlFiles) {
            if (processed == 0 || processed % 50 == 0) {
                log.info("[模型恢复][{}][读取XML] 第{}/{}个，文件={}", modelId, processed + 1, xmlFiles.size(), xml);
            }
            try {
                Path realXml = xml.toRealPath();
                if (!realXml.startsWith(upload)) throw new IllegalArgumentException("XML 不在上传目录内");
                Element root = RestoreAnnotationXml.read(realXml);
                Set<String> rejected = new LinkedHashSet<>();
                // Filter object names before parsing coordinates, so a rejected object cannot break valid targets.
                org.w3c.dom.NodeList objects = root.getElementsByTagName("object");
                for (int i = objects.getLength() - 1; i >= 0 && !allowedLabels.isEmpty(); i--) {
                    Element object = (Element) objects.item(i);
                    String name = RestoreAnnotationXml.text(object, "name");
                    if (!name.isEmpty() && !allowedLabels.contains(name)) {
                        rejected.add(name);
                        rejectedObjects++;
                        object.getParentNode().removeChild(object);
                    }
                }
                if (!rejected.isEmpty()) {
                    String warning = realXml + "：非允许标签=" + rejected + "，图片=" + RestoreAnnotationXml.text(root, "path");
                    labelWarnings.add(warning);
                    log.warn("[模型恢复][{}][标签不匹配] {}", modelId, warning);
                }
                Path image = resolveImage(root, upload);
                String url = relative(upload, image);
                TabEasyPic old = existing.get(url);
                String id = old == null ? UUID.randomUUID().toString().replace("-", "") : old.getId();
                JSONArray boxes = RestoreAnnotationXml.annotations(root, modelId, id);
                if (boxes.isEmpty() && !rejected.isEmpty()) {
                    // Non-empty rejected names must never produce a false background sample.
                    rejectedImages++;
                    skipped++;
                    continue;
                }
                if (!seen.add(url)) throw new IllegalArgumentException("多个 XML 指向同一图片，请保留一份后重试");
                if (allowedLabels.isEmpty() && hasSavedAnnotationData(old) && !request.getBooleanValue("overwrite")) {
                    // Still repair the model-picture relation for retained annotations.
                    restored.add(old);
                    skipped++;
                    continue;
                }
                Set<String> titles = new LinkedHashSet<>();
                for (int i = 0; i < boxes.size(); i++) titles.add(boxes.getJSONObject(i).getString("name"));
                labels.addAll(titles);
                TabEasyPic pic = new TabEasyPic().setId(id).setModelId(modelId).setPicType("1")
                        .setPicName(image.getFileName().toString()).setPicUrl(url)
                        .setRemake(relative(upload, image.getParent())).setMarkType("Y")
                        .setMarkXml(relative(upload, realXml)).setMarkTitle(String.join(",", titles))
                        .setMarkFeature(boxes.isEmpty() ? "背景图" : "标注图")
                        .setMarkJson(boxes.isEmpty() ? "" : boxes.toJSONString());
                restored.add(pic);
                if (old == null) inserted++; else updated++;
                if (boxes.isEmpty()) backgrounds++; else annotated++;
            } catch (Exception e) {
                failed++;
                log.warn("[模型恢复][{}][XML失败] 文件={}，原因={}", modelId, xml, e.getMessage());
                if (errors.size() < 50) errors.add(xmlDir.relativize(xml) + ": " + e.getMessage());
            } finally {
                processed++;
                if (processed % 50 == 0 || processed == xmlFiles.size() || System.currentTimeMillis() - lastProgress >= 3000) {
                    log.info("[模型恢复][{}][解析进度] {}/{} ({}%)，待新增={}，待更新={}，标注图={}，背景图={}，保留={}，失败={}，当前文件={}，耗时={}ms",
                            modelId, processed, xmlFiles.size(), processed * 100L / xmlFiles.size(), inserted, updated,
                            annotated, backgrounds, skipped, failed, xml, System.currentTimeMillis() - started);
                    lastProgress = System.currentTimeMillis();
                }
            }
        }
        if (restored.isEmpty() && labelWarnings.isEmpty()) throw new IllegalArgumentException("没有可恢复的图片或有效 XML。" + String.join("；", errors));
        log.info("[模型恢复][{}][数据库处理开始] 图片记录={}，新增模型={}", modelId, restored.size(), create);
        if (create) check(models.insert(model));
        int saved = 0;
        lastProgress = System.currentTimeMillis();
        for (TabEasyPic pic : restored) {
            TabEasyPic old = existing.get(normalize(pic.getPicUrl()));
            if (old == null) check(pictures.insert(pic));
            else if (pic != old) check(pictures.updateById(pic));
            if (linked.add(pic.getId())) check(relations.insert(new TabModelTryOrg().setModelId(modelId).setPicId(pic.getId())));
            saved++;
            if (saved == 1 || saved % 50 == 0 || saved == restored.size() || System.currentTimeMillis() - lastProgress >= 3000) {
                log.info("[模型恢复][{}][数据库处理进度] {}/{} ({}%)，当前图片={}，事务尚未提交，耗时={}ms",
                        modelId, saved, restored.size(), saved * 100L / restored.size(), pic.getPicUrl(), System.currentTimeMillis() - started);
                lastProgress = System.currentTimeMillis();
            }
        }
        log.info("[模型恢复][{}][汇总开始] 统计模型图片、补齐关联和标签", modelId);
        List<TabEasyPic> all = pictures.selectList(new QueryWrapper<TabEasyPic>().eq("model_id", modelId));
        long marked = all.stream().filter(p -> "Y".equals(p.getMarkType())).count();
        for (TabEasyPic pic : all) {
            if (linked.add(pic.getId())) check(relations.insert(new TabModelTryOrg().setModelId(modelId).setPicId(pic.getId())));
            if (allowedLabels.isEmpty() && StringUtils.isNotBlank(pic.getMarkTitle())) labels.addAll(Arrays.asList(pic.getMarkTitle().split(",")));
        }
        model.setPicName(picFolder).setPicNumber(String.valueOf(all.size())).setMakeNumber(String.valueOf(marked))
                .setTxtInfo(String.join(",", labels));
        log.info("[模型恢复][{}][训练结果关联] 目录={}", modelId, imageDir);
        boolean resultRestored = restoreTrainingFiles(upload, imageDir, model);
        check(models.updateById(model));
        JSONObject result = new JSONObject();
        result.put("modelId", modelId);
        result.put("total", xmlFiles.size());
        result.put("inserted", inserted);
        result.put("updated", updated);
        result.put("annotated", annotated);
        result.put("backgrounds", backgrounds);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("labels", labels);
        result.put("labelWarnings", labelWarnings);
        result.put("rejectedObjects", rejectedObjects);
        result.put("rejectedImages", rejectedImages);
        log.info("[模型恢复][{}][标签核对] 不匹配文件={}，忽略目标={}，整图跳过={}", modelId, labelWarnings.size(), rejectedObjects, rejectedImages);
        result.put("modelOnnx", model.getModelOnnx());
        result.put("trainingResultRestored", resultRestored);
        log.info("[模型恢复][{}][待提交] 新增={}，更新={}，保留={}，失败={}，模型图片总数={}，标注总数={}，ONNX={}，训练结果已关联={}，耗时={}ms",
                modelId, inserted, updated, skipped, failed, all.size(), marked, model.getModelOnnx(), resultRestored,
                System.currentTimeMillis() - started);
        return result;
    }

    private boolean restoreTrainingFiles(Path upload, Path dir, TabModelTry model) throws IOException {
        String onnx = artifact(upload, dir, "weights/best.onnx");
        String pt = artifact(upload, dir, "weights/best.pt");
        if (onnx != null) model.setModelOnnx(onnx).setOnnxIsok(1);
        if (pt != null) model.setModelPt(pt);
        TabTrainResult files = new TabTrainResult().setModelId(model.getId())
                .setOnnxWeight(onnx).setBestPt(pt).setLastPt(artifact(upload, dir, "weights/last.pt"))
                .setLabels(artifact(upload, dir, "labels.jpg"))
                .setLabelsCorrelogram(artifact(upload, dir, "labels_correlogram.jpg", "confusion_matrix_normalized.png"))
                .setTrainBatch0(artifact(upload, dir, "train_batch0.jpg"))
                .setTrainBatch1(artifact(upload, dir, "train_batch1.jpg"))
                .setTrainBatch2(artifact(upload, dir, "train_batch2.jpg"))
                .setValBatch0Lables(artifact(upload, dir, "val_batch0_labels.jpg"))
                .setValBatch0Pred(artifact(upload, dir, "val_batch0_pred.jpg"))
                .setConfusionMatrix(artifact(upload, dir, "confusion_matrix.png"))
                .setF1Curve(artifact(upload, dir, "F1_curve.png", "BoxF1_curve.png"))
                .setPpCurve(artifact(upload, dir, "P_curve.png", "BoxP_curve.png"))
                .setPrCurve(artifact(upload, dir, "PR_curve.png", "BoxPR_curve.png"))
                .setRrCurve(artifact(upload, dir, "R_curve.png", "BoxR_curve.png"))
                .setResults(artifact(upload, dir, "results.png"))
                .setHypYaml(artifact(upload, dir, "hyp.yaml", "args.yaml"))
                .setOptYaml(artifact(upload, dir, "opt.yaml", "args.yaml"));
        JSONObject fields = (JSONObject) com.alibaba.fastjson.JSON.toJSON(files);
        if (fields.entrySet().stream().noneMatch(e -> !"modelId".equals(e.getKey()) && e.getValue() != null)) return false;
        List<TabTrainResult> old = trainingResults.selectList(new QueryWrapper<TabTrainResult>()
                .eq("model_id", model.getId()).orderByDesc("create_time"));
        // Fill only missing file references, leaving historical metrics and times intact.
        if (old.isEmpty()) {
            check(trainingResults.insert(files));
        } else {
            TabTrainResult latest = old.get(0);
            JSONObject merged = (JSONObject) com.alibaba.fastjson.JSON.toJSON(latest);
            fields.forEach((key, value) -> {
                if (value != null && StringUtils.isBlank(merged.getString(key))) merged.put(key, value);
            });
            check(trainingResults.updateById(merged.toJavaObject(TabTrainResult.class)));
        }
        return true;
    }

    private static String artifact(Path upload, Path dir, String... names) throws IOException {
        for (String name : names) {
            Path file = dir.resolve(name);
            if (Files.isRegularFile(file) && Files.isReadable(file)) {
                Path real = file.toRealPath();
                if (real.startsWith(dir)) return relative(upload, real);
            }
        }
        return null;
    }

    private static void check(int affected) {
        if (affected != 1) throw new IllegalStateException("恢复写入失败，数据已回滚");
    }

    private static boolean hasSavedAnnotationData(TabEasyPic picture) {
        if (picture == null || !"Y".equals(picture.getMarkType())) return false;
        // Background images intentionally have no target JSON, just like saveMake().
        if (StringUtils.isBlank(picture.getMarkJson())) return "背景图".equals(picture.getMarkFeature());
        try {
            JSONArray annotations = JSONArray.parseArray(picture.getMarkJson());
            return annotations != null && !annotations.isEmpty();
        } catch (RuntimeException invalidJson) {
            return false;
        }
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String relative(Path root, Path path) {
        return normalize(root.relativize(path).toString());
    }

    private static Path directory(Path upload, String value) throws IOException {
        if (StringUtils.isBlank(value)) throw new IllegalArgumentException("请填写 XML 目录和图片目录");
        Path path = upload.resolve(value.trim()).toRealPath();
        if (!path.startsWith(upload) || !Files.isDirectory(path) || !Files.isReadable(path))
            throw new IllegalArgumentException("目录须位于服务器上传目录下且可读取: " + value);
        return path;
    }

    private static Path resolveImage(Element root, Path upload) throws IOException {
        String value = RestoreAnnotationXml.text(root, "path");
        if (value.isEmpty()) throw new IllegalArgumentException("XML 缺少图片绝对路径 path");
        Path image = Paths.get(value);
        if (!image.isAbsolute()) throw new IllegalArgumentException("XML path 必须是服务器图片绝对路径: " + value);
        if (!Files.isRegularFile(image) || !Files.isReadable(image))
            throw new IllegalArgumentException("XML path 指向的图片不存在或不可读取: " + value);
        Path real = image.toRealPath();
        if (!real.startsWith(upload))
            throw new IllegalArgumentException("XML 图片路径须位于服务器上传目录内: " + value);
        return real;
    }
}
