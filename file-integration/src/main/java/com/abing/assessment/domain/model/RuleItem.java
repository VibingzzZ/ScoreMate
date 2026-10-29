package com.abing.assessment.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 一条「匹配 → 分值」规则，也是整套 schema 的最小单元：职务表、荣誉表、二维矩阵、扣分项
 * 都表达成同一形状（只做单层 items，多步查表遇到真细则再说）。
 *
 * <p>{@code match} 的键自由，引擎只认这几个（其余忽略并记入问题清单）：
 * <table border="1">
 *   <tr><th>键</th><th>取自材料哪个字段</th><th>匹配方式</th></tr>
 *   <tr><td>{@code doc_type}</td><td>docType</td><td>相等（或命中列表之一）</td></tr>
 *   <tr><td>{@code level}</td><td>levelHint</td><td>相等（或命中列表之一）</td></tr>
 *   <tr><td>{@code titles}</td><td>title + achievement</td><td>任一关键词是子串</td></tr>
 *   <tr><td>{@code achievement}</td><td>achievement</td><td>任一关键词是子串</td></tr>
 *   <tr><td>{@code issuer}</td><td>issuer</td><td>任一关键词是子串</td></tr>
 *   <tr><td>{@code column}</td><td>——</td><td>配合 {@code scores} 直接指定等次列</td></tr>
 * </table>
 *
 * @param match  匹配条件，见上表
 * @param score  固定分值；扣分项写负数
 * @param scores 二维矩阵的一行，对应 {@link RuleGroup#columns()}；null 表示该等次不计分
 * @param unit   计数单位（次 / 小时 / 篇）；有 unit 的只标"需人工填数量"，不猜
 * @param cap    该条目单独的上限
 * @param manual 标为人工判定（无法从材料自动定分）
 * @param note   依据说明，原样显示在页面上
 */
public record RuleItem(
        Map<String, Object> match,
        Double score,
        List<Double> scores,
        String unit,
        Double cap,
        boolean manual,
        String note
) {

    /** 是否需要人工补充信息（数量 / 等次 / 定级）才能算分 */
    public boolean needsManualInput() {
        if (manual) {
            return true;
        }
        // 有计数单位但没说数量 —— 材料里读到"志愿服务"也读不出时长
        return unit != null && !unit.isBlank();
    }

    /** 矩阵条目：分值要按等次列挑 */
    public boolean isMatrix() {
        return scores != null && !scores.isEmpty();
    }

    /** 扣分条目 */
    public boolean isNegative() {
        return score != null && score < 0;
    }

    /**
     * 一句话描述，用于页面「依据」列。
     *
     * <p>只写人看得懂的部分：备注（细则条款）＞ 级别，绝不把 {@code titles} 那串关键词
     * 原样吐出来——班委看的是「国家级·按等次表折算」，不是一份关键词清单。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (note != null && !note.isBlank()) {
            sb.append(note);
        } else {
            Object lv = match == null ? null : match.get("level");
            if (lv != null) {
                sb.append(String.valueOf(lv));
            }
        }
        if (score != null) {
            sb.append(sb.length() > 0 ? " · " : "").append(score >= 0 ? "+" : "").append(fmt(score)).append(" 分");
        } else if (isMatrix()) {
            sb.append(sb.length() > 0 ? " · " : "").append("按等次表折算");
        }
        if (unit != null && !unit.isBlank()) {
            sb.append(" / ").append(unit);
        }
        if (cap != null) {
            sb.append("，上限 ").append(fmt(cap));
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
