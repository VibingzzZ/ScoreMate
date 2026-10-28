package com.abing.assessment.domain.model;

import java.util.List;

/**
 * 一个计分维度（德 / 智 / 体 / 美 / 劳）。
 *
 * <p><b>维度固定这五个</b>（2026-10-27 决策 4）：细则里出现五育之外的维度不自动进这里，
 * 由 AI 标出来、人工判定。固定下来才能让公式和汇总逻辑稳定，不会每年返工。
 *
 * <p>{@code formula} 里可用变量（由引擎注入）：
 * <ul>
 *   <li>{@code base} / {@code peer} … —— 来自 {@link #inputs()}，由用户手填（基础分、互评分这类
 *       证书里根本读不到的东西）</li>
 *   <li>{@code bonus} —— 本维度所有计入的加分项之和（引擎算出）</li>
 *   <li>{@code deduction} —— 本维度扣分之和的<b>绝对值</b>（公式里统一写减号）</li>
 * </ul>
 *
 * @param id        moral / intellect / physique / aesthetic / labour
 * @param name      展示名：德育 / 智育 / 体育 / 美育 / 劳育
 * @param weight    占总分的权重，五个加起来应为 1
 * @param formula   计分公式，见类注释
 * @param floorZero 结果小于 0 时是否归零（德育细则明确"扣分大于前三项则为 0"）
 * @param inputs    需要用户手填的输入项
 * @param note      说明（例如"该维度加分明细尚未结构化"）
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
