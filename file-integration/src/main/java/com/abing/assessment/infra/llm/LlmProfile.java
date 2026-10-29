package com.abing.assessment.infra.llm;

import java.util.UUID;

/**
 * 一份模型配置档案（CC-SWITCH 风格的「配置项」）。
 *
 * <p>用户可以存多份档案（"我的 DeepSeek"、"实验室 Qwen"、"公司 OpenAI"…），
 * 每份 = 名称 + 厂商 + 接口地址 + 模型名 + Key，随时切换启用哪一份。
 *
 * <p>整个档案（含 Key）加密后作为一个 JSON 字符串存入 {@link ApiKeyStore}，
 * 存储接口不用变，也能承载一组信息。
 *
 * @param id       档案唯一标识；新建时为空，由 {@link LlmProfileStore} 补
 * @param name     用户起的名字，可空（空则用「厂商标名 · 模型」兜底显示）
 * @param provider deepseek / doubao / openai / claude / custom
 * @param baseUrl  OpenAI 兼容端点，例如 https://api.deepseek.com/v1
 * @param model    模型名，任意可填（不写死）
 * @param apiKey   API Key，只在此对象里流转，绝不进日志
 */
public record LlmProfile(String id, String name, String provider, String baseUrl, String model, String apiKey) {

    /** 兼容单档案时代的调用（那时还没有 id / name） */
    public LlmProfile(String provider, String baseUrl, String model, String apiKey) {
        this(null, null, provider, baseUrl, model, apiKey);
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 没有 id 就补一个（旧档案迁移、前端新增时都会用到） */
    public LlmProfile withIdIfAbsent() {
        return (id == null || id.isBlank())
                ? new LlmProfile(newId(), name, provider, baseUrl, model, apiKey)
                : this;
    }

    /** 编辑时只改元信息、保留原 Key（页面不回填明文，编辑框留空即表示不改） */
    public LlmProfile withKey(String newKey) {
        return new LlmProfile(id, name, provider, baseUrl, model, newKey);
    }

    /** 页面展示名：用户起的名字优先，否则「厂商标名 · 模型」 */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return ProviderDefaults.label(provider) + " · " + (model == null || model.isBlank() ? "未指定模型" : model);
    }

    static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /**
     * 掩码打印。record 默认 toString 会把 apiKey 原样带出来——任何一处不小心
     * log 了整个 profile、或异常消息带上它，Key 就泄进日志了。这里从类型层面堵死。
     */
    @Override
    public String toString() {
        return "LlmProfile[id=" + id + ", name=" + name + ", provider=" + provider
                + ", baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + mask(apiKey) + "]";
    }

    /** 只露头 3 尾 2，中间打码：sk-****xx */
    static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "(空)";
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 2);
    }
}
