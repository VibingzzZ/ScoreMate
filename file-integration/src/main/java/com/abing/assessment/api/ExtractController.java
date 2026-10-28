package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.ExtractionResult;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.infra.llm.Extractor;
import com.abing.assessment.infra.material.MaterialNormalizer;
import com.abing.assessment.infra.material.NormalizedMaterial;
import com.abing.assessment.infra.profile.OwnerProfileStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单份材料抽取接口。
 *
 * <p>设计取舍（2026-10-28 定）：<b>每张图只调一次模型</b>，不做二次复核。
 * 准确性靠两件事兜：
 * <ol>
 *   <li>prompt 一次读准（见 configs/prompt/extract-v1.txt 的"读字要求"）</li>
 *   <li>抽完把<b>歧义项</b>抛给用户确认，姓名等身份字段统一采用用户填的本人信息</li>
 * </ol>
 * 这样批量扫描时既省时间也省钱。
 */
@RestController
@RequestMapping("/api")
public class ExtractController {

    /** 单张图片上限。视觉模型对超大图也不友好，压一下反而更准 */
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 单次识别最多处理的页数，避免一份 50 页 PDF 把额度烧光 */
    private static final int MAX_PAGES = 5;

    /** 低于这个置信度就提示用户逐项核对（实测读错姓名的那次是 0.9，所以阈值不能设太低） */
    private static final double LOW_CONFIDENCE = 0.85;

    private final Extractor extractor;
    private final List<MaterialNormalizer> normalizers;
    private final OwnerProfileStore profileStore;

