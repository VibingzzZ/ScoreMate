package com.abing.assessment.domain.model;

/**
 * 一次抽取的结果，除了事实本身，还带上"这次用了什么模型、花了多久、多少 token"。
 *
 * <p>这两项不是装饰：调 prompt 和换模型时，没有耗时和 token 你就无法判断
 * "是模型不行"还是"这次网络抖了"。
 *
 * <p>刻意<b>只调一次模型</b>：姓名这类身份字段最终以用户填写的本人信息为准，
 * 靠"第二次调用复核"既慢又贵，收益还不如让用户在歧义清单里改一次。
 */
public record ExtractionResult(
        ExtractedFact fact,
        String model,
        long latencyMs,
        Integer totalTokens
) {
}
