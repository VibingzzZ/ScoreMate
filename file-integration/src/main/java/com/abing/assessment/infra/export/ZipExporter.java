package com.abing.assessment.infra.export;

import com.abing.assessment.domain.export.PackPlan;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.ScoreReport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把 {@link PackPlan} 落成 zip 字节数组。<b>只做搬运</b>：结构、命名、排序全在
 * {@code PackPlanner} 定好，这里一"顺手调整"就会出现单测通过但实际包不对的分裂。
 *
 * <p>必须用 UTF-8 构造 ZipOutputStream（Java 会置 EFS 标志），否则 Windows 解出中文名乱码。
 */
public final class ZipExporter {

    private ZipExporter() {
    }

    /**
     * @param originals 材料序号 → 原件字节（图片 / PDF / Word 原样放进去，不做二次压缩）
     * @return zip 的完整字节
     */
    public static byte[] build(PackPlan plan, ScoreReport report, OwnerProfile owner,
                               Map<Integer, byte[]> originals) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024 * 256);

        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (PackPlan.Folder folder : plan.folders()) {
                // 空文件夹也要留条目，否则解压出来少一个育，班委会问
                if (folder.items().isEmpty()) {
                    zip.putNextEntry(new ZipEntry(folder.name() + "/"));
                    zip.closeEntry();
                    continue;
                }
                for (PackPlan.Item item : folder.items()) {
                    byte[] data = originals.get(item.materialIndex());
                    if (data == null || data.length == 0) {
                        continue;   // controller 已经校验过数量，这里只是防御
                    }
                    zip.putNextEntry(new ZipEntry(folder.name() + "/" + item.newName()));
                    zip.write(data);
                    zip.closeEntry();
                }
            }

            zip.putNextEntry(new ZipEntry(FormWriter.FILE_NAME));
            FormWriter.write(zip, plan, report, owner);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
