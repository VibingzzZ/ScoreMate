package com.abing.assessment.domain.model;

import java.util.List;

/**
 * 一个计分维度（德 / 智 / 体 / 美 / 劳），固定五个；细则里多出来的维度只标给人看，不自动生效。
 *
 * <p>{@code formula} 可用变量：{@code inputs} 里的手填项（如 base / peer）、
 * {@code bonus}（本维度加分之）、{@code deduction}（扣分之<b>绝对值</b>，公式里统一写减号）。
 *
 * @param id        moral / intellect / physique / aesthetic / labour
 * @param name      展示名
 * @param weight    占总分权重，五项之和应为 1
 * @param formula   计分公式
 * @param floorZero 结果小于 0 时是否归零（德育细则"扣分大于前三项则为 0"）
 * @param inputs    需用户手填的输入项
 * @param note      说明（如"该维度加分明细尚未结构化"）
 */
public record DimensionSpec(
        String id,
        String name,
        double weight,
        String formula,
        boolean floorZero,
        List<InputSpec> inputs,
        String note
) {

    /**
     * 一个需要用户手填的输入项。
     *
     * @param key  公式里引用的变量名（如 base / peer）
     * @param name 展示名（如「德育基础分」）
     * @param hint 提示（告诉用户从哪张表/哪个系统取这个数）
     */
    public record InputSpec(String key, String name, String hint) {

        public boolean hasHint() {
            return hint != null && !hint.isBlank();
        }
    }

    /** 这个维度的公式是否用到了某个变量（判断"缺输入"时用） */
    public boolean formulaUses(String var) {
        if (formula == null || var == null) {
            return false;
        }
        // 粗判即可：变量名都是标识符，前后不能紧邻字母数字
        return formula.matches("(?s).*?(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(var)
                + "(?![A-Za-z0-9_]).*");
    }
}
