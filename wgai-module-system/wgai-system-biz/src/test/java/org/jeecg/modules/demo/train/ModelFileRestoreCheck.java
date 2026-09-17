package org.jeecg.modules.demo.train;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.jeecg.modules.demo.easy.entity.TabEasyPic;
import org.jeecg.modules.demo.easy.mapper.TabEasyPicMapper;
import org.jeecg.modules.demo.train.entity.TabModelTry;
import org.jeecg.modules.demo.train.mapper.TabModelTryMapper;
import org.jeecg.modules.demo.train.mapper.TabModelTryOrgMapper;
import org.jeecg.modules.demo.train.mapper.TabTrainResultMapper;
import org.jeecg.modules.demo.train.service.impl.ModelFileRestoreService;
import org.jeecg.modules.demo.train.util.RestoreAnnotationXml;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Standalone regression check using in-memory mapper doubles; no production database access. */
public class ModelFileRestoreCheck {
    private static Path imageRoot;
    private static final String BOX = "<object><name>smoke</name><bndbox><xmin>307.734375</xmin>"
            + "<ymin>220</ymin><xmax>432.734375</xmax><ymax>246</ymax></bndbox></object>";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("model-restore-check-");
        checkUserSamples(root);
        Path dir = Files.createDirectories(root.resolve("smokeing"));
        imageRoot = dir;
        Path xml = Files.createDirectories(dir.resolve("xml"));
        write(dir.resolve("000001.png"), "image fixture");
        write(dir.resolve("000002.png"), "image fixture");
        write(dir.resolve("unmarked.jpg"), "image fixture");
        write(dir.resolve("broken.png"), "image fixture");
        write(dir.resolve("train_batch0.jpg"), "result fixture");
        write(dir.resolve("className.txt"), "other\nsmoke\n");
        write(Files.createDirectories(dir.resolve("weights")).resolve("best.onnx"), "weight fixture");
        write(dir.resolve("weights/best.pt"), "weight fixture");
        write(xml.resolve("000001.xml"), annotation("000001.png", BOX));
        write(xml.resolve("000002.xml"), annotation("000002.png", "<object><name/><bndbox><xmin/><ymin/><xmax/><ymax/></bndbox></object>"));
        write(xml.resolve("missing.xml"), annotation("missing.png", BOX));
        // A matching filename must never hide a broken absolute path.
        write(xml.resolve("no-path.xml"), annotation("000001.png", BOX).replace(dir.resolve("000001.png").toString(), ""));
        write(xml.resolve("broken.xml"), "<annotation>");
        JSONArray parsed = RestoreAnnotationXml.annotations(RestoreAnnotationXml.read(xml.resolve("000001.xml")), "m", "p");
        require(parsed.getJSONObject(0).getDoubleValue("xmin") == 307.734375, "decimal coordinates preserved");
        require("rect".equals(parsed.getJSONObject(0).getString("type")), "VOC defaults to rectangle");
        require("p".equals(parsed.getJSONObject(0).getString("picId")), "new picture id assigned");
        Path nested = Files.createDirectories(xml.resolve("nested"));
        Path shared = Files.createDirectories(root.resolve("shared"));
        write(shared.resolve("poly.png"), "image fixture");
        String polygon = "<object><name>poly</name><type>polygon</type><polygon><pt><x>1</x><y>1</y></pt>"
                + "<pt><x>20</x><y>1</y></pt><pt><x>20</x><y>20</y></pt></polygon></object>";
        write(nested.resolve("poly.xml"), annotation("poly.png", polygon)
                .replace(dir.resolve("poly.png").toString(), shared.resolve("poly.png").toString()));
        checkInvalid(root, annotation("a.png", BOX.replace("307.734375", "NaN")), "non-finite coordinate rejected");
        checkInvalid(root, annotation("a.png", BOX.replace("432.734375", "200")), "reversed box rejected");
        checkInvalid(root, "<!DOCTYPE annotation [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + annotation("a.png", BOX), "XXE rejected");
        Path control = root.resolve("control.xml");
        write(control, annotation("a.png", "<object><name>point</name><type>control</type><point><x>3.25</x><y>4</y></point></object>"));
        require(RestoreAnnotationXml.annotations(RestoreAnnotationXml.read(control), "m", "p")
                .getJSONObject(0).getJSONArray("points").getJSONObject(0).getDoubleValue("x") == 3.25, "control point parsed");
        write(control, annotation("a.png", ""));
        require(RestoreAnnotationXml.annotations(RestoreAnnotationXml.read(control), "m", "p").isEmpty(), "zero objects is background");

        List<Object> models = new ArrayList<>(), pictures = new ArrayList<>(), relations = new ArrayList<>(), results = new ArrayList<>();
        ModelFileRestoreService service = new ModelFileRestoreService();
        inject(service, "uploadPath", root.toString());
        inject(service, "models", mapper(TabModelTryMapper.class, models));
        inject(service, "pictures", mapper(TabEasyPicMapper.class, pictures));
        inject(service, "relations", mapper(TabModelTryOrgMapper.class, relations));
        inject(service, "trainingResults", mapper(TabTrainResultMapper.class, results));
        JSONObject request = new JSONObject();
        request.put("modelName", "恢复测试");
        request.put("modelType", "1");
        request.put("imageDir", "smokeing");
        request.put("includeUnmarked", true);
        JSONObject first = service.restore(request);
        require(first.getIntValue("inserted") == 3, "only XML pictures recovered, including absolute path outside model directory");
        require(first.getIntValue("backgrounds") == 1 && first.getIntValue("annotated") == 2, "background and annotation classification");
        require(first.getIntValue("failed") == 3, "missing image, missing absolute path and broken XML reported");
        require(relations.size() == 3 && pictures.size() == 3, "all XML picture relations restored");
        TabEasyPic background = pictures.stream().map(p -> (TabEasyPic) p)
                .filter(p -> "000002.png".equals(p.getPicName())).findFirst().get();
        require("背景图".equals(background.getMarkFeature()) && "Y".equals(background.getMarkType())
                && "".equals(background.getMarkJson()) && "".equals(background.getMarkTitle()),
                "empty name and empty box restore as confirmed background, with no target annotations");
        require(pictures.stream().map(p -> ((TabEasyPic) p).getPicUrl()).anyMatch("shared/poly.png"::equals), "absolute path used without directory guessing");
        TabEasyPic polygonPicture = pictures.stream().map(p -> (TabEasyPic) p)
                .filter(p -> "shared/poly.png".equals(p.getPicUrl())).findFirst().get();
        List<org.jeecg.modules.demo.train.util.picXml> savedAnnotations = JSON.parseArray(
                polygonPicture.getMarkJson(), org.jeecg.modules.demo.train.util.picXml.class);
        require(savedAnnotations.size() == 1 && "polygon".equals(savedAnnotations.get(0).getType())
                && savedAnnotations.get(0).getPoints().size() == 3
                && polygonPicture.getId().equals(savedAnnotations.get(0).getPicId())
                && first.getString("modelId").equals(savedAnnotations.get(0).getModelId()),
                "persisted mark_json matches original saveMake picXml schema and current picture/model ids");
        require(results.size() == 1 && first.getBooleanValue("trainingResultRestored"), "training files restored separately");
        TabModelTry model = (TabModelTry) models.get(0);
        require("other,smoke,poly".equals(model.getTxtInfo()), "original class order retained");
        require("3".equals(model.getPicNumber()) && "3".equals(model.getMakeNumber()), "model counts recomputed");
        require("smokeing/weights/best.onnx".equals(model.getModelOnnx()) && model.getOnnxIsok() == 1, "existing ONNX linked");
        require("smokeing/train_batch0.jpg".equals(JSON.toJSON(results.get(0)) instanceof JSONObject
                ? ((JSONObject) JSON.toJSON(results.get(0))).getString("trainBatch0") : null), "result image linked");
        relations.clear();
        request.put("modelId", first.getString("modelId"));
        JSONObject second = service.restore(request);
        require(second.getIntValue("inserted") == 0 && second.getIntValue("skipped") == 3, "repeated restore is idempotent");
        require(results.size() == 1 && pictures.size() == 3, "no duplicate result or picture rows");
        require(relations.size() == 3, "retained picture relations repaired");
        polygonPicture.setMarkJson(null);
        JSONObject repaired = service.restore(request);
        require(repaired.getIntValue("updated") == 1 && repaired.getIntValue("skipped") == 2,
                "missing JSON restored even when old mark_type is Y; valid background retained");
        TabEasyPic repairedPicture = pictures.stream().map(p -> (TabEasyPic) p)
                .filter(p -> polygonPicture.getId().equals(p.getId())).findFirst().get();
        require(JSON.parseArray(repairedPicture.getMarkJson()).getJSONObject(0).getJSONArray("points").size() == 3,
                "repaired polygon JSON actually passed to mapper update");
        request.put("overwrite", true);
        JSONObject third = service.restore(request);
        require(third.getIntValue("updated") == 3, "explicit overwrite restores existing annotations");
        request.put("modelId", "missing");
        try { service.restore(request); throw new AssertionError("missing model accepted"); }
        catch (IllegalArgumentException expected) { }
        request.remove("modelId");
        request.put("overwrite", false);
        JSONObject another = service.restore(request);
        String anotherId = another.getString("modelId");
        require(!first.getString("modelId").equals(anotherId) && models.size() == 2,
                "same directory can create a distinct model");
        require(another.getIntValue("inserted") == 3 && pictures.size() == 6 && relations.size() == 6 && results.size() == 2,
                "same directory restores independent pictures, relations and training results");
        for (Object row : pictures) {
            TabEasyPic picture = (TabEasyPic) row;
            if (!anotherId.equals(picture.getModelId()) || picture.getMarkJson().isEmpty()) continue;
            for (Object annotation : JSON.parseArray(picture.getMarkJson())) {
                JSONObject value = (JSONObject) annotation;
                require(anotherId.equals(value.getString("modelId")) && picture.getId().equals(value.getString("picId")),
                        "new model JSON points to its own model and picture records");
            }
        }
        request.remove("modelId");
        request.put("allowedLabels", "helmet,nohelmet");
        write(xml.resolve("000001.xml"), annotation("000001.png", BOX.replace("smoke", "helmet")
                + BOX.replace("smoke", "nohelmet") + BOX.replace("smoke", "helmet") + BOX.replace("smoke", "nohelmet")
                + BOX.replace("smoke", "helmethelmet") + BOX.replace("smoke", "nohelmet‘’")));
        JSONObject filtered = service.restore(request);
        String filteredId = filtered.getString("modelId");
        require(filtered.getIntValue("inserted") == 2 && filtered.getIntValue("backgrounds") == 1
                && filtered.getIntValue("rejectedImages") == 1, "mixed valid/invalid labels retained; all-invalid polygon skipped, not background");
        TabModelTry filteredModel = models.stream().map(m -> (TabModelTry) m).filter(m -> filteredId.equals(m.getId())).findFirst().get();
        require("helmet,nohelmet".equals(filteredModel.getTxtInfo()), "model label list strictly uses supplied order, not old className.txt");
        TabEasyPic filteredPic = pictures.stream().map(p -> (TabEasyPic) p)
                .filter(p -> filteredId.equals(p.getModelId()) && "000001.png".equals(p.getPicName())).findFirst().get();
        JSONArray filteredJson = JSON.parseArray(filteredPic.getMarkJson());
        require("helmet,nohelmet".equals(filteredPic.getMarkTitle()) && filteredJson.size() == 4,
                "multiple allowed labels and repeated targets are all retained in JSON");
        for (int i = 0; i < 4; i++) require((i % 2 == 0 ? "helmet" : "nohelmet").equals(filteredJson.getJSONObject(i).getString("name")),
                "all object names and their original order retained");
        require(filtered.getJSONArray("labelWarnings").toJSONString().contains("000001.xml")
                && filtered.getJSONArray("labelWarnings").toJSONString().contains("nohelmet‘’"), "mismatch report contains source file and exact invalid label");
        System.out.println("PASS: label whitelist, exact model labels, mixed labels, background, mismatch report and all previous restoration checks. Fixtures: " + root);
    }

