package org.jeecg.modules.demo.train.util;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Path;

/** Reads existing VOC files without modifying the source files. */
public final class RestoreAnnotationXml {
    private RestoreAnnotationXml() { }

    public static Element read(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        if (!"annotation".equals(root.getTagName())) throw new IllegalArgumentException("根节点必须是 annotation");
        return root;
    }

    public static String text(Element parent, String tag) {
        Element child = child(parent, tag);
        return child == null ? "" : child.getTextContent().trim();
    }

    private static Element child(Element parent, String tag) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(n.getNodeName())) return (Element) n;
        }
        return null;
    }

    private static double number(Element parent, String tag) {
        double value = Double.parseDouble(text(parent, tag));
        if (!Double.isFinite(value)) throw new IllegalArgumentException("坐标或尺寸不是有限数值: " + tag);
        return value;
    }

    public static JSONArray annotations(Element root, String modelId, String picId) {
        Element size = child(root, "size");
        double width = number(size, "width"), height = number(size, "height");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("图片尺寸必须大于零");
        JSONArray result = new JSONArray();
        NodeList objects = root.getElementsByTagName("object");
        for (int i = 0; i < objects.getLength(); i++) {
            Element object = (Element) objects.item(i);
            if (child(object, "name") == null) throw new IllegalArgumentException("object 缺少 name");
            String name = text(object, "name");
            // Existing background XML uses an empty-name object with empty box values.
            if (name.isEmpty()) continue;
            if (name.contains(",") || name.contains("\n") || name.contains("\r"))
                throw new IllegalArgumentException("标签名称不能含逗号或换行");
            String type = text(object, "type");
            if (type.isEmpty()) type = "rect";
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("type", type);
            item.put("modelId", modelId);
            item.put("picId", picId);
            item.put("ywidth", width);
            item.put("yheight", height);
            item.put("canvaswidth", width);
            item.put("canvasheight", height);
            if ("rect".equals(type)) {
                Element box = child(object, "bndbox");
                double xmin = number(box, "xmin"), ymin = number(box, "ymin");
                double xmax = number(box, "xmax"), ymax = number(box, "ymax");
                if (xmin < 0 || ymin < 0 || xmax > width || ymax > height || xmax <= xmin || ymax <= ymin)
                    throw new IllegalArgumentException("矩形坐标无效或超出图片范围");
                item.put("xmin", String.valueOf(xmin));
                item.put("ymin", String.valueOf(ymin));
                item.put("xmax", String.valueOf(xmax));
                item.put("ymax", String.valueOf(ymax));
            } else if ("polygon".equals(type) || "control".equals(type)) {
                JSONArray points = new JSONArray();
                if ("control".equals(type)) {
                    points.add(point(child(object, "point"), width, height));
                } else {
                    Element polygon = child(object, "polygon");
                    if (polygon == null) throw new IllegalArgumentException("缺少 polygon");
                    NodeList pts = polygon.getElementsByTagName("pt");
                    if (pts.getLength() < 3) throw new IllegalArgumentException("多边形至少需要三个点");
                    for (int j = 0; j < pts.getLength(); j++) points.add(point((Element) pts.item(j), width, height));
                }
                item.put("points", points);
            } else {
                throw new IllegalArgumentException("不支持的标注类型: " + type);
            }
            result.add(item);
        }
        return result;
    }

    private static JSONObject point(Element element, double width, double height) {
        double x = number(element, "x"), y = number(element, "y");
        if (x < 0 || y < 0 || x > width || y > height) throw new IllegalArgumentException("点坐标超出图片范围");
        JSONObject point = new JSONObject();
        point.put("x", x);
        point.put("y", y);
        return point;
    }
}
