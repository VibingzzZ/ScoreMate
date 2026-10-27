package com.abing.assessment.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 当前该用哪个模型配置（厂商 + 地址 + 模型 + Key）。
 *
 * <p>优先级：<b>页面填的（DPAPI 加密保存） &gt; 环境变量 LLM_API_KEY</b>。
 * 页面填的优先，因为那是用户显式的选择；环境变量留给开发调试和 CI。
 *
 * <p>完整 Key 只在调用模型的那一刻出现一次，其它地方一律拿不到。
 */
@Component
public class ApiKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiKeyStore store;
    private final LlmProperties props;

    public ApiKeyProvider(ApiKeyStore store, LlmProperties props) {
        this.store = store;
        this.props = props;
    }

    /**
     * 当前生效的完整档案；没有则返回 null。
     * 页面保存的优先；否则若环境变量配了 Key，用配置里的 provider/baseUrl/model 兜底。
     */
    public LlmProfile currentProfile() {
        String raw = store.load();
        if (raw != null && !raw.isBlank()) {
            try {
                LlmProfile saved = MAPPER.readValue(raw, LlmProfile.class);
                if (saved != null && saved.hasKey()) {
                    return saved;
                }
            } catch (JsonProcessingException e) {
                log.warn("已保存的模型配置无法解析（可能格式变化），将忽略并回退到环境变量：{}", e.toString());
            }
        }
        if (props.hasApiKey()) {
            return new LlmProfile(props.provider(), props.baseUrl(), props.model(), props.apiKey());
        }
        return null;
    }

    /** 当前生效的 Key；没有则返回 null。调用方用完不要缓存、不要写日志 */
    public String current() {
        LlmProfile p = currentProfile();
        return p == null ? null : p.apiKey();
    }

    /** 当前厂商；没有返回 null（页面可据此外显「未选择厂商」） */
    public String provider() {
        LlmProfile p = currentProfile();
        return p == null ? null : p.provider();
    }

    public boolean hasKey() {
        return current() != null;
    }

    /** 来源：saved（页面填的）/ env（环境变量）/ none */
    public String source() {
        String raw = store.load();
        if (raw != null && !raw.isBlank()) {
            try {
                LlmProfile saved = MAPPER.readValue(raw, LlmProfile.class);
                if (saved != null && saved.hasKey()) {
                    return "saved";
                }
            } catch (JsonProcessingException ignored) {
                // 解析失败按没有处理
            }
        }
        return props.hasApiKey() ? "env" : "none";
    }

    /** 掩码，只用于页面回显：sk-ab****wxyz */
    public String masked() {
        String key = current();
        if (key == null) {
            return null;
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 5) + "****" + key.substring(key.length() - 4);
    }

    /** 保存页面填的档案（含厂商，整体加密） */
    public void save(LlmProfile profile) throws IOException {
        store.save(MAPPER.writeValueAsString(profile));
    }

    /** 便捷方法：仅传 Key 时退化为 openai 兼容默认（保留旧调用兼容） */
    public void save(String apiKey) throws IOException {
        save(new LlmProfile("openai", ProviderDefaults.defaultBaseUrl("openai"),
                ProviderDefaults.defaultModel("openai"), apiKey));
    }

    public void clear() {
        store.clear();
    }

    public String storageDescription() {
        return store.describeStorage();
    }
}