    private static void checkUserSamples(Path root) throws Exception {
        double[][] hl = {{175.21484375,301.015625},{355.21484375,281.015625},
                {437.21484375,274.015625},{538.21484375,267.015625},{538.21484375,283.015625},
                {368.21484375,299.015625},{250.21484375,309.015625},{182.21484375,317.015625}};
        double[][] cx = {{234.70703125,1.015625},{235.70703125,78.015625},{221.70703125,206.015625},
                {212.70703125,373.015625},{219.70703125,547.015625},{235.70703125,691.015625},
                {519.70703125,694.015625},{519.70703125,492.015625},{505.70703125,318.015625},
                {496.70703125,219.015625},{499.70703125,123.015625},{479.70703125,9.015625}};
        String content = "<annotation><folder/><filename>004060.png</filename>"
                + "<path>/opt/upFiles/null/004060.png</path><size><width>700</width><height>700</height><depth>3</depth></size>"
                + polygon("hl", hl) + polygon("cx", cx) + "</annotation>";
        Path sample = root.resolve("user-polygons.xml");
        write(sample, content);
        org.w3c.dom.Element xml = RestoreAnnotationXml.read(sample);
        require("/opt/upFiles/null/004060.png".equals(RestoreAnnotationXml.text(xml, "path")),
                "empty folder does not rewrite absolute path containing literal null directory");
        JSONArray objects = RestoreAnnotationXml.annotations(xml, "model", "picture");
        require(objects.size() == 2, "both polygon objects retained");
        String[] names = {"hl", "cx"};
        double[][][] expected = {hl, cx};
        for (int i = 0; i < expected.length; i++) {
            JSONObject object = objects.getJSONObject(i);
            require(names[i].equals(object.getString("name")) && "polygon".equals(object.getString("type")), "polygon label and type retained");
            JSONArray points = object.getJSONArray("points");
            require(points.size() == expected[i].length, "all polygon points retained");
            for (int j = 0; j < expected[i].length; j++) {
                require(points.getJSONObject(j).getDoubleValue("x") == expected[i][j][0]
                        && points.getJSONObject(j).getDoubleValue("y") == expected[i][j][1], "point order and decimal precision retained");
            }
        }
        System.out.println("PASS: user polygons hl (8 points), cx (12 points), all coordinates and empty folder.");
    }

