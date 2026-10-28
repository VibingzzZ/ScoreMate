package com.abing.assessment.infra.material;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 图片原样透传，保持和原来「只收图片」路径完全等价。 */
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
        // 一张图就是一页，maxPages 用不上
        return NormalizedMaterial.single(in.readAllBytes());
    }
}
