package com.abing.assessment.domain.model;

import java.util.List;

/**
 * 一组规则 = 一类加分/扣分来源（学生干部、荣誉类、竞赛获奖、扣分…）。
 *
 * <p>{@code maxItems} 是这组最多能命中几项，用来表达细则里的
 * 「身兼多项职务最多选两项」「个人荣誉最多叠加两项」「同一类别取最高」。
 * 引擎按分值从高到低保留前 N 项，其余标记为「超限不计」——
 * <b>不删掉</b>，让它留在页面上，用户能看见自己哪一项被挤掉了。
 *
 * @param id         组标识，稳定不随文本改动
 * @param dimension  归属维度（必须是 cfg.dimensions 里的 id）
 * @param kind       bonus 加分 / deduction 扣分
 * @param name       展示名
 * @param maxItems   最多计入几项；null = 不限
 * @param columns    等次列（「一等类/二等类/…」「主要负责人/一般成员」），配合 item.scores 使用
 * @param exclusions 排除说明（原文条款），只展示不参与计算
 * @param items      规则条目
 * @param note       补充说明（例如"该校细则此表数值待人工核对"）
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