    public ExtractController(Extractor extractor, List<MaterialNormalizer> normalizers,
                             OwnerProfileStore profileStore) {
        this.extractor = extractor;
        this.normalizers = normalizers;
        this.profileStore = profileStore;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> extract(@RequestParam("file") MultipartFile file) throws IOException {
        validate(file);

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String mime = file.getContentType() == null ? "" : file.getContentType();
        MaterialNormalizer normalizer = pickNormalizer(name, mime);

        // 页数上限在这里就传下去：PDF 只渲染用得上的那几页，不先把整本渲染完再丢
        NormalizedMaterial material =
                normalizer.normalize(file.getInputStream(), file.getOriginalFilename(), MAX_PAGES);
        List<byte[]> images = material.images();
        OwnerProfile owner = profileStore.load();

        List<Map<String, Object>> pages = new ArrayList<>();
        List<ExtractedFact> facts = new ArrayList<>();
        List<Map<String, Object>> allIssues = new ArrayList<>();
        String model = null;
        long totalLatency = 0L;
        int totalTokens = 0;
        boolean tokensKnown = false;

        int used = images.size();
        for (int i = 0; i < used; i++) {
            ExtractionResult r = extractor.extract(images.get(i), "image/png");

            // 统一身份字段 + 收集歧义项
            PageOutcome outcome = normalize(r.fact(), owner, i, file.getOriginalFilename());
            facts.add(outcome.fact());
            allIssues.addAll(outcome.issues());

            Map<String, Object> page = new LinkedHashMap<>();
            page.put("pageNo", i + 1);
            page.put("fact", outcome.fact());
            page.put("model", r.model());
            page.put("latencyMs", r.latencyMs());
            page.put("tokens", r.totalTokens());
            page.put("issues", outcome.issues());
            pages.add(page);

            model = r.model();
            totalLatency += r.latencyMs();
            if (r.totalTokens() != null) {
                totalTokens += r.totalTokens();
                tokensKnown = true;
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", file.getOriginalFilename());
        data.put("pageCount", material.totalPages());
        data.put("pagesUsed", used);
        if (material.truncated()) {
            data.put("note", "共 " + material.totalPages() + " 页，仅识别前 " + used + " 页（其余请手动处理）");
        }
        data.put("pages", pages);
        data.put("fact", facts.isEmpty() ? null : facts.get(0));   // 兼容旧前端
        data.put("model", model);
        data.put("latencyMs", totalLatency);
        data.put("totalTokens", tokensKnown ? totalTokens : null);
        // 所有页的歧义项汇总，页面用它渲染"待确认清单"
        data.put("issues", allIssues);
        data.put("ownerName", owner.name());
        return Result.success(data);
    }

    // ── 身份统一 & 歧义收集 ───────────────────────────────────────────────

    private record PageOutcome(ExtractedFact fact, List<Map<String, Object>> issues) {}

    /**
     * 把模型结果收敛成"可直接用于算分的事实"：
     * 姓名统一采用本人信息，其余字段只标记问题、不擅自改。
     */
    private PageOutcome normalize(ExtractedFact raw, OwnerProfile owner, int pageIndex, String fileName) {
        List<Map<String, Object>> issues = new ArrayList<>();
        ExtractedFact fact = raw;

        boolean isCertificate = raw.docType() != null && !"其他".equals(raw.docType().trim());
        if (!isCertificate) {
            // 不是证明材料：本来就没姓名没颁发单位，不是错误，不产生任何提醒
            return new PageOutcome(raw, issues);
        }

        String detected = trimToNull(raw.personName());
        if (owner.hasName()) {
            if (detected == null) {
                fact = raw.withPersonName(owner.name());
                issues.add(issue(pageIndex, fileName, "person_name", "姓名", null, "auto_filled",
                        "证书上没有读到姓名，已用你填写的本人姓名「" + owner.name() + "」"));
            } else if (!detected.equals(owner.name())) {
                fact = raw.withPersonName(owner.name());
                issues.add(issue(pageIndex, fileName, "person_name", "姓名", detected, "name_mismatch",
                        "识别到「" + detected + "」，与本人姓名「" + owner.name()
                                + "」不一致：可能是他人材料，或模型读错了字"));
            }
        } else if (detected == null) {
            issues.add(issue(pageIndex, fileName, "person_name", "姓名", null, "missing",
                    "没识别到姓名，且还没填写本人信息"));
        }

        addMissing(issues, pageIndex, fileName, "issuer", "颁发单位", raw.issuer());
        addMissing(issues, pageIndex, fileName, "title", "名称", raw.title());
        addMissing(issues, pageIndex, fileName, "date", "签发日期", raw.date());
        if ("聘书".equals(raw.docType())) {
            addMissing(issues, pageIndex, fileName, "period_start", "任期起", raw.periodStart());
            addMissing(issues, pageIndex, fileName, "period_end", "任期止", raw.periodEnd());
        }

        Double conf = raw.confidence();
        if (conf != null && conf < LOW_CONFIDENCE) {
            issues.add(issue(pageIndex, fileName, "confidence", "整体可信度", String.valueOf(conf),
                    "low_confidence", "模型对这份材料不太确定（" + conf + "），建议逐项核对"));
        }
        return new PageOutcome(fact, issues);
    }

    private void addMissing(List<Map<String, Object>> issues, int pageIndex, String fileName,
                            String field, String label, String value) {
        if (trimToNull(value) == null) {
            issues.add(issue(pageIndex, fileName, field, label, null, "missing",
                    "证书上应该有「" + label + "」但没读到，请手动补上或确认真没有"));
        }
    }

    private Map<String, Object> issue(int pageIndex, String fileName, String field, String label,
                                      String value, String reason, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("page", pageIndex + 1);
        m.put("fileName", fileName);
        m.put("field", field);
        m.put("label", label);
        m.put("value", value);
        m.put("reason", reason);
        m.put("hint", hint);
        return m;
    }

    // ── 文件校验 ─────────────────────────────────────────────────────────

    private MaterialNormalizer pickNormalizer(String name, String mime) {
        for (MaterialNormalizer n : normalizers) {
            if (n.supports(name, mime)) {
                return n;
            }
        }
        throw new BusinessException("不支持的文件类型（当前仅支持图片和 PDF）", 415);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("没有收到文件", 400);
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessException(
                    "文件超过 20MB，这份不自动识别。请拆页或压缩后重传；材料本身可以留着，导出后手工归档", 413);
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String mime = file.getContentType() == null ? "" : file.getContentType();

        boolean looksSupported = mime.startsWith("image/")
                || mime.equals("application/pdf")
                || name.endsWith(".pdf")
                || name.endsWith(".doc") || name.endsWith(".docx");
        if (!looksSupported) {
            throw new BusinessException("只支持图片（jpg / png / webp）、PDF 或 Word（doc / docx）", 415);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
