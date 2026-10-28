package com.abing.assessment.domain.model;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份已加载的规则配置（学院层）。
 *
 * <p>2026-10-28 收窄：schema 已经定稿（见 docs/综测整理-规则schema草案.md 的五项决策），
 * 所以 {@code dimensions} / {@code ruleGroups} 是两个一等公民，不再只是躺在 raw 里的 Map。
 * <b>{@link #raw()} 仍然保留</b>——它是兜底：解析器认不出的新字段不至于被丢掉，
 * 排查"配置写了但没生效"时能直接看到原文结构。
 *
 * @param formulas          计分公式原文（{@code overall} 必填，各维度公式也可放这里）
 * @param globalConstraints  跨组通用约束（时间窗口 / 同类取最高 / 须证明 / 多人框本人）
 * @param dimensions         五个计分维度（德智体美劳）
 * @param ruleGroups         加分/扣分规则组
 */
public record RuleConfig(
        String school,
        String college,
        String ruleVersion,
        String sourceFile,
        Path file,
        Map<String, Object> formulas,
        Map<String, Object> globalConstraints,
        List<DimensionSpec> dimensions,
        List<RuleGroup> ruleGroups,
        Map<String, Object> raw
) {

    /** 一行摘要，用于日志和页面展示 */
    public String summary() {
        return school + " / " + college + " / " + ruleVersion;
    }

    /** 总分公式，缺省按五育标准权重 */
    public String overallFormula() {
        Object f = formulas == null ? null : formulas.get("overall");
        return f == null ? "德育*0.20 + 智育*0.60 + 体育*0.10 + 美育*0.05 + 劳育*0.05" : String.valueOf(f);
    }

    /** 维度 id → 维度，方便引擎按 id 查 */
    public Map<String, DimensionSpec> dimensionIndex() {
        Map<String, DimensionSpec> m = new LinkedHashMap<>();
        if (dimensions != null) {
            dimensions.forEach(d -> m.put(d.id(), d));
        }
        return m;
    }

    /** 有没有任何可自动匹配的规则条目——没有的话算分只能算基础分部分 */
    public boolean hasStructuredRules() {
        return ruleGroups != null && ruleGroups.stream().anyMatch(g -> !g.items().isEmpty());
    }

    /** 某个维度下有没有结构化规则 */
    public boolean hasRulesFor(String dimensionId) {
        return ruleGroups != null && ruleGroups.stream()
                .anyMatch(g -> dimensionId.equals(g.dimension()) && !g.items().isEmpty());
    }

    /** 时间窗口约束是否生效 */
    public boolean timeWindowEnabled() {
        return boolOf(globalConstraints, "time_window", true);
    }

    private static boolean boolOf(Map<String, Object> m, String key, boolean dft) {
        if (m == null) {
            return dft;
        }
        Object v = m.get(key);
        if (v == null) {
            return dft;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
