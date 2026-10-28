package com.abing.assessment.infra.material;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

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
                .normalize(new ByteArrayInputStream(new byte[]{1, 2, 3}), "a.jpg", 5);

        assertEquals(1, m.images().size());
        assertEquals(1, m.totalPages());
        assertFalse(m.truncated());
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
