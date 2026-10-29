package com.abing.assessment.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型接入配置。api-key 只从环境变量读（{@code ${LLM_API_KEY:}}），
 * 不进配置文件也不进仓库——泄露的是用户自己的钱。
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        String baseUrl,
        String model,
        String apiKey,
        String provider,
        int timeoutSeconds,
        int maxTokens,
        String promptFile,
        Boolean thinkingDisabled
) {

    /**
     * 默认输出预算。
     *
     * <p>抽取的正文只有 200 字左右（实测 165–182 token），2000 是很宽的安全边际。
     *
     * <p><b>为什么要关思考（2026-10-29 实测）</b>：deepseek-flash 默认开思考、effort=high，
     * 一次证书抽取会先花掉 4000 token 思考再被 max_tokens 截断，content 为空 →
     * 触发翻倍重试，等效每份材料调用两次。关掉后单份 29s/5942tok 降到 6.6s/2057tok，
     * 且金标准准确率反而更高（思考会挤掉正文预算）。抽取是「看字抄字」，
     * 不需要推理——需要推理的是算分，那部分在本地 ScoreEngine 里，不花 token。
     */
    private static final int DEFAULT_MAX_TOKENS = 2000;

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
        if (thinkingDisabled == null) {
            thinkingDisabled = true;
        }
    }

    /** 是否要求上游关闭思考模式（默认关；端点不支持时会自动降级，见 OpenAiCompatExtractor） */
    public boolean thinkingOff() {
        return Boolean.TRUE.equals(thinkingDisabled);
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
