package com.abing.assessment.domain.model;

/**
 * 一次抽取的结果：事实 + 本次用的模型 / 耗时 / token（没有这些就无法区分"模型不行"与"网络抖了"）。
 *
 * <p>刻意<b>只调一次</b>：身份字段以用户填的本人信息为准，二次复核又慢又贵，
 * 收益不如让用户在歧义清单里改一次。
 */
public record ExtractionResult(
        ExtractedFact fact,
        String model,
        long latencyMs,
        Integer totalTokens
) {
}
