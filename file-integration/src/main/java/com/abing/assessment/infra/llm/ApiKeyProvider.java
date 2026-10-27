package com.abing.assessment.infra.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 当前该用哪个 API Key。
 *
 * <p>优先级：<b>页面填的（DPAPI 加密保存） &gt; 环境变量 LLM_API_KEY</b>。
 * 页面填的优先，因为那是用户显式的选择；环境变量留给开发调试和 CI。
 *
 * <p>这个类是对外唯一出口：其它地方一律拿不到"存了没有"以外的信息，
 * 完整 Key 只在调用模型的那一刻出现一次。
 */
@Component
public class ApiKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyProvider.class);

    private final ApiKeyStore store;
    private final LlmProperties props;

    public ApiKeyProvider(ApiKeyStore store, LlmProperties props) {
        this.store = store;
        this.props = props;
    }

    /** 当前生效的 Key；没有则返回 null。调用方用完不要缓存、不要写日志 */
    public String current() {
        String saved = store.load();
        if (saved != null && !saved.isBlank()) {
            return saved.trim();
        }
        String fromEnv = props.apiKey();
        return (fromEnv == null || fromEnv.isBlank()) ? null : fromEnv.trim();
    }

    public boolean hasKey() {
        return current() != null;
    }

    /** 来源：saved（页面填的）/ env（环境变量）/ none */
    public String source() {
        String saved = store.load();
        if (saved != null && !saved.isBlank()) {
            return "saved";
        }
        String fromEnv = props.apiKey();
        return (fromEnv != null && !fromEnv.isBlank()) ? "env" : "none";
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

    public void save(String apiKey) throws IOException {
        store.save(apiKey);
    }

    public void clear() {
        store.clear();
    }

    public String storageDescription() {
        return store.describeStorage();
    }
}
