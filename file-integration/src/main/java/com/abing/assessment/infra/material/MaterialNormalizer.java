package com.abing.assessment.infra.material;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 把任意材料归一化成「图片列表」，让下游视觉模型只看图、不关心原始格式。
 *
 * <p>S1/S3 落地：图片原样透传、PDF 与 Word 都归一化为每页一张 PNG；Word 借 LibreOffice 转 PDF。
 */
public interface MaterialNormalizer {

    boolean supports(String ext, String mime);

    /**
     * 一页一张 PNG，顺序与材料页码一致。
     * @param originalName 原始文件名（含扩展名），用于按扩展名判断格式（如 Word 需区分 doc/docx）
     */
    List<byte[]> toImages(InputStream in, String originalName) throws IOException;
}
