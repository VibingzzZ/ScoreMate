package com.abing.assessment.infra.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF → 每页一张 JPEG。DPI=150 对 A4 证书足够读字（长边 1754，落在
 * {@link ImageOps#MAX_LONG_EDGE} 之内，不会被二次缩放）。
 *
 * <p><b>渲染时就按 maxPages 收手</b>，不是全渲染再截断：40 页扫描件全渲染要十几秒 + 上百 MB
 * 内存，最后 35 张直接扔掉，用户看到的是「传到一半卡住」。
 */
@Component
public class PdfNormalizer implements MaterialNormalizer {

    private static final float DPI = 150f;

    @Override
    public boolean supports(String ext, String mime) {
        if ("application/pdf".equals(mime)) {
            return true;
        }
        return "pdf".equalsIgnoreCase(ext);
    }

    @Override
    public NormalizedMaterial normalize(InputStream in, String originalName, int maxPages) throws IOException {
        // PDFBox 2.x 的 load(InputStream) 内部会把流读进内存缓冲，普通流即可（无需 reset）
        try (PDDocument doc = PDDocument.load(in)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            int total = doc.getNumberOfPages();
            int limit = Math.min(total, Math.max(1, maxPages));
            List<byte[]> pages = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, DPI);
                // 编码成 JPEG 而不是 PNG：扫描件的 PNG 单页常有 1–2MB，
                // base64 后请求体翻 1.33 倍，上传到模型的耗时比模型读图还长
                pages.add(ImageOps.toModelJpeg(img));
            }
            return new NormalizedMaterial(pages, total);
        }
    }
}
