package com.abing.assessment.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 从一份材料里抽出来的"事实"。
 *
 * <p>它只回答"材料上写了什么"，不含任何分值判断——分值是规则引擎的事（见架构文档 §6.0）。
 *
 * <p><b>null 有明确语义</b>：材料上没写 / 无法确定。转换时不要把 null 变成空串，
 * 否则"证书上没有写级别"和"级别是空"就分不清了，规则层会误判。
 *
 * <p>字段名用 snake_case 是为了跟 prompt 输出、金标准 JSON 完全对齐，
 * 少一层映射就少一处出错的地方。
 *
 * <p>事实字段（判准确率只看这 7 个）：{@code doc_type / person_name / issuer / date /
 * period_start / period_end / title}。其余是弱判断，只记录不判对错。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedFact(
        @JsonProperty("doc_type") String docType,
        @JsonProperty("person_name") String personName,
        @JsonProperty("is_multi_person") Boolean multiPerson,
        @JsonProperty("issuer") String issuer,
        @JsonProperty("date") String date,
        @JsonProperty("period_start") String periodStart,
        @JsonProperty("period_end") String periodEnd,
        @JsonProperty("title") String title,
        @JsonProperty("achievement") String achievement,
        @JsonProperty("level_hint") String levelHint,
        @JsonProperty("category_guess") String categoryGuess,
        @JsonProperty("evidence_quote") String evidenceQuote,
        @JsonProperty("confidence") Double confidence
) {

    /**
     * 换掉姓名（统一成本人信息时用）。
     * record 不可变，只能整条重建——比加一堆 setter 更不容易漏字段。
     */
    public ExtractedFact withPersonName(String newName) {
        return new ExtractedFact(docType, newName, multiPerson, issuer, date, periodStart, periodEnd,
                title, achievement, levelHint, categoryGuess, evidenceQuote, confidence);
    }
}
