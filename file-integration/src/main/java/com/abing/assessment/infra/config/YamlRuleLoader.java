package com.abing.assessment.infra.config;

import com.abing.assessment.domain.model.DimensionSpec;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.RuleGroup;
import com.abing.assessment.domain.model.RuleItem;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 从 configs/ 加载规则配置。三条纪律：配置在包外（改配置不用重建）；
 * 缺字段要报出来（宁可页面上红着，也不静默跳过）；{@code configs/_school/} 是被引用的公共部分，
 * 不单独成规则。
 *
 * <p>解析策略「<b>能认的认，认不得的报</b>」：单条写错不拖垮整份配置，问题进 problems 让人看见。
 */
@Component
public class YamlRuleLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlRuleLoader.class);

    /** 决策 4：维度固定五育，规则组挂到别的维度上属于配置错误 */
    private static final List<String> KNOWN_DIMENSIONS =
            List.of("moral", "intellect", "physique", "aesthetic", "labour");

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

    private void loadOne(Path file) {        String name = file.getFileName().toString();
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

            List<DimensionSpec> dimensions = parseDimensions(name, map.get("dimensions"));
            List<RuleGroup> groups = parseGroups(name, map.get("rule_groups"), dimensions);

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
                    dimensions,
                    groups,
                    raw);

            rules.add(cfg);
            log.info("已加载规则：{}（{}）—— 维度 {} 个，规则组 {} 个，条目 {} 条",
                    cfg.summary(), name, dimensions.size(), groups.size(),
                    groups.stream().mapToInt(g -> g.items().size()).sum());
        } catch (Exception e) {
            problems.add(name + "：解析失败 —— " + e.getMessage());
            log.error("解析配置失败：{}", file, e);
        }
    }

    // ── dimensions ───────────────────────────────────────────────────────

    private List<DimensionSpec> parseDimensions(String fileName, Object node) {
        List<DimensionSpec> out = new ArrayList<>();
        for (Object o : asList(node)) {
            Map<String, Object> m = asMap(o);
            String id = str(m.get("id"));
            if (id == null) {
                problems.add(fileName + "：dimensions 里有一条没写 id，已跳过");
                continue;
            }
            if (!KNOWN_DIMENSIONS.contains(id)) {
                problems.add(fileName + "：维度「" + id + "」不在固定五育（"
                        + String.join("/", KNOWN_DIMENSIONS) + "）内，已跳过。"
                        + "细则里五育之外的维度请人工判定，不要塞进配置");
                continue;
            }
            List<DimensionSpec.InputSpec> inputs = new ArrayList<>();
            for (Object io : asList(m.get("inputs"))) {
                Map<String, Object> im = asMap(io);
                String key = str(im.get("key"));
                if (key != null) {
                    inputs.add(new DimensionSpec.InputSpec(key, str(im.get("name")), str(im.get("hint"))));
                }
            }
            double weight = num(m.get("weight"), 0d);
            out.add(new DimensionSpec(
                    id,
                    str(m.get("name")) == null ? id : str(m.get("name")),
                    weight,
                    str(m.get("formula")) == null ? "bonus - deduction" : str(m.get("formula")),
                    bool(m.get("floor_zero"), false),
                    inputs,
                    str(m.get("note"))));
        }
        if (out.isEmpty()) {
            problems.add(fileName + "：没有解析出任何 dimension，算分将无法进行");
        }
        double sum = out.stream().mapToDouble(DimensionSpec::weight).sum();
        if (!out.isEmpty() && Math.abs(sum - 1d) > 0.001) {
            // 不阻塞加载：可能是没写全的中间态，但必须让人看见
            problems.add(fileName + "：五个维度权重之和是 " + round(sum) + "，不是 1，总分会被缩放");
        }
        return out;
    }

    // ── rule_groups ──────────────────────────────────────────────────────

    private List<RuleGroup> parseGroups(String fileName, Object node, List<DimensionSpec> dimensions) {
        List<RuleGroup> out = new ArrayList<>();
        List<String> dimIds = dimensions.stream().map(DimensionSpec::id).toList();
        for (Object o : asList(node)) {
            Map<String, Object> m = asMap(o);
            String id = str(m.get("id"));
            String dim = str(m.get("dimension"));
            if (id == null || dim == null) {
                problems.add(fileName + "：rule_groups 里有一组缺 id 或 dimension，已跳过");
                continue;
            }
            if (!dimIds.contains(dim)) {
                problems.add(fileName + "：规则组「" + id + "」挂在未知维度「" + dim + "」上，已跳过");
                continue;
            }
            List<String> columns = new ArrayList<>();
            for (Object c : asList(m.get("columns"))) {
                columns.add(String.valueOf(c));
            }
            List<String> exclusions = new ArrayList<>();
            for (Object c : asList(m.get("exclusions"))) {
                exclusions.add(String.valueOf(c));
            }
            List<RuleItem> items = parseItems(fileName, id, m.get("items"), columns.size());
            if (items.isEmpty()) {
                log.warn("规则组 {} 没有可用条目，已跳过", id);
                continue;
            }
            String kind = str(m.get("kind"));
            out.add(new RuleGroup(
                    id,
                    dim,
                    kind == null ? "bonus" : kind,
                    str(m.get("name")) == null ? id : str(m.get("name")),
                    m.get("max_items") == null ? null : (int) num(m.get("max_items"), 0),
                    columns,
                    exclusions,
                    items,
                    str(m.get("note"))));
        }
        return out;
    }

    private List<RuleItem> parseItems(String fileName, String groupId, Object node, int columnCount) {
        List<RuleItem> out = new ArrayList<>();
        int idx = 0;
        for (Object o : asList(node)) {
            idx++;
            Map<String, Object> m = asMap(o);
            Map<String, Object> match = asMap(m.get("match"));
            Double score = m.get("score") == null ? null : num(m.get("score"), 0d);

            List<Double> scores = null;
            if (m.get("scores") != null) {
                scores = new ArrayList<>();
                for (Object s : asList(m.get("scores"))) {
                    scores.add(s == null ? null : num(s, 0d));
                }
                if (columnCount > 0 && scores.size() != columnCount) {
                    problems.add(fileName + "：" + groupId + " 第 " + idx + " 条 scores 有 "
                            + scores.size() + " 列，但 columns 有 " + columnCount + " 列，已按较短的截断");
                }
            }
            if (score == null && scores == null && !bool(m.get("manual"), false)) {
                problems.add(fileName + "：" + groupId + " 第 " + idx + " 条既没有 score 也没有 scores，已跳过");
                continue;
            }
            if (match.isEmpty()) {
                // 没有 match 的条目会匹配所有材料，等于给每份材料都加分——这是很危险的配置错误
                problems.add(fileName + "：" + groupId + " 第 " + idx + " 条没有 match 条件，已跳过"
                        + "（无条件条目会把分数加给每一份材料）");
                continue;
            }
            out.add(new RuleItem(
                    match,
                    score,
                    scores,
                    str(m.get("unit")),
                    m.get("cap") == null ? null : num(m.get("cap"), 0d),
                    bool(m.get("manual"), false),
                    str(m.get("note"))));
        }
        return out;
    }

    // ── 工具 ─────────────────────────────────────────────────────────────

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

    private static List<Object> asList(Object o) {
        if (o instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return List.of();
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static double num(Object o, double dft) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? dft : Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    private static boolean bool(Object o, boolean dft) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? dft : Boolean.parseBoolean(String.valueOf(o));
    }

    private static boolean isBlank(Object o) {
        return o == null || String.valueOf(o).isBlank();
    }

    private static String round(double v) {
        return String.valueOf(Math.round(v * 1000) / 1000d);
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

    /**
     * 当前生效的那份配置文件。
     *
     * <p>「上传新细则」的语义是<b>替换</b>，所以写入位置就是这份文件——不是新开一个让用户
     * 自己去改加载路径。没有加载到任何配置时返回 null，调用方自己决定放哪。
     */
    public Path primaryFile() {
        return rules.isEmpty() ? null : rules.get(0).file();
    }

    /**
     * 重新读取全部配置。用户导入新细则、或手改了 YAML 之后调用。
     *
     * <p>先清空再加载，否则会叠加出一份「旧规则 + 新规则」的幻觉配置——
     * 那样算分会比实际多给，且没人能看出来。
     */
    public synchronized void reload() {
        rules.clear();
        problems.clear();
        load();
    }
}
