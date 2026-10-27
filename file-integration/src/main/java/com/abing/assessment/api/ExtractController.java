package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.ExtractionResult;
import com.abing.assessment.infra.llm.Extractor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单份材料抽取接口。M1 阶段先用它验证"视觉识别准不准"，
 * 同时也是后面整条流水线（§4.3）里第 ② 步的真实入口。
 */
@RestController
@RequestMapping("/api")
public class ExtractController {

    /** 单张图片上限。视觉模型对超大图也不友好，压一下反而更准 */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final Extractor extractor;

    public ExtractController(Extractor extractor) {
        this.extractor = extractor;
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> extract(@RequestParam("file") MultipartFile file) throws IOException {
        validate(file);

        String mime = file.getContentType();
        ExtractionResult result = extractor.extract(file.getBytes(), mime);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", file.getOriginalFilename());
        data.put("model", result.model());
        data.put("latencyMs", result.latencyMs());
        data.put("totalTokens", result.totalTokens());
        data.put("fact", result.fact());
        return Result.success(data);
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

        if (mime.equals("application/pdf") || name.endsWith(".pdf")) {
            // 不是技术做不到，是渲染哪一页、多页证书怎么处理都还没设计，先别装作支持
            throw new BusinessException("暂不支持 PDF：请截取证书那一页另存为图片再上传", 415);
        }
        if (!mime.startsWith("image/")) {
            throw new BusinessException("只支持图片（jpg / png / webp）", 415);
        }
    }
}
