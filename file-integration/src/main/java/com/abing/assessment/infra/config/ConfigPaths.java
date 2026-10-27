package com.abing.assessment.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * configs 目录的查找规则。规则配置、prompt 模板都从这里取，必须用同一套规则，
 * 否则会出现"规则能加载但 prompt 找不到"这种莫名问题。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>{@code -Dassessment.config-dir}（测试/调试用，优先级最高）</li>
 *   <li>{@code ./configs}（exe 运行时，配置就在 exe 旁边）</li>
 *   <li>{@code ../configs}（开发时工作目录是 file-integration）</li>
 *   <li>{@code %LOCALAPPDATA%/Assessment/configs}（用户级配置）</li>
 * </ol>
 */
public final class ConfigPaths {

    private ConfigPaths() {
    }

    public static Path resolveConfigDir() {
        List<Path> candidates = new ArrayList<>();
        String explicit = System.getProperty("assessment.config-dir");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit));
        }
        candidates.add(Paths.get("configs"));
        candidates.add(Paths.get("..", "configs"));
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Paths.get(localAppData, "Assessment", "configs"));
        }
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
