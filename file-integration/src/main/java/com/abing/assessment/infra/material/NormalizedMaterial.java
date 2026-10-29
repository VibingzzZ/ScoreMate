package com.abing.assessment.infra.material;

import java.util.List;

/**
 * 归一化结果：已经渲染好的页面图片 + 材料一共有多少页。
 *
 * <p>为什么要把「总页数」单独带出来：一份 30 页的 PDF 只识别前 5 页，
 * 但页面上必须能告诉用户「共 30 页，仅识别前 5 页」——只给 images 的话，
 * 调用方看到的永远是 5，就没法判断是不是被截断了。
 *
 * @param images     每页一张 PNG，最多 {@code maxPages} 张
 * @param totalPages 材料实际页数（可能大于 images.size()）
 */
public record NormalizedMaterial(List<byte[]> images, int totalPages) {

    /**
     * 归一化后图片的统一格式。所有实现都输出 JPEG（见 {@link ImageOps}），
     * 调用方不要再写死 image/png——那不匹配的 MIME 会让部分网关直接拒收。
     */
    public static final String IMAGE_MIME = "image/jpeg";

    /** 单页材料（图片）的便捷构造 */
    public static NormalizedMaterial single(byte[] image) {
        return new NormalizedMaterial(List.of(image), 1);
    }

    /** 是否因为页数上限而被截断 */
    public boolean truncated() {
        return totalPages > images.size();
    }
}
