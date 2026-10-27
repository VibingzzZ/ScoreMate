package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.ExtractionResult;
import com.abing.assessment.infra.llm.Extractor;
import com.abing.assessment.infra.material.MaterialNormalizer;
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
 * 单份材料抽取接口。M1 阶段先用它验证"视觉识别准不准"，
 * 同时也是后面整条流水线（§4.3）里第 ② 步的真实入口。
 *
 * <p>S1 起：文件先经 {@link MaterialNormalizer} 归一化成图片列表，再逐页送视觉模型。
 * 这样图片/PDF 走同一套逻辑，多页也自然支持（Word 待 S3）。
 */
@RestController
@RequestMapping("/api")
public class ExtractController {

    /** 单张图片上限。视觉模型对超大图也不友好，压一下反而更准 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 单次识别最多处理的页数，避免一份 50 页 PDF 把额度烧光 */
    private static final int MAX_PAGES = 5;

    private final Extractor extractor;
    private final List<MaterialNormalizer> normalizers;

    public ExtractController(Extractor extractor, List<MaterialNormalizer> normalizers) {
        this.extractor = extractor;
        this.normalizers = normalizers;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> extract(@RequestParam("file") MultipartFile file) throws IOException {
        validate(file);

        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String mime = file.getContentType() == null ? "" : file.getContentType();
        MaterialNormalizer normalizer = pickNormalizer(name, mime);

        List<byte[]> images = normalizer.toImages(file.getInputStream(), file.getOriginalFilename());

        List<ExtractedFact> pages = new ArrayList<>();
        int used = Math.min(images.size(), MAX_PAGES);
        for (int i = 0; i < used; i++) {
            ExtractionResult r = extractor.extract(images.get(i), "image/png");
            pages.add(r.fact());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", file.getOriginalFilename());
        data.put("pageCount", images.size());
        data.put("pagesUsed", used);
        if (images.size() > MAX_PAGES) {
            data.put("note", "材料共 " + images.size() + " 页，仅识别前 " + MAX_PAGES + " 页");
        }
        data.put("pages", pages);                                  // 多页结果（单页时长度=1）
        data.put("fact", pages.isEmpty() ? null : pages.get(0));   // 兼容旧前端
        return Result.success(data);
    }

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
            throw new BusinessException("图片超过 10MB，请先压缩到 2MB 以内再试", 413);
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String mime = file.getContentType() == null ? "" : file.getContentType();

        // 归一化层（图片 / PDF / Word）都已支持，其余类型拒绝。
        boolean looksSupported = mime.startsWith("image/")
                || mime.equals("application/pdf")
                || name.endsWith(".pdf")
                || name.endsWith(".doc") || name.endsWith(".docx");
        if (!looksSupported) {
            throw new BusinessException("只支持图片（jpg / png / webp）、PDF 或 Word（doc / docx）", 415);
        }
    }
}
