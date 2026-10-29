package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.infra.config.RuleConfigWriter;
import com.abing.assessment.infra.config.RuleDraft;
import com.abing.assessment.infra.config.RuleImporter;
import com.abing.assessment.infra.config.YamlRuleLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分细则导入：换年份时传一份新细则即可，不改代码。
 *
 * <p>两个接口分开，<b>不是上传即生效</b>：{@code /import} 只返回草稿（不动配置），
 * {@code /save} 在用户核对（改分值、删条目）后才写 YAML。多这一步核对，换来的是
 * 一份能对账的配置——二维表读错一格就是整班算错分，且当场看不出来。
 */
@RestController
@RequestMapping("/api/rules")
public class RuleImportController {

    private static final Logger log = LoggerFactory.getLogger(RuleImportController.class);

    /** 细则 PDF 一般几百 KB，超过这个大小基本是传错了文件 */
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private final RuleImporter importer;
    private final RuleConfigWriter writer;
    private final YamlRuleLoader loader;

    public RuleImportController(RuleImporter importer, RuleConfigWriter writer, YamlRuleLoader loader) {
        this.importer = importer;
        this.writer = writer;
        this.loader = loader;
    }

    /** 当前生效的规则概况 + 导入是否可用，页面进来先问一次 */
    @GetMapping("/current")
    public Result<Map<String, Object>> current() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ready", importer.ready());
        Path file = loader.primaryFile();
        data.put("file", file == null ? null : file.getFileName().toString());

        RuleConfig cfg = loader.rules().isEmpty() ? null : loader.rules().get(0);
        if (cfg != null) {
            data.put("school", cfg.school());
            data.put("college", cfg.college());
            data.put("ruleVersion", cfg.ruleVersion());
            data.put("sourceFile", cfg.sourceFile());
            data.put("groupCount", cfg.ruleGroups() == null ? 0 : cfg.ruleGroups().size());
            data.put("itemCount", cfg.ruleGroups() == null ? 0
                    : cfg.ruleGroups().stream().mapToInt(g -> g.items().size()).sum());
        }
        data.put("problems", loader.problems());
        return Result.success(data);
    }

    /** 第一步：识别。返回草稿，此时配置一点没变 */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("没有收到文件", 400);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException("细则文件超过 30MB，请先用 PDF 压缩工具处理", 413);
        }
        String name = file.getOriginalFilename() == null ? "细则.pdf" : file.getOriginalFilename();
        String mime = file.getContentType() == null ? "" : file.getContentType();

        RuleDraft draft;
        try {
            draft = importer.importFrom(file.getBytes(), name, mime);
        } catch (IOException e) {
            throw new BusinessException("读取细则失败：" + e.getMessage(), 500);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceFile", draft.sourceFile());
        data.put("groups", draft.groups());
        data.put("problems", draft.problems());
        data.put("totalPages", draft.totalPages());
        data.put("pagesRead", draft.pagesRead());
        data.put("itemCount", draft.itemCount());
        data.put("totalTokens", draft.totalTokens());
        data.put("unscored", draft.unscoredItems().size());
        // 「条件相同、分值不同」的条目：多半是表里还有一列角色没被区分。
        // 必须在核对阶段就让用户看见，而不是等他算完分才发现某个人多给了 2 分。
        data.put("conflicts", draft.matchConflicts());
        return Result.success(data);
    }

    /** 第二步：核对完保存。这里会覆盖当前 YAML 并备份旧版 */
    @PostMapping("/save")
    public Result<Map<String, Object>> save(@RequestBody SaveRequest request) {
        if (request == null || request.groups() == null || request.groups().isEmpty()) {
            throw new BusinessException("没有可保存的规则组", 400);
        }

        List<RuleDraft.Group> groups = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (RuleDraft.Group g : request.groups()) {
            if (g.items() == null || g.items().isEmpty()) {
                continue;
            }
            for (RuleDraft.Item it : g.items()) {
                if (it.score() == null) {
                    invalid.add(g.name() + " · " + it.title());
                }
            }
            groups.add(g);
        }
        if (!invalid.isEmpty()) {
            throw new BusinessException("有 " + invalid.size() + " 条没有分值，请补上或删掉："
                    + String.join("、", invalid.subList(0, Math.min(5, invalid.size()))), 400);
        }
        if (groups.isEmpty()) {
            throw new BusinessException("所有规则组都是空的，拒绝了这次保存", 400);
        }

        RuleDraft draft = new RuleDraft(request.sourceFile(), groups, List.of(), 0, 0, 0);
        Path saved;
        try {
            saved = writer.save(draft, request.college());
        } catch (IOException e) {
            throw new BusinessException("写配置失败：" + e.getMessage(), 500);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", saved.getFileName().toString());
        data.put("groupCount", loader.rules().isEmpty() ? 0 : loader.rules().get(0).ruleGroups().size());
        data.put("itemCount", loader.rules().isEmpty() ? 0
                : loader.rules().get(0).ruleGroups().stream().mapToInt(g -> g.items().size()).sum());
        data.put("problems", loader.problems());
        log.info("细则导入完成：{} 组 / {} 条 → {}",
                groups.size(), groups.stream().mapToInt(g -> g.items().size()).sum(), saved);
        return Result.success(data);
    }

    /**
     * 保存请求体。前端把（可能改过分值的）草稿原样回传，
     * 所以字段与 {@link RuleDraft} 保持一致，不做二次映射。
     */
    public record SaveRequest(String college, String sourceFile, List<RuleDraft.Group> groups) {
    }
}
