package com.abing.assessment.infra.llm;

import java.util.List;

/**
 * 各厂商的默认接入参数。
 *
 * <p>四家都走 OpenAI 兼容的 chat/completions 协议（DeepSeek / 豆包 / OpenAI 官方均原生支持，
 * Claude 经 OpenRouter 等兼容网关），所以一个 {@link OpenAiCompatExtractor} 就能全覆盖，
 * provider 只决定 baseUrl / model 的<b>预填建议值</b>，用户可在页面上改成任意模型。
 *
 * <p><b>模型名绝不写死</b>：厂商模型阵容会随时间更新（如 DeepSeek 的 deepseek-chat 已更名下线，
 * 现行为 deepseek-flash），任何「支持图像输入」的模型都可以直接填，包括 Ollama 本地模型。
 * 这里给的只是引导默认，实际生效的是用户保存的档案（{@link LlmProfile}）。
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

    /**
     * 各厂商的模型预填建议——只是「选厂商时自动带出」的占位，用户随时可改成任意模型名。
     * 注意只给「支持图像输入」的模型；纯文本模型跑证书会返回「看不到图像」。
     * （2026-10 核实：DeepSeek 现行视觉模型是 deepseek-flash，旧名 deepseek-chat 已过时。）
     */
    public static String defaultModel(String provider) {
        if (provider == null) {
            return "";
        }
        return switch (provider.toLowerCase()) {
            case "deepseek" -> "deepseek-flash";      // DeepSeek 现行视觉模型，可改
            case "doubao"   -> "doubao-vision-pro-32k"; // 可改
            case "openai"   -> "gpt-4o-mini";          // 可改
            case "claude"   -> "anthropic/claude-3.5-sonnet"; // 可改
            default         -> "";                     // custom：用户自己填
        };
    }

    /** 给页面渲染下拉用 */
    public static List<String> providers() {
        return List.of("deepseek", "doubao", "openai", "claude", "custom");
    }

    /**
     * 厂商标名（给人看的）。未知厂商原样返回，不报错——
     * 用户可能填任何 OpenAI 兼容网关，我们没有理由拦它。
     */
    public static String label(String provider) {
        if (provider == null || provider.isBlank()) {
            return "自定义";
        }
        return switch (provider.toLowerCase()) {
            case "deepseek" -> "DeepSeek";
            case "doubao"   -> "豆包";
            case "openai"   -> "OpenAI";
            case "claude"   -> "Claude";
            case "env"      -> "环境变量";
            default         -> provider;
        };
    }
}