    private static String polygon(String name, double[][] points) {
        StringBuilder xml = new StringBuilder("<object><name>").append(name).append("</name><type>polygon</type><polygon>");
        for (double[] point : points) xml.append("<pt><x>").append(point[0]).append("</x><y>").append(point[1]).append("</y></pt>");
        return xml.append("</polygon></object>").toString();
    }

    private static String annotation(String file, String objects) {
        return "<annotation><filename>" + file + "</filename><path>" + imageRoot.resolve(file)
                + "</path><size><width>700</width><height>700</height><depth>3</depth></size>" + objects + "</annotation>";
    }

    private static void write(Path file, String content) throws Exception { Files.write(file, content.getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void checkInvalid(Path root, String content, String message) throws Exception {
        Path file = root.resolve("invalid.xml"); write(file, content);
        try { RestoreAnnotationXml.annotations(RestoreAnnotationXml.read(file), "m", "p"); }
        catch (Exception expected) { return; }
        throw new AssertionError(message);
    }

    private static Object mapper(Class<?> type, List<Object> rows) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName();
            if ("insert".equals(name)) {
                if (args[0].getClass().getMethod("getId").invoke(args[0]) == null)
                    args[0].getClass().getMethod("setId", String.class).invoke(args[0], UUID.randomUUID().toString());
                rows.add(args[0]); return 1;
            }
            if ("updateById".equals(name)) {
                String id = (String) args[0].getClass().getMethod("getId").invoke(args[0]);
                for (int i = 0; i < rows.size(); i++) {
                    if (id.equals(rows.get(i).getClass().getMethod("getId").invoke(rows.get(i)))) {
                        rows.set(i, args[0]); return 1;
                    }
                }
                return 0;
            }
            if ("selectById".equals(name)) {
                for (Object row : rows) if (args[0].equals(row.getClass().getMethod("getId").invoke(row))) return row;
                return null;
            }
            if ("selectCount".equals(name) || "selectList".equals(name)) {
                QueryWrapper<?> query = (QueryWrapper<?>) args[0];
                String sql = query.getSqlSegment();
                Object value = query.getParamNameValuePairs().values().iterator().next();
                String getter = sql.contains("pic_name") ? "getPicName" : "getModelId";
                List<Object> selected = new ArrayList<>();
                for (Object row : rows) if (Objects.equals(value, row.getClass().getMethod(getter).invoke(row))) selected.add(row);
                return "selectCount".equals(name) ? Long.valueOf(selected.size()) : selected;
            }
            throw new UnsupportedOperationException(name);
        });
    }
}
