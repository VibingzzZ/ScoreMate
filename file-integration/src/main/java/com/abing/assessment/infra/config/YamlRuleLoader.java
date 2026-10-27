package com.abing.assessment.infra.config;

import com.abing.assessment.domain.model.RuleConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从 configs/ 目录加载规则配置。
 *
 * <p>三条纪律（对应架构文档 §3.1 的配置原则）：
 * <ol>
 *   <li><b>配置在包外</b>：不打包进 jar，改配置不用重新构建</li>
 *   <li><b>缺字段要报出来</b>：宁可页面上红着"缺少 meta.college"，也不要静默跳过</li>
 *   <li><b>学校层不单独成规则</b>：{@code configs/_school/} 是被学院层引用的公共部分，跳过</li>
 * </ol>
 */
@Component
public class YamlRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlRuleLoader.class);

    private final List<RuleConfig> rules = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private Path configDir;

    @PostConstruct
    public void load() {
        configDir = ConfigPaths.resolveConfigDir();
        if (configDir == null) {
            problems.add("未找到 configs 目录（已查找：./configs、../configs、%LOCALAPPDATA%/Assessment/configs）");
            log.warn("未找到任何规则配置，页面会显示「未加载」");
            return;
        }
        log.info("规则配置目录：{}", configDir);

        try (Stream<Path> walk = Files.walk(configDir)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    // 跳过 _school / _template 这类下划线开头的公共层
                    .filter(p -> {
                        Path parent = p.getParent();
                        return parent == null || !parent.getFileName().toString().startsWith("_");
                    })
                    .sorted()
                    .toList();
            files.forEach(this::loadOne);
        } catch (IOException e) {
            problems.add("遍历配置目录失败：" + e.getMessage());
            log.error("遍历配置目录失败", e);
        }

        log.info("规则配置加载完成：成功 {} 份，问题 {} 条", rules.size(), problems.size());
    }

    private void loadOne(Path file) {
        String name = file.getFileName().toString();
        try (InputStream in = Files.newInputStream(file)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> map)) {
                problems.add(name + "：顶层不是对象，无法解析");
                return;
            }

            Map<String, Object> meta = asMap(map.get("meta"));
            Map<String, Object> formulas = asMap(map.get("formulas"));

            List<String> missing = new ArrayList<>();
            if (isBlank(meta.get("school"))) missing.add("meta.school");
            if (isBlank(meta.get("college"))) missing.add("meta.college");
            if (isBlank(meta.get("rule_version"))) missing.add("meta.rule_version");
            if (formulas.isEmpty()) missing.add("formulas");

            if (!missing.isEmpty()) {
                // 绝不静默跳过：配置写错必须让人看见
                problems.add(name + "：缺少必填字段 " + String.join("、", missing));
                log.warn("配置 {} 缺少必填字段：{}", name, missing);
                return;
            }

            Map<String, Object> raw = new LinkedHashMap<>();
            map.forEach((k, v) -> raw.put(String.valueOf(k), v));

            RuleConfig cfg = new RuleConfig(
                    String.valueOf(meta.get("school")),
                    String.valueOf(meta.get("college")),
                    String.valueOf(meta.get("rule_version")),
                    meta.get("source_file") == null ? null : String.valueOf(meta.get("source_file")),
                    file.toAbsolutePath(),
                    formulas,
                    asMap(map.get("global_constraints")),
                    raw);

            rules.add(cfg);
            log.info("已加载规则：{}（{}）", cfg.summary(), name);
        } catch (Exception e) {
            problems.add(name + "：解析失败 —— " + e.getMessage());
            log.error("解析配置失败：{}", file, e);
        }
    }

    private boolean isYaml(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        return n.endsWith(".yaml") || n.endsWith(".yml");
    }

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    public List<RuleConfig> rules() {
        return List.copyOf(rules);
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public Path configDir() {
        return configDir;
    }
}
