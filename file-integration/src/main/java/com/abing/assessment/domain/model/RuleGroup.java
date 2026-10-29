package com.abing.assessment.domain.model;

import java.util.List;

/**
 * 一组规则 = 一类加分 / 扣分来源（学生干部、荣誉、竞赛、扣分…）。
 *
 * <p>{@code maxItems} 表达细则里的「身兼多项最多选两项」「同类取最高」：引擎按分值保留前 N 项，
 * 其余标「超限不计」但<b>不删除</b>，让人看得见哪项被挤掉。
 *
 * @param id         组标识，稳定不随文本改动
 * @param dimension  归属维度（须是 cfg.dimensions 里的 id）
 * @param kind       bonus 加分 / deduction 扣分
 * @param name       展示名
 * @param maxItems   最多计入几项；null = 不限
 * @param columns    等次列，配合 item.scores 使用
 * @param exclusions 排除条款，只展示不参与计算
 * @param items      规则条目
 * @param note       补充说明
 */
public record RuleGroup(
        String id,
        String dimension,
        String kind,
        String name,
        Integer maxItems,
        List<String> columns,
        List<String> exclusions,
        List<RuleItem> items,
        String note
) {

    public boolean isDeduction() {
        return "deduction".equalsIgnoreCase(kind);
    }

    public boolean hasColumns() {
        return columns != null && !columns.isEmpty();
    }
}
