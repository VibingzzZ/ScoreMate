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
 * 足够模型读字，又不会把单页撑得太大。多页成本由调用方 cap（见 ExtractController）。
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
    public List<byte[]> toImages(InputStream in, String originalName) throws IOException {
        // PDFBox 2.x 的 load(InputStream) 内部会把流读进内存缓冲，普通流即可（无需 reset）
        try (PDDocument doc = PDDocument.load(in)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            List<byte[]> pages = new ArrayList<>();
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, DPI);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos); // 统一落 PNG，下游 Extractor 只认 image/png
                pages.add(baos.toByteArray());
            }
            return pages;
        }
    }
}
