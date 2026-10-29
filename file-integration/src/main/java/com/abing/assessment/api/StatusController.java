package com.abing.assessment.api;

import com.abing.assessment.infra.config.YamlRuleLoader;
import com.abing.assessment.infra.llm.ApiKeyProvider;
import com.abing.assessment.infra.llm.Extractor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行状态接口：只暴露加载了哪些规则、配置目录、有什么问题，不返回配置内容本身
 * （以后可能带出学号等个人信息）。
 */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final YamlRuleLoader ruleLoader;
    private final Extractor extractor;
    private final ApiKeyProvider apiKeys;

    public StatusController(YamlRuleLoader ruleLoader, Extractor extractor, ApiKeyProvider apiKeys) {
        this.ruleLoader = ruleLoader;
        this.extractor = extractor;
        this.apiKeys = apiKeys;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "assessment");
        body.put("version", "0.1.0");

        Path dir = ruleLoader.configDir();
        body.put("configDir", dir == null ? null : dir.toString());

        List<Map<String, Object>> rules = ruleLoader.rules().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("school", r.school());
            m.put("college", r.college());
            m.put("ruleVersion", r.ruleVersion());
            m.put("file", r.file().getFileName().toString());
            return m;
        }).toList();
        body.put("rules", rules);
        body.put("problems", ruleLoader.problems());

        // 模型配置状态：只暴露"配好没有""用的哪个模型""Key 掩码"，绝不返回完整 Key
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("configured", extractor.isConfigured());
        llm.put("describe", extractor.describe());
        llm.put("masked", apiKeys.masked());
        llm.put("source", apiKeys.source());
        var activeProfile = apiKeys.currentProfile();
        llm.put("name", activeProfile == null ? null : activeProfile.displayName());
        llm.put("model", activeProfile == null ? null : activeProfile.model());
        llm.put("profileCount", apiKeys.profiles().size());
        body.put("llm", llm);

        return body;
    }
}
