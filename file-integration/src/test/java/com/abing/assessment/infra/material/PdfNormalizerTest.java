package com.abing.assessment.infra.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 归一化的页数行为。
 *
 * <p>钉住一条容易被"优化"掉的规则：<b>maxPages 是在渲染前收手，不是在渲染后截断</b>。
 * 一份 40 页扫描件如果全渲染再丢 35 张，用户看到的是传完卡住十几秒——这条测试红了
 * 就说明有人把循环上限改回 total 了。
 */
class PdfNormalizerTest {

    private final PdfNormalizer normalizer = new PdfNormalizer();

    @Test
    void 只渲染到上限但返回真实总页数() throws IOException {
        byte[] pdf = blankPdf(8);

        NormalizedMaterial m = normalizer.normalize(new ByteArrayInputStream(pdf), "x.pdf", 5);

        assertEquals(5, m.images().size(), "只该渲染前 5 页");
        assertEquals(8, m.totalPages(), "总页数要如实报出来，否则页面提示不了「仅识别前 5 页」");
        assertTrue(m.truncated());
    }

    @Test
    void 页数没超上限时不标记截断() throws IOException {
        byte[] pdf = blankPdf(3);

        NormalizedMaterial m = normalizer.normalize(new ByteArrayInputStream(pdf), "x.pdf", 5);

        assertEquals(3, m.images().size());
        assertEquals(3, m.totalPages());
        assertFalse(m.truncated());
    }

    @Test
    void 单页图片按一页处理() throws IOException {
        NormalizedMaterial m = new ImageNormalizer()
                .normalize(new ByteArrayInputStream(jpegOf(600, 400)), "a.jpg", 5);

        assertEquals(1, m.images().size());
        assertEquals(1, m.totalPages());
        assertFalse(m.truncated());
    }

    /**
     * 手机直出的大图必须被压到长边上限内再送给模型。
     *
     * <p>不压的代价是实测过的：同一张证书原图 21 秒、压到长边 1600 只要 6 秒，
     * 而小字依然可读。这条红了说明有人把 {@link ImageOps#MAX_LONG_EDGE} 绕过了。
     */
    @Test
    void 大图压到长边上限以内() throws IOException {
        NormalizedMaterial m = new ImageNormalizer()
                .normalize(new ByteArrayInputStream(jpegOf(3000, 2000)), "big.jpg", 5);

        BufferedImage out = ImageIO.read(new ByteArrayInputStream(m.images().get(0)));
        assertEquals(ImageOps.MAX_LONG_EDGE, Math.max(out.getWidth(), out.getHeight()),
                "长边应正好压到上限");
        // 等比缩放：3000×2000 → 1800×1200
        assertEquals(1200, Math.min(out.getWidth(), out.getHeight()), "不能改变宽高比");
    }

    @Test
    void 输出统一是JPEG() throws IOException {
        NormalizedMaterial m = new ImageNormalizer()
                .normalize(new ByteArrayInputStream(jpegOf(800, 600)), "a.jpg", 5);

        byte[] head = m.images().get(0);
        // JPEG 的文件头是 FF D8 FF。下游按 NormalizedMaterial.IMAGE_MIME 声明类型，
        // 内容与声明必须一致，否则部分网关直接拒收。
        assertEquals((byte) 0xFF, head[0]);
        assertEquals((byte) 0xD8, head[1]);
        assertEquals((byte) 0xFF, head[2]);
    }

    /** 造一张指定尺寸的照片（带噪点，避免纯色被 JPEG 压成几百字节看不出效果） */
    private byte[] jpegOf(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(12, h / 20)));
            g.drawString("certificate " + w + "x" + h, w / 10, h / 2);
        } finally {
            g.dispose();
        }
        return ImageOps.encodeJpeg(ImageOps.toRgb(img));
    }

    /** 造一个 n 页的空白 PDF，每页写一行字（纯空白页 PDFBox 也能渲染，但写字更接近真实） */
    private byte[] blankPdf(int n) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < n; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(PDType1Font.HELVETICA, 12);
                    cs.newLineAtOffset(60, 700);
                    cs.showText("page " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
