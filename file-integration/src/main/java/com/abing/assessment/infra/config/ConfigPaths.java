package com.abing.assessment.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * configs 目录查找规则。规则与 prompt 必须走同一套，否则会出现"规则能加载但 prompt 找不到"。
 *
 * <p>顺序：{@code -Dassessment.config-dir}（调试）→ {@code ./configs}（exe 同级）→
 * {@code ../configs}（开发态工作目录）→ {@code %LOCALAPPDATA%/Assessment/configs}。
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
