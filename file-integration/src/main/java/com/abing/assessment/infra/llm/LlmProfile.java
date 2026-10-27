package com.abing.assessment.infra.llm;

/**
 * 当前生效的模型配置档案。
 *
 * <p>多厂商支持的核心载体：用户选择厂商（provider）后，baseUrl / model 自动带出默认值，
 * 也允许手动覆盖。整个档案（含 Key）加密后作为一个 JSON 字符串存入 {@link ApiKeyStore}，
 * 这样存储接口不用变，也能承载「厂商 + 地址 + 模型 + Key」这一组信息。
 */
public record LlmProfile(String provider, String baseUrl, String model, String apiKey) {

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
