package com.abing.assessment.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把确认过的规则草稿写成 YAML，替换当前生效的配置。
 *
 * <p><b>写入不是「新建一份」，而是「覆盖当前这份」。</b>因为用户的心智模型是
 * 「今年换了细则，我重新传一次」——他不需要知道 YAML 在哪个目录、加载路径是什么。
 * 旧文件自动备份成 {@code .bak-时间戳}，改坏了能退回。
 *
 * <p>只替换 {@code rule_groups} 和 meta 里的来源信息，{@code formulas} /
 * {@code dimensions} / {@code global_constraints} 原样保留：那些是学校统一规定的
 * 计算方式和打包规格，不在学院细则里，重新生成会丢。
 *
 * <p>副作用：SnakeYAML dump 不带注释，所以原文件里的说明性注释会消失。
 * 这是有意的取舍——重新导入本来就会重写规则部分，而备份还在。
 */
@Component
public class RuleConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigWriter.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final YamlRuleLoader loader;

    public RuleConfigWriter(YamlRuleLoader loader) {
        this.loader = loader;
    }

    /**
     * 保存草稿并重新加载。
     *
     * @param draft         用户确认过的草稿
     * @param collegeName   页面上填的学院名（留空则沿用原配置）
     * @return 写入的文件路径
     */
    public Path save(RuleDraft draft, String collegeName) throws IOException {
        Path target = loader.primaryFile();
        if (target == null) {
            throw new IOException("没有找到当前生效的规则文件，无法确定覆盖目标。"
                    + "请确认 configs 目录下有学院 YAML（第一次使用请先放一份基础配置）");
        }
        if (draft.unscoredItems().size() > 0) {
            throw new IOException("还有 " + draft.unscoredItems().size()
                    + " 条规则没有分值，不能保存——请先在页面上补上或删掉");
        }

        Map<String, Object> root = readBase(target);

        Map<String, Object> meta = asMap(root.get("meta"));
        if (collegeName != null && !collegeName.isBlank()) {
            meta.put("college", collegeName.trim());
        }
        meta.put("source_file", draft.sourceFile());
        meta.put("extracted_at", LocalDate.now().toString());
        root.put("meta", meta);

        root.put("rule_groups", toGroups(draft));

        String yaml = header(draft, meta) + dump(root);
        backup(target);
        Files.writeString(target, yaml, StandardCharsets.UTF_8);
        log.info("规则已写入 {}（{} 组 / {} 条，旧版已备份）",
                target, draft.groups().size(), draft.itemCount());

        loader.reload();
        return target;
    }

    // ── YAML 组装 ────────────────────────────────────────────────────────

    private List<Map<String, Object>> toGroups(RuleDraft draft) {
        List<Map<String, Object>> out = new ArrayList<>();
        // id 只用于日志和页面引用，序号够用；真正可读的名字在 name 里
        Map<String, Integer> seq = new LinkedHashMap<>();

        for (RuleDraft.Group g : draft.groups()) {
            int n = seq.merge(g.dimension(), 1, Integer::sum);
            Set<String> conflicts = conflictKeysOf(g);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.dimension() + "_" + n);
            m.put("dimension", g.dimension());
            m.put("kind", g.kind());
            m.put("name", g.name());
            if (g.note() != null) {
                m.put("note", g.note());
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (RuleDraft.Item it : g.items()) {
                items.add(toItem(it, conflicts.contains(matchKeyOf(it))));
            }
            m.put("items", items);
            out.add(m);
        }
        return out;
    }

    /** 同组里「级别 + 匹配词」相同但分值不同的那些键 */
    private static Set<String> conflictKeysOf(RuleDraft.Group g) {
        Map<String, Set<Double>> byKey = new LinkedHashMap<>();
        for (RuleDraft.Item it : g.items()) {
            byKey.computeIfAbsent(matchKeyOf(it), k -> new LinkedHashSet<>()).add(it.score());
        }
        Set<String> out = new LinkedHashSet<>();
        byKey.forEach((k, v) -> {
            if (v.size() > 1) {
                out.add(k);
            }
        });
        return out;
    }

    private static String matchKeyOf(RuleDraft.Item it) {
        String keyword = it.rank() != null ? it.rank() : it.title();
        return (it.level() == null ? "任意级别" : it.level()) + " · " + keyword;
    }

    /**
     * @param conflict 与同组其他条目条件相同、分值不同。
     *                 这种必须标 manual —— 否则 YAML 里两条 match 一模一样，
     *                 引擎无法区分谁是谁，会静默按其中一条给分（算错了也不报）。
     */
    private Map<String, Object> toItem(RuleDraft.Item it, boolean conflict) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> match = new LinkedHashMap<>();

        // 「不确定」不写 level：那表示表里本来就没有级别维度（例如一般性主题教育活动），
        // 写了反而会要求材料必须命中"不确定"这个值，永远匹配不上。
        String level = it.level();
        if (level != null && !"不确定".equals(level)) {
            match.put("level", level);
        }
        String keyword = it.rank() != null ? it.rank() : it.title();
        if (keyword != null) {
            match.put("titles", List.of(keyword));
        }
        m.put("match", match);
        m.put("score", num(it.score()));
        String note = it.note() != null ? it.note() : it.title();
        if (conflict) {
            m.put("manual", true);
            note = note + "｜同组有相同条件、不同分值的条目，请按角色（如寝室长/普通成员）人工确认";
        }
        m.put("note", note);
        return m;
    }

    /** 整数分写成 20 而不是 20.0，YAML 才干净 */
    private static Object num(Double v) {
        if (v == null) {
            return null;
        }
        return v == Math.rint(v) ? (Object) v.longValue() : (Object) v;
    }

    private static String header(RuleDraft draft, Map<String, Object> meta) {
        return """
                # 综合测评规则配置 —— 由「上传细则」自动转换并经人工核对生成
                #
                # 来源文件：%s
                # 生成日期：%s
                # 规则组：%d 组 / %d 条
                #
                # ⚠ 这个文件的 rule_groups 是机器生成的，改坏了页面会算错分。
                #   要改就改完再去「评分规则」里核对一遍；每次重新导入都会覆盖这里。
                #   formulas / dimensions / global_constraints 是学校统一规定，导入时原样保留。
                #
                """.formatted(
                draft.sourceFile() == null ? "（未记录）" : draft.sourceFile(),
                meta.getOrDefault("extracted_at", LocalDate.now().toString()),
                draft.groups().size(), draft.itemCount());
    }

    private static String dump(Map<String, Object> root) {
        DumperOptions opt = new DumperOptions();
        opt.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opt.setIndent(2);
        // 细则备注经常一整段中文，折行会切碎成多行且难以阅读
        opt.setWidth(4096);
        opt.setSplitLines(false);
        opt.setAllowUnicode(true);
        return new Yaml(opt).dump(root);
    }

    // ── 读旧配置 / 备份 ──────────────────────────────────────────────────

    /** 读现有 YAML 作为骨架；文件不存在或读不动就用内置的五育默认结构 */
    private Map<String, Object> readBase(Path target) {
        if (target != null && Files.isRegularFile(target)) {
            try (InputStream in = Files.newInputStream(target)) {
                Object loaded = new Yaml().load(in);
                if (loaded instanceof Map<?, ?> map) {
                    Map<String, Object> root = new LinkedHashMap<>();
                    map.forEach((k, v) -> root.put(String.valueOf(k), v));
                    return root;
                }
            } catch (Exception e) {
                log.warn("读取旧规则失败，将用默认五育结构重建：{}", e.toString());
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("meta", new LinkedHashMap<>(Map.of(
                "school", "广东金融学院",
                "college", "计算机学院",
                "rule_version", LocalDate.now().toString())));
        root.put("formulas", new LinkedHashMap<>(Map.of(
                "overall", "德育*0.20 + 智育*0.60 + 体育*0.10 + 美育*0.05 + 劳育*0.05")));
        root.put("dimensions", defaultDimensions());
        root.put("global_constraints", new LinkedHashMap<>(Map.of(
                "time_window", true,
                "same_category_highest_only", true,
                "proof_required", true)));
        return root;
    }

    /**
     * 五育维度与计分公式的默认值（校发《实施办法》第五~九条）。
     *
     * <p>只有在「原配置读不出来」时才会用到。这五个公式是全校统一的，
     * 不随学院细则变化，所以内置一份比让用户自己填更安全。
     */
    private List<Map<String, Object>> defaultDimensions() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(dim("moral", "德育", 0.20, "base*0.30 + peer*0.20 + bonus*0.50 - deduction",
                List.of(input("base", "德育基础分"), input("peer", "互评分"))));
        out.add(dim("intellect", "智育", 0.60, "base*0.80 + bonus*0.20",
                List.of(input("base", "智育基础分"))));
        out.add(dim("physique", "体育", 0.10, "base*0.70 + bonus*0.30 - deduction",
                List.of(input("base", "体育基础分"))));
        out.add(dim("aesthetic", "美育", 0.05, "base*0.50 + bonus*0.50",
                List.of(input("base", "美育基础分"))));
        out.add(dim("labour", "劳育", 0.05, "base*0.50 + bonus*0.50",
                List.of(input("base", "劳育基础分"))));
        return out;
    }

    private Map<String, Object> dim(String id, String name, double weight, String formula,
                                    List<Map<String, Object>> inputs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("weight", weight);
        m.put("formula", formula);
        m.put("floor_zero", true);
        m.put("inputs", inputs);
        return m;
    }

    private Map<String, Object> input(String key, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        return m;
    }

    private void backup(Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return;
        }
        String name = target.getFileName().toString();
        Path bak = target.resolveSibling(name + ".bak-" + LocalDateTime.now().format(STAMP));
        Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
        log.info("旧规则已备份：{}", bak.getFileName());
    }

    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>();
    }
}
