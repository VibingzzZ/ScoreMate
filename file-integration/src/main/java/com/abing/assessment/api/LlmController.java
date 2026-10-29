package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.infra.llm.ApiKeyProvider;
import com.abing.assessment.infra.llm.Extractor;
import com.abing.assessment.infra.llm.LlmProfile;
import com.abing.assessment.infra.llm.LlmProfileStore;
import com.abing.assessment.infra.llm.ProviderDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置的增删改与启停。四条硬规矩：
 * 只写不读（前端只能拿到掩码）；先测后留（测不通回滚，不留坏配置）；
 * 不落明文（整组档案 DPAPI 加密）；编辑不强制重填（Key 留空 = 保持原值）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private static final Logger log = LoggerFactory.getLogger(LlmController.class);

    /** 粗筛用：正常 Key 不会短于这个长度，太短基本是没复制全 */
    private static final int MIN_KEY_LENGTH = 10;

    private final ApiKeyProvider apiKeys;
    private final LlmProfileStore store;
    private final Extractor extractor;

    public LlmController(ApiKeyProvider apiKeys, LlmProfileStore store, Extractor extractor) {
        this.apiKeys = apiKeys;
        this.store = store;
        this.extractor = extractor;
    }

    @GetMapping
    public Result<Map<String, Object>> status() {
        return Result.success(view());
    }

    /**
     * 新增或更新一份配置，并立即测连通性。请求体 {@code {id?, name?, provider, apiKey?, baseUrl?, model?}}：
     * 无 id 则新增并设为启用；有 id 且 apiKey 留空则保留原 Key。
     * 测失败回滚本次改动，避免用户以为配好了、上传证书时才收到 401。
     */
    @PostMapping("/key")
    public Result<Map<String, Object>> saveKey(@RequestBody Map<String, String> body) throws IOException {
        if (body == null) {
            throw new BusinessException("请求体为空", 400);
        }
        String id = trimToNull(body.get("id"));
        String provider = defaultProvider(body.get("provider"));
        String apiKey = trimToNull(body.get("apiKey"));
        String name = trimToNull(body.get("name"));

        // 编辑模式：Key 留空表示"不改"，沿用原来那把
        if (apiKey == null && id != null) {
            LlmProfile existing = findById(id);
            if (existing == null) {
                throw new BusinessException("要修改的配置不存在（可能已被删除），刷新页面后重试", 404);
            }
            apiKey = existing.apiKey();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("Key 不能为空", 400);
        }
        apiKey = apiKey.trim();
        if (apiKey.length() < MIN_KEY_LENGTH) {
            throw new BusinessException("Key 看起来不完整（只有 " + apiKey.length() + " 个字符），检查是否复制全了", 400);
        }
        // 顺手挡一下最常见的误操作：把整个 "Bearer sk-xxx" 粘进来
        if (apiKey.toLowerCase().startsWith("bearer ")) {
            apiKey = apiKey.substring(7).trim();
        }

        String baseUrl = trimToNull(body.get("baseUrl"));
        if (baseUrl == null) {
            baseUrl = ProviderDefaults.defaultBaseUrl(provider);
        }
        String model = trimToNull(body.get("model"));
        if (model == null) {
            model = ProviderDefaults.defaultModel(provider);
        }
        if (baseUrl == null || model == null) {
            throw new BusinessException("请填写 baseUrl 和 model，或选择一个已知厂商", 400);
        }

        String snapshot = store.snapshot();
        LlmProfile saved = apiKeys.upsert(new LlmProfile(id, name, provider, baseUrl.trim(), model.trim(), apiKey));
        apiKeys.activate(saved.id());
        try {
            extractor.ping();
        } catch (RuntimeException e) {
            // 测不通就不留：回滚到本次操作前的状态
            store.restore(snapshot);
            log.info("连通性测试失败，已回滚本次配置改动");
            throw e;
        }
        return Result.success(view());
    }

    /** 切换启用哪一份配置 */
    @PostMapping("/active")
    public Result<Map<String, Object>> activate(@RequestBody Map<String, String> body) {
        String id = body == null ? null : trimToNull(body.get("id"));
        if (id == null) {
            throw new BusinessException("缺少要启用的配置 id", 400);
        }
        if (!apiKeys.activate(id)) {
            throw new BusinessException("配置不存在（可能已被删除），刷新页面后重试", 404);
        }
        return Result.success(view());
    }

    /**
     * 删除一份配置；不传 id 则清空全部（兼容旧调用）。
     */
    @DeleteMapping("/key")
    public Result<Map<String, Object>> clearKey(@RequestParam(required = false) String id) {
        if (id == null || id.isBlank()) {
            apiKeys.clear();
            return Result.success("已清除本机保存的全部模型配置", view());
        }
        if (!apiKeys.delete(id.trim())) {
            throw new BusinessException("配置不存在（可能已被删除），刷新页面后重试", 404);
        }
        return Result.success("已删除该配置", view());
    }

    // ── 视图（绝不包含完整 Key） ──────────────────────────────────────────

    private Map<String, Object> view() {
        Map<String, Object> data = new LinkedHashMap<>();
        LlmProfile active = apiKeys.currentProfile();

        data.put("configured", extractor.isConfigured());
        data.put("activeId", active == null ? null : active.id());
        data.put("name", active == null ? null : active.displayName());
        data.put("provider", active == null ? null : active.provider());
        data.put("baseUrl", active == null ? null : active.baseUrl());
        data.put("model", active == null ? null : active.model());
        data.put("masked", apiKeys.masked());
        data.put("source", apiKeys.source());
        data.put("storage", apiKeys.storageDescription());
        data.put("describe", extractor.describe());

        List<Map<String, Object>> profiles = new ArrayList<>();
        String resolvedActiveId = active == null ? null : active.id();
        for (LlmProfile p : apiKeys.profiles()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", p.id());
            item.put("name", p.displayName());
            item.put("provider", p.provider());
            item.put("providerLabel", ProviderDefaults.label(p.provider()));
            item.put("baseUrl", p.baseUrl());
            item.put("model", p.model());
            item.put("masked", ApiKeyProvider.mask(p.apiKey()));
            item.put("active", p.id() != null && p.id().equals(resolvedActiveId));
            profiles.add(item);
        }
        data.put("profiles", profiles);

        data.put("providers", ProviderDefaults.providers());
        Map<String, String> labels = new LinkedHashMap<>();
        for (String pv : ProviderDefaults.providers()) {
            labels.put(pv, ProviderDefaults.label(pv));
        }
        data.put("providerLabels", labels);
        return data;
    }

    private LlmProfile findById(String id) {
        for (LlmProfile p : apiKeys.profiles()) {
            if (id.equals(p.id())) {
                return p;
            }
        }
        return null;
    }

    private static String defaultProvider(String raw) {
        String v = trimToNull(raw);
        return v == null ? "custom" : v.toLowerCase();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
