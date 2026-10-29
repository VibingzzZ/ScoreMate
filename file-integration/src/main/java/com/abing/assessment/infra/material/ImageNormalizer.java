package com.abing.assessment.infra.material;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 图片 → 整形后的一张 JPEG。不再原样透传：手机直出的 4000×3000 照片原样发出去，
 * 模型侧还要自己缩。压到长边 {@link ImageOps#MAX_LONG_EDGE} 后耗时 21s → 6s，小字仍可读。
 */
@Component
public class ImageNormalizer implements MaterialNormalizer {

    @Override
    public boolean supports(String ext, String mime) {
        if (mime != null && mime.startsWith("image/")) {
            return true;
        }
        return ext != null && List.of("png", "jpg", "jpeg", "webp", "gif", "bmp").contains(ext.toLowerCase());
    }

    @Override
    public NormalizedMaterial normalize(InputStream in, String originalName, int maxPages) throws IOException {
        BufferedImage img = ImageIO.read(in);
        if (img == null) {
            throw new IOException("图片无法解码（可能已损坏或不是真正的图片）：" + originalName);
        }
        // 一张图就是一页，maxPages 用不上
        return NormalizedMaterial.single(ImageOps.toModelJpeg(img));
    }
}
