package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.export.PackPlan;
import com.abing.assessment.domain.export.PackPlanner;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import com.abing.assessment.domain.rules.ScoreEngine;
import com.abing.assessment.infra.config.YamlRuleLoader;
import com.abing.assessment.infra.export.OutputStore;
import com.abing.assessment.infra.export.ZipExporter;
import com.abing.assessment.infra.profile.OwnerProfileStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出接口：算分结果 → 学院要求的 zip 包。
 *
 * <p>分两步，刻意不让用户"点一下就下载一个不知道里面是什么的压缩包"：
 * <ol>
 *   <li>{@code POST /api/export/preview} 只算结构：哪份材料进了哪个育、会叫什么名字、哪些没进包</li>
 *   <li>{@code POST /api/export} 收原件 + 同一个算分入参，<b>在服务端重算一次</b>再打包</li>
 * </ol>
 * 重算而不是信任前端传来的结果，是为了保证"包里的分数"和"评定表里的分数"必然一致——
 * 前端只要有一次状态不同步，交上去的包就是错的，而这种错用户自己看不出来。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final YamlRuleLoader ruleLoader;
    private final OwnerProfileStore profileStore;
    private final OutputStore outputStore;

    public ExportController(YamlRuleLoader ruleLoader, OwnerProfileStore profileStore, OutputStore outputStore) {
        this.ruleLoader = ruleLoader;
        this.profileStore = profileStore;
        this.outputStore = outputStore;
    }

    /** 结构预览：不产生文件，只回答"包会长什么样" */
    @PostMapping("/preview")
    public Result<Map<String, Object>> preview(@RequestBody ScoreInput body) {
        Prepared p = prepare(body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("zipName", p.plan().zipName());
        data.put("academicYear", p.plan().academicYear());
        data.put("folders", p.plan().folders());
        data.put("skipped", p.plan().skipped());
        data.put("warnings", p.plan().warnings());
        data.put("packedCount", p.plan().packedCount());
        data.put("formFileName", com.abing.assessment.infra.export.FormWriter.FILE_NAME);
        data.put("outputDir", outputStore.describeLocation());
        data.put("overall", p.report().overall());
        data.put("status", p.report().status());
        return Result.success(data);
    }

    /**
     * 真正导出。
     *
     * @param requestJson 与 {@code /api/score} 完全相同的入参（JSON 字符串，multipart 里的一个字段）
     * @param sourcesJson 每份材料来自第几个原件，如 {@code [0,0,1]}——
     *                    一份 3 页 PDF 在算分时是 3 份材料，打包时却只有 1 个原件，必须显式给映射。
     *                    不传则按 1:1 处理（全是图片时就是这种情况）
     * @param files       材料原件，顺序与 sources 里的下标一致
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> export(@RequestParam("request") String requestJson,
                                         @RequestParam(value = "sources", required = false) String sourcesJson,
                                         @RequestParam(value = "files", required = false) List<MultipartFile> files)
            throws IOException {
        ScoreInput body = readBody(requestJson);
        Prepared p = prepare(body);

        if (files == null || files.isEmpty()) {
            throw new BusinessException("没有收到材料原件，无法打包", 400);
        }
        int materialCount = body.materials().size();
        int[] sourceOf = readSources(sourcesJson, materialCount, files.size());

        Map<Integer, byte[]> allBytes = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            byte[] bytes = files.get(i).getBytes();
            if (bytes.length == 0) {
                throw new BusinessException("第 " + (i + 1) + " 份材料是空文件，请重新上传", 400);
            }
            allBytes.put(i, bytes);
        }

        Map<Integer, byte[]> originals = new LinkedHashMap<>();
        for (int m = 0; m < materialCount; m++) {
            originals.put(m, allBytes.get(sourceOf[m]));
        }

        byte[] zip = ZipExporter.build(p.plan(), p.report(), p.owner(), originals);
        Path saved = outputStore.save(p.plan().zipName(), zip);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(p.plan().zipName(), StandardCharsets.UTF_8).build());
        headers.setContentLength(zip.length);
        // 自定义头里不能直接放中文，URL 编码后由前端解码显示"已存到哪"
        headers.set("X-Export-Name", encode(p.plan().zipName()));
        headers.set("X-Export-Path", encode(saved == null ? "" : saved.toString()));
        log.info("导出完成：{}（{} KB，{} 份材料）", p.plan().zipName(), zip.length / 1024, originals.size());
        return ResponseEntity.ok().headers(headers).body(zip);
    }

    // ── 共用装配 ───────────────────────────────────────────────────────

    private record Prepared(ScoreReport report, PackPlan plan, OwnerProfile owner) {
    }

    /** 校验 → 重算 → 出计划。两个接口走同一条路，避免"预览和实际打包不一致" */
    private Prepared prepare(ScoreInput body) {
        if (body == null) {
            throw new BusinessException("请求体为空", 400);
        }
        if (body.academicYear() == null || body.academicYear().isBlank()) {
            throw new BusinessException("没指定评测学年，先在算分面板里选一个", 400);
        }
        if (body.materials() == null || body.materials().isEmpty()) {
            throw new BusinessException("还没有材料，识别完再加分算一次", 400);
        }

        OwnerProfile owner = profileStore.load().normalized();
        if (!owner.hasName()) {
            throw new BusinessException("请先在「我的信息」里填写姓名——压缩包按「学号_姓名」命名", 400);
        }
        if (owner.studentId() == null || owner.studentId().isBlank()) {
            throw new BusinessException("请先在「我的信息」里填写学号——压缩包按「学号_姓名」命名", 400);
        }

        RuleConfig cfg = activeRule();
        ScoreReport report = ScoreEngine.evaluate(body, cfg);
        PackPlan plan = PackPlanner.plan(report, body.materials(), owner);
        return new Prepared(report, plan, owner);
    }

    private ScoreInput readBody(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("请求体为空", 400);
        }
        try {
            return MAPPER.readValue(json, ScoreInput.class);
        } catch (Exception e) {
            throw new BusinessException("算分入参解析失败：" + e.getMessage(), 400);
        }
    }

    /** 材料 → 原件的映射。没传就是 1:1，传了就得对得上，宁可直接报错也不猜 */
    private int[] readSources(String json, int materialCount, int fileCount) {
        int[] sourceOf = new int[materialCount];
        if (json == null || json.isBlank()) {
            if (fileCount != materialCount) {
                throw new BusinessException("收到 " + fileCount + " 份原件但材料清单有 " + materialCount
                        + " 份，缺少材料与原件对应关系，请刷新页面重新上传", 400);
            }
            for (int i = 0; i < materialCount; i++) {
                sourceOf[i] = i;
            }
            return sourceOf;
        }
        try {
            var node = MAPPER.readTree(json);
            if (!node.isArray() || node.size() != materialCount) {
                throw new BusinessException("材料与原件对应关系对不上，请刷新页面重新上传", 400);
            }
            for (int i = 0; i < materialCount; i++) {
                int v = node.get(i).asInt(-1);
                if (v < 0 || v >= fileCount) {
                    throw new BusinessException("第 " + (i + 1) + " 份材料找不到对应的原件", 400);
                }
                sourceOf[i] = v;
            }
            return sourceOf;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("材料与原件对应关系解析失败：" + e.getMessage(), 400);
        }
    }

    private RuleConfig activeRule() {
        List<RuleConfig> rules = ruleLoader.rules();
        if (rules.isEmpty()) {
            throw new BusinessException("还没有加载到任何规则配置：请确认 configs/ 下有今年份的细则 YAML", 409);
        }
        return rules.get(0);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
