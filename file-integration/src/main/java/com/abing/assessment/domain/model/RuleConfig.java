package com.abing.assessment.domain.model;

import java.nio.file.Path;
import java.util.Map;

/**
 * 一份已加载的规则配置（学院层）。
 *
 * <p><b>为什么只强类型化几个字段</b>：规则配置的 schema（架构文档 §12 里的 S2）还没定稿。
 * 现在把 moral / physique / aesthetic 的内部结构写成 record，等第二份学院细则进来
 * （它的智育是"两次查表"、体育是"公式换算"）一定要返工。所以先只锁"必须有"的字段，
 * 其余原样留在 {@link #raw()} 里，等 S2 定了再逐步收窄。
 *
 * <p>这不是偷懒，是刻意留出变更空间：<b>提前强类型化 = 提前把假设写死。</b>
 */
public record RuleConfig(
        String school,
        String college,
        String ruleVersion,
        String sourceFile,
        Path file,
        Map<String, Object> formulas,
        Map<String, Object> globalConstraints,
        Map<String, Object> raw
) {

    /** 一行摘要，用于日志和页面展示 */
    public String summary() {
        return school + " / " + college + " / " + ruleVersion;
    }
}
