package com.abing.assessment.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 当前该用哪份模型配置（厂商 + 地址 + 模型 + Key）。
 *
 * <p>支持多份档案（CC-SWITCH 式）：用户存多套配置，随时切换启用项，
 * 由 {@link LlmProfileStore} 负责列表与启用状态。
 *
 * <p>优先级：<b>页面保存的启用档案 &gt; 环境变量 LLM_API_KEY</b>。
 * 页面填的优先，因为那是用户显式的选择；环境变量留给开发调试和 CI。
 *
 * <p>完整 Key 只在调用模型的那一刻出现一次，其它地方一律拿不到。
 */
@Component
public class ApiKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvider.class);

    private final LlmProfileStore store;
    private final LlmProperties props;

    public ApiKeyProvider(LlmProfileStore store, LlmProperties props) {
        this.store = store;
        this.props = props;
    }

    /**
     * 当前生效的完整档案；没有则返回 null。
     * 页面保存的启用项优先；否则若环境变量配了 Key，用配置里的 provider/baseUrl/model 兜底。
     */
    public LlmProfile currentProfile() {
        LlmProfile active = store.active();
        if (active != null && active.hasKey()) {
            return active;
        }
        if (props.hasApiKey()) {
            return new LlmProfile("env", "环境变量 LLM_API_KEY",
                    props.provider(), props.baseUrl(), props.model(), props.apiKey());
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

    /** 来源：saved（页面保存的档案）/ env（环境变量）/ none */
    public String source() {
        LlmProfile active = store.active();
        if (active != null && active.hasKey()) {
            return "saved";
        }
        return props.hasApiKey() ? "env" : "none";
    }

    /** 掩码，只用于页面回显：sk-ab****wxyz */
    public String masked() {
        return mask(current());
    }

    // ── 多档案操作（供 LlmController 用） ─────────────────────────────────

    public List<LlmProfile> profiles() {
        return store.list();
    }

    public LlmProfile upsert(LlmProfile profile) {
        return store.upsert(profile);
    }

    /** 兼容旧调用：等同于新增/更新一份档案 */
    public LlmProfile save(LlmProfile profile) {
        return store.upsert(profile);
    }

    public boolean activate(String id) {
        return store.activate(id);
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    /** 清空全部档案（页面上的"全部清除"） */
    public void clear() {
        store.clearAll();
    }

    public String storageDescription() {
        return store.storageDescription();
    }

    /** 与 {@link LlmProfile#mask(String)} 一致，供页面回显用 */
    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, 5) + "****" + key.substring(key.length() - 4);
    }
}
