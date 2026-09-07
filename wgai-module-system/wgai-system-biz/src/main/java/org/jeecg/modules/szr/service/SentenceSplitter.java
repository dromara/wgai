package org.jeecg.modules.szr.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 中文文本切句。
 *
 * <p>切句是数字人首帧延迟的第一杠杆：整段 16 秒一次性合成再播，首帧要等十几秒；
 * 切成 5 句之后，首句只要 1~2 秒就能开口，后面几句在播放期间陆续生成。
 *
 * <p>切出来的每一句同时也是「播到哪了」回调的锚点。
 *
 * @author wggg
 */
public class SentenceSplitter {

    /** 句末标点，在这些字符之后断句（标点保留在上一句）。 */
    private static final String END_MARKS = "。！？!?；;\n";

    /** 次级停顿标点，只有当句子超长时才在这里断。 */
    private static final String SOFT_MARKS = "，,、：:";

    private SentenceSplitter() {
    }

    /**
     * @param text     原文
     * @param minChars 短句合并阈值：不足这么多字就并进下一句，避免"好的。"这种半秒片段
     * @param maxChars 硬切阈值：超过就在次级标点处断，再不行就硬切
     */
    public static List<String> split(String text, int minChars, int maxChars) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        // 1. 先按句末标点粗切
        List<String> rough = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (char c : text.toCharArray()) {
            buf.append(c);
            if (END_MARKS.indexOf(c) >= 0) {
                String s = buf.toString().trim();
                if (!s.isEmpty()) {
                    rough.add(s);
                }
                buf.setLength(0);
            }
        }
        if (buf.toString().trim().length() > 0) {
            rough.add(buf.toString().trim());
        }

        // 2. 过长的再按次级标点拆
        List<String> sized = new ArrayList<>();
        for (String s : rough) {
            sized.addAll(splitLong(s, maxChars));
        }

        // 3. 过短的向后合并
        //    单独合成"好的。"只有半秒音频，切换开销比内容还多，且回调过于碎
        //
        //    ⚠ 同时丢掉没有可发音内容的片段（纯标点、纯空白）。
        //      这种片段送进 sherpa 会让 lexicon 产出零音素，
        //      空张量喂进 Conv 层直接崩掉整个 JVM，不是 Java 异常。
        StringBuilder merge = new StringBuilder();
        for (String s : sized) {
            if (!hasSpeakable(s)) {
                continue;
            }
            merge.append(s);
            if (merge.length() >= minChars) {
                result.add(merge.toString());
                merge.setLength(0);
            }
        }
        if (merge.length() > 0) {
            if (result.isEmpty()) {
                result.add(merge.toString());
            } else {
                // 末尾残句并进上一句，别单独留一个碎片
                result.set(result.size() - 1, result.get(result.size() - 1) + merge);
            }
        }
        return result;
    }

    private static List<String> splitLong(String s, int maxChars) {
        List<String> out = new ArrayList<>();
        if (s.length() <= maxChars) {
            out.add(s);
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            buf.append(c);
            boolean soft = SOFT_MARKS.indexOf(c) >= 0 && buf.length() >= maxChars / 2;
            if (soft || buf.length() >= maxChars) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        return out;
    }

    /** 有没有真正能发音的内容（中日韩文字 / 字母 / 数字）。 */
    private static boolean hasSpeakable(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
