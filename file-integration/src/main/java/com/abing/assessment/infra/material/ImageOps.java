package com.abing.assessment.infra.material;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 送模型前的统一整形：限长边 + 转 JPEG。
 *
 * <p>目标是<b>压时间不是压 token</b>：2.18MP 的图本身只占约 250 token，耗时却在缩放预处理上——
 * 同一张证书 4000px 原图 21s，压到 1600px 只要 6s。
 *
 * <p>取 1800 是因为 A4 按 150dpi 渲染正好长边 1754，PDF 路径不会被二次缩放；手机照片多在 3000+，
 * 压到 1800 已能省掉大部分预处理，同时保住小字清晰度。
 */
final class ImageOps {

    /** 送模型的长边上限（像素）。超过就等比缩小 */
    static final int MAX_LONG_EDGE = 1800;

    /** JPEG 质量：92 对证书上的小字（印章、落款）仍可辨认，体积只有无损 PNG 的三到四成 */
    private static final float JPEG_QUALITY = 0.92f;

    /** 统一送给模型的 MIME，ExtractController 依赖这个常量 */
    static final String MIME = "image/jpeg";

    private ImageOps() {
    }

    /** 缩放（只在超过上限时）+ 编码成 JPEG */
    static byte[] toModelJpeg(BufferedImage img) throws IOException {
        return encodeJpeg(toRgb(shrink(img)));
    }

    /** 等比缩到长边不超过 {@link #MAX_LONG_EDGE}；本来就更小则原样返回 */
    static BufferedImage shrink(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= MAX_LONG_EDGE) {
            return img;
        }
        double scale = (double) MAX_LONG_EDGE / longEdge;
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));

        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            // 证书是文字为主，双线性够用且比最近邻清晰；BICUBIC 在大比例缩小下容易出锯齿
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * 转成不带 alpha 的 RGB。JPEG 不支持透明，PNG 截图直接编码会得到黑底，
     * 文字压在黑底上模型很难读，所以先铺一层白。
     */
    static BufferedImage toRgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 明确指定质量编码，ImageIO.write 的默认质量（0.75）对小字偏狠 */
    static byte[] encodeJpeg(BufferedImage img) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
