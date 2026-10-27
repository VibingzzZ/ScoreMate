package com.abing.assessment.infra.llm;

import java.util.List;

/**
 * 各厂商的默认接入参数。
 *
 * <p>四家都走 OpenAI 兼容的 chat/completions 协议（DeepSeek / 豆包 / OpenAI 官方均原生支持，
 * Claude 经 OpenRouter 等兼容网关），所以一个 {@link OpenAiCompatExtractor} 就能全覆盖，
 * provider 只决定 baseUrl / model 的出厂默认值，可覆盖。
 *
 * <p>Claude 直连 Anthropic 官方（Messages API 格式不同）属于 P1：接口已抽象，后续加
 * {@code AnthropicExtractor} + 工厂即可，不破坏此处。
 */
public final class ProviderDefaults {

    private ProviderDefaults() {}

    public static String defaultBaseUrl(String provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider.toLowerCase()) {
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "doubao"   -> "https://ark.cn-beijing.volces.com/api/v1";
            case "openai"   -> "https://api.openai.com/v1";
            case "claude"   -> "https://openrouter.ai/api/v1"; // 经 OpenAI 兼容网关，填 Anthropic Key
            default         -> "";                              // custom：用户自己填
        };
    }

    public static String defaultModel(String provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider.toLowerCase()) {
            case "deepseek" -> "deepseek-chat";
            case "doubao"   -> "doubao-vision-pro-32k";
            case "openai"   -> "gpt-4o-mini";
            case "claude"   -> "anthropic/claude-3.5-sonnet";
            default         -> "";
        };
    }

    /** 给页面渲染下拉用 */
    public static List<String> providers() {
        return List.of("deepseek", "doubao", "openai", "claude", "custom");
    }
}
