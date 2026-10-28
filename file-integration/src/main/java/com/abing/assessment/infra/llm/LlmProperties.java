package com.abing.assessment.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型接入配置。
 *
 * <p><b>api-key 只从环境变量读</b>（application.yaml 里写的是 {@code ${LLM_API_KEY:}}），
 * 绝不把 Key 写进配置文件、也不写进仓库。这是 BYOK 模式下的硬要求：
 * 泄露的是用户自己的钱（见架构文档 §2.7）。
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        String provider,
        int timeoutSeconds,
        int maxTokens,
        String promptFile
) {

    /**
     * 默认输出预算。
     *
     * <p>不能给小：推理类模型（如 deepseek-flash）会先消耗 token 在思考上，预算给小了
     * 正文根本轮不到，表现为 content 为空 + finish_reason=length。
     * 一次证书抽取的正文只有几百 token，4000 留足思考空间，越界时还会自动翻倍重试。
     */
    private static final int DEFAULT_MAX_TOKENS = 4000;

    public LlmProperties {
        // 给默认值，避免 application.yaml 漏配一项就启动失败。
        // 这里只是引导占位——实际生效的模型以用户在页面保存的档案（LlmProfile）为准，
        // 模型名不写死，任何「支持图像输入」的模型都可以填。
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com/v1";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-flash";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
        }
        if (maxTokens <= 0) {
            maxTokens = DEFAULT_MAX_TOKENS;
        }
        if (promptFile == null || promptFile.isBlank()) {
            promptFile = "extract-v1.txt";
        }
    }

    /** 是否已经具备调用条件（有 Key） */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 给页面看的描述，不含 Key */
    public String describe() {
        return model + " @ " + baseUrl;
    }

    /** 掩码打印，防 record 默认 toString 把 apiKey 带进日志/异常 */
    @Override
    public String toString() {
        return "LlmProperties[baseUrl=" + baseUrl + ", model=" + model + ", provider=" + provider
                + ", apiKey=" + LlmProfile.mask(apiKey)
                + ", timeoutSeconds=" + timeoutSeconds + ", promptFile=" + promptFile + "]";
    }
}
