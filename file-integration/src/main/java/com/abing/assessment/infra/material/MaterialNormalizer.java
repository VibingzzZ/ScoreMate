package com.abing.assessment.infra.material;

import com.abing.assessment.common.BusinessException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 把任意材料归一化成「图片列表」，让下游视觉模型只看图、不关心原始格式。
 *
 * <p>图片会被整形（限长边 + 转 JPEG）、PDF 与 Word 都按页渲染；Word 借 LibreOffice 转 PDF。
 * 统一输出见 {@link NormalizedMaterial#IMAGE_MIME}。
 */
public interface MaterialNormalizer {

    boolean supports(String ext, String mime);

    /**
     * 归一化材料。
     *
     * <p><b>{@code maxPages} 是渲染上限，不是在结果上截断。</b>一份 40 页的 PDF
     * 如果先全渲染再丢掉 35 张，等于为不要的东西付了 8 倍 CPU 和内存，
     * 用户看到的就是"传完半天没反应"。实现方必须在渲染前就用这个值收手。
     *
     * @param originalName 原始文件名（含扩展名），用于按扩展名判断格式（如 Word 需区分 doc/docx）
     * @param maxPages     最多渲染多少页，至少 1
     */
    NormalizedMaterial normalize(InputStream in, String originalName, int maxPages) throws IOException;

    /**
     * 按文件名/MIME 挑一个能处理的归一化器。
     *
     * <p>放在接口上是为了让「材料识别」和「细则导入」用同一套挑选逻辑——两处各写一份的话，
     * 将来加一种格式（比如 Excel）很容易只改到一处，另一处静默用错实现。
     */
    static MaterialNormalizer pick(List<MaterialNormalizer> all, String name, String mime) {
        for (MaterialNormalizer n : all) {
            if (n.supports(name, mime)) {
                return n;
            }
        }
        throw new BusinessException("不支持的文件类型（当前仅支持图片、PDF 和 Word）", 415);
    }
}
