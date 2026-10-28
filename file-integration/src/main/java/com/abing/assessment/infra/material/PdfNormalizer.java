package com.abing.assessment.infra.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF → 每页一张 PNG。
 *
 * <p>视觉模型只读图，所以无论用户上传扫描件还是电子版 PDF，都先归一化。DPI=150 对 A4 证书
 * 足够模型读字，又不会把单页撑得太大。
 *
 * <p><b>只渲染前 {@code maxPages} 页，而不是渲染完再截断。</b>渲染一页 A4 要几十毫秒到几百毫秒，
 * 一份 40 页的扫描件全渲染就是十几秒 + 上百 MB 内存，最后 35 张直接扔掉——用户感受到的
 * 就是「传到一半卡住」。总页数照样返回，用来提示「共 N 页，仅识别前 5 页」。
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
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos); // 统一落 PNG，下游 Extractor 只认 image/png
                pages.add(baos.toByteArray());
            }
            return new NormalizedMaterial(pages, total);
        }
    }
}
