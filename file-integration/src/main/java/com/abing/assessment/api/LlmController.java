package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.infra.llm.ApiKeyProvider;
import com.abing.assessment.infra.llm.Extractor;
import com.abing.assessment.infra.llm.LlmProfile;
import com.abing.assessment.infra.llm.ProviderDefaults;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API Key 的填写与测试（多厂商）。
 *
 * <p>三条硬规矩（架构文档 §2.7）：
 * <ol>
 *   <li><b>只写不读</b>：前端提交后拿不到完整 Key，只能拿到掩码</li>
 *   <li><b>先测后留</b>：填完立刻做一次连通性测试，测不通就撤销，不留一个坏 Key</li>
 *   <li><b>不落明文</b>：整个档案（含厂商）加密存进 DPAPI</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    /** 粗筛用：正常 Key 不会短于这个长度，太短基本是没复制全 */
    private static final int MIN_KEY_LENGTH = 10;

    private final ApiKeyProvider apiKeys;
    private final Extractor extractor;

    public LlmController(ApiKeyProvider apiKeys, Extractor extractor) {
        this.apiKeys = apiKeys;
        this.extractor = extractor;
    }

    @GetMapping
    public Result<Map<String, Object>> status() {
        return Result.success(view());
    }

    /**
     * 保存并立即测试。测试失败会撤销保存，避免用户以为配好了、
     * 结果上传证书时才收到 401。
     * 接收 { provider, apiKey, baseUrl?, model? }：baseUrl / model 缺省时取厂商默认值。
     */
    @PostMapping("/key")
    public Result<Map<String, Object>> saveKey(@RequestBody Map<String, String> body) throws IOException {
        if (body == null) {
            throw new BusinessException("请求体为空", 400);
        }
        String provider = body.getOrDefault("provider", "openai");
        String apiKey = body.get("apiKey");
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

        String baseUrl = body.get("baseUrl");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = ProviderDefaults.defaultBaseUrl(provider);
        }
        String model = body.get("model");
        if (model == null || model.isBlank()) {
            model = ProviderDefaults.defaultModel(provider);
        }
        if (baseUrl.isBlank() || model.isBlank()) {
            throw new BusinessException("请填写 baseUrl 和 model，或选择一个已知厂商", 400);
        }

        LlmProfile profile = new LlmProfile(provider, baseUrl.trim(), model.trim(), apiKey);
        apiKeys.save(profile);
        try {
            extractor.ping();
        } catch (RuntimeException e) {
            // 测不通就不留：否则页面显示"已就绪"，用户上传证书才发现不对
            apiKeys.clear();
            throw e;
        }
        return Result.success(view());
    }

    @DeleteMapping("/key")
    public Result<Void> clearKey() {
        apiKeys.clear();
        return Result.success("已清除本机保存的 Key", null);
    }

    private Map<String, Object> view() {
        Map<String, Object> data = new LinkedHashMap<>();
        LlmProfile p = apiKeys.currentProfile();
        data.put("configured", extractor.isConfigured());
        data.put("provider", p == null ? null : p.provider());
        data.put("baseUrl", p == null ? null : p.baseUrl());
        data.put("model", p == null ? null : p.model());
        data.put("masked", apiKeys.masked());
        data.put("source", apiKeys.source());
        data.put("storage", apiKeys.storageDescription());
        data.put("describe", extractor.describe());
        data.put("providers", ProviderDefaults.providers());
        return data;
    }
}
