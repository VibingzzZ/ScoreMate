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
        int timeoutSeconds,
        String promptFile
) {

    public LlmProperties {
        // 给默认值，避免 application.yaml 漏配一项就启动失败
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (model == null || model.isBlank()) {
            model = "qwen-vl-max";
        }
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 120;
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
}
