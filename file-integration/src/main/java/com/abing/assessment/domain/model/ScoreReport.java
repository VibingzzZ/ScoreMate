package com.abing.assessment.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 算分结果。
 *
 * <p>设计上刻意"啰嗦"：每一条算进总分的分都能追到
 * {@code 哪份材料 → 哪条细则 → 多少分 → 凭什么}。
 * 架构文档 §5.3 的硬纪律是 <b>"答不出凭什么的分不允许出现在结果里"</b>，
 * 所以 {@link ScoreLine#basis()} 和 {@link ScoreLine#evidence()} 都是一等字段。
 *
 * @param overall 总分，{@code null} 表示算不出来（缺输入 / 维度无分）
 * @param status  {@code ok} 全部算齐 / {@code partial} 有维度缺输入 / {@code empty} 没有材料
 * @param penalties 扣分明细（手填的 + 材料里扣出来的），评定表扣分列要逐条写事由
 */
public record ScoreReport(
        String school,
        String college,
        String ruleVersion,
        String academicYear,
        String windowStart,
        String windowEnd,
        List<DimensionScore> dimensions,
        List<ScoreLine> lines,
        List<Unmatched> unmatched,
        List<String> issues,
        Double overall,
        String status,
        List<PenaltyEntry> penalties
) {

    /**
     * 一个维度的算分结果。
     *
     * @param inputs 实际用到的输入（用户填的 + bonus/deduction）
     * @param score  维度得分，null = 缺输入算不了
     * @param status ok / need_input / no_rules
     */
    public record DimensionScore(
            String id,
            String name,
            double weight,
            String formula,
            Map<String, Double> inputs,
            double bonus,
            double deduction,
            Double score,
            String status,
            String note,
            List<String> warnings
    ) {
    }

    /**
     * 一条算分明细。
     *
     * @param selected  在同一「材料 × 规则组」里是否被选为采用的那条
     * @param included  最终是否计入合计
     * @param manual    需要人工定分（材料里读不出等次 / 数量）
     * @param inWindow  时间窗口判定：true 在窗口内 / false 超出 / null 日期读不出
     * @param score     分值，null = 无法自动定分
     */
    public record ScoreLine(
            String id,
            int materialIndex,
            String fileName,
            String dimension,
            String groupId,
            String groupName,
            String basis,
            Double score,
            boolean selected,
            boolean included,
            boolean manual,
            Boolean inWindow,
            boolean needsConfirm,
            String reason,
            String evidence
    ) {
    }

    /** 没匹配到任何规则的材料——绝不静默丢弃 */
    public record Unmatched(int materialIndex, String fileName, String reason) {
    }
}
