package com.abing.assessment.infra.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 导出的 zip 在本机留一份副本。
 *
 * <p>浏览器下载走的是"下载目录"，用户经常找不到；而且如果下载失败，
 * 手里就什么都没有了。所以每次导出都往 {@code %LOCALAPPDATA%\Assessment\output}
 * 写一份，页面上把路径告诉用户。
 *
 * <p>只留业务产物，<b>不含任何配置或 Key</b>（架构文档要求 2：Key 的四个出口全部排除）。
 */
@Component
public class OutputStore {

    private static final Logger log = LoggerFactory.getLogger(OutputStore.class);

    private final Path dir;

    public OutputStore() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData == null || localAppData.isBlank())
                ? Paths.get(System.getProperty("user.home"), ".assessment")
                : Paths.get(localAppData, "Assessment");
        this.dir = base.resolve("output");
    }

    /** @return 实际写入的路径；写失败返回 null（导出本身不算失败，浏览器照样能下载） */
    public Path save(String fileName, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(fileName);
            // 同名覆盖：同一个人反复导出，留最新一份即可，不要攒一堆 (1)(2)(3)
            Path tmp = dir.resolve(fileName + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("导出副本已保存：{}（{} KB）", target, bytes.length / 1024);
            return target;
        } catch (IOException e) {
            log.warn("导出副本保存失败（不影响浏览器下载）：{}", e.toString());
            return null;
        }
    }

    public String describeLocation() {
        return dir.toString();
    }
}
