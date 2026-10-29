package com.abing.assessment.infra.material;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 图片 → 统一整形后的一张 JPEG。
 *
 * <p><b>不再原样透传</b>：用户手机直出的是 4000×3000、三五 MB 的照片，原样发给模型
 * 既慢（模型侧要自己缩）又没必要。统一压到长边 {@link ImageOps#MAX_LONG_EDGE}，
 * 手机照片的耗时从 21 秒降到 6 秒，证书小字仍然可读。
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
