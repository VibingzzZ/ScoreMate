package com.abing.assessment.infra.config;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.infra.llm.Extractor;
import com.abing.assessment.infra.material.MaterialNormalizer;
import com.abing.assessment.infra.material.NormalizedMaterial;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * 把用户上传的评分细则（PDF / 图片 / Word）转成规则草稿。
 *
 * <p>只负责「尽量读准 + 标出不确定」，不直接生效：细则表有合并单元格、斜线表头、跨页续表，
 * 读错一格在页面上看不出来，须由人在 {@link RuleDraft} 上核对。
 *
 * <p>已验（2026-10-29，真实细则 P29 智育竞赛表）：4 级别 × 5 等次共 20 格逐格全对。
 * 风险仍在跨页续表与纵向合并单元格，靠 prompt 铁律兜。
 *
 * <p>逐页并发 3：37 页串行两分半，并发约 50 秒。页间无依赖（跨页条目按标题+级别+等次去重）。
 */
@Component
public class RuleImporter {

    private static final Logger log = LoggerFactory.getLogger(RuleImporter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * 页数上限。37 页的细则里前面十几页是文字条款（总则、评选条件），后面才是评分表，
     * 全都得送模型才能判断哪页有表——本地判断不可靠（扫描件的文本层是空的）。
     * 40 页对应约 8 万 token，是一次性成本。
     */
    private static final int MAX_PAGES = 40;

    private static final int PAGE_CONCURRENCY = 3;

    /** 一页可能吐几十条 item（P29 一页就是 20 条），比材料抽取的 2000 大得多 */
    private static final int PAGE_MAX_TOKENS = 6000;

    private static final String PROMPT_FILE = "rules-v1.txt";

    /** 模型可能吐中文维度名，统一收敛到 schema 的五个 id */
    private static final Map<String, String> DIMENSION_ALIAS = Map.ofEntries(
            Map.entry("德育", "moral"), Map.entry("智育", "intellect"), Map.entry("体育", "physique"),
            Map.entry("美育", "aesthetic"), Map.entry("劳育", "labour"),
            Map.entry("moral", "moral"), Map.entry("intellect", "intellect"),
            Map.entry("physique", "physique"), Map.entry("aesthetic", "aesthetic"),
            Map.entry("labour", "labour"));

    private final Extractor extractor;
    private final List<MaterialNormalizer> normalizers;
    /** null 表示 prompt 模板缺失，此时导入不可用 */
    private final String prompt;

    public RuleImporter(Extractor extractor, List<MaterialNormalizer> normalizers) {
        this.extractor = extractor;
        this.normalizers = normalizers;
        this.prompt = loadPrompt();
        if (prompt == null) {
            log.warn("未找到规则抽取 prompt configs/prompt/{}，细则导入功能不可用", PROMPT_FILE);
        }
    }

    /** 页面用这个决定「上传细则」按钮是否可点 */
    public boolean ready() {
        return prompt != null && extractor.isConfigured();
    }

    /**
     * 读取细则文件，产出待确认的规则草稿。
     *
     * @param bytes    文件字节
     * @param fileName 原始文件名（决定用哪个归一化器，也写进草稿的来源）
     * @param mime     Content-Type，可能为空
     */
    public RuleDraft importFrom(byte[] bytes, String fileName, String mime) throws IOException {
        if (prompt == null) {
            throw new BusinessException("缺少规则抽取 prompt：configs/prompt/" + PROMPT_FILE, 500);
        }
        if (!extractor.isConfigured()) {
            throw new BusinessException("还没有配置模型 API Key，无法自动转换规则", 400);
        }

        String ext = extensionOf(fileName);
        MaterialNormalizer normalizer = MaterialNormalizer.pick(normalizers, ext, mime);

        NormalizedMaterial material;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            material = normalizer.normalize(in, fileName, MAX_PAGES);
        }
        List<byte[]> pages = material.images();
        log.info("细则导入开始：{}，共 {} 页，送模型 {} 页", fileName, material.totalPages(), pages.size());

        long startedAt = System.currentTimeMillis();
        List<PageResult> results = readAll(pages, fileName);
        RuleDraft draft = merge(results, fileName, material.totalPages(), pages.size());
        log.info("细则导入完成：{}ms，{} 组 / {} 条，问题 {} 条",
                System.currentTimeMillis() - startedAt,
                draft.groups().size(), draft.itemCount(), draft.problems().size());
        return draft;
    }

    // ── 逐页读取 ─────────────────────────────────────────────────────────

    /** 单页结果：json 为 null 表示这页失败了，error 说明原因 */
    private record PageResult(int pageNo, JsonNode json, int tokens, String error) {
    }

    private List<PageResult> readAll(List<byte[]> pages, String fileName) {
        int n = pages.size();
        PageResult[] out = new PageResult[n];
        Semaphore gate = new Semaphore(PAGE_CONCURRENCY);

        // 虚拟线程：这里全是「等模型回话」，不占 CPU，并发度不必和核数挂钩
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    gate.acquire();
                    try {
                        out[index] = readOne(pages.get(index), index + 1, fileName);
                    } finally {
                        gate.release();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("细则导入被中断", 504);
                } catch (Exception e) {
                    // 单页失败不该炸掉整份：最终会有 problem 提示哪页没读到
                    log.warn("细则某一页读取异常：{}", e.toString());
                }
            }
        }
        return Arrays.asList(out);
    }

    private PageResult readOne(byte[] image, int pageNo, String fileName) {
        try {
            String pagePrompt = prompt + "\n\n—— 本页是第 " + pageNo + " 页，文件："
                    + (fileName == null ? "未命名" : fileName) + "。";
            Extractor.RawExtraction raw = extractor.extractRaw(
                    image, NormalizedMaterial.IMAGE_MIME, pagePrompt, PAGE_MAX_TOKENS);
            String text = raw.text();
            JsonNode node = MAPPER.readTree(firstJsonObject(text));
            int tokens = raw.totalTokens() == null ? 0 : raw.totalTokens();
            return new PageResult(pageNo, node, tokens, null);
        } catch (BusinessException e) {
            return new PageResult(pageNo, null, 0, e.getMessage());
        } catch (Exception e) {
            return new PageResult(pageNo, null, 0, e.getClass().getSimpleName() + "：" + e.getMessage());
        }
    }

    // ── 合并成草稿 ───────────────────────────────────────────────────────

    private RuleDraft merge(List<PageResult> results, String fileName, int totalPages, int pagesRead) {
        // 用 LinkedHashMap 保住「先出现的组排在前面」，页面表格的顺序就和细则一致
        Map<String, GroupBuilder> groups = new LinkedHashMap<>();
        Set<String> seenItems = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        List<Integer> noTablePages = new ArrayList<>();
        int tokens = 0;

        for (PageResult pr : results) {
            if (pr == null) {
                continue;
            }
            tokens += pr.tokens();
            if (pr.error() != null) {
                problems.add("第 " + pr.pageNo() + " 页没读到：" + pr.error());
                continue;
            }
            JsonNode json = pr.json();
            if (!json.path("has_table").asBoolean(true)) {
                noTablePages.add(pr.pageNo());
                continue;
            }

            String pageDim = dimensionOf(json.path("dimension").asText(null));

            for (JsonNode g : json.path("groups")) {
                String name = text(g, "name");
                if (name == null) {
                    continue;
                }
                String dim = pageDim != null ? pageDim : dimensionOf(g.path("dimension").asText(null));
                if (dim == null) {
                    problems.add("第 " + pr.pageNo() + " 页的组「" + name + "」没判断出属于哪一育，"
                            + "已跳过——请确认这页的表头，或手动把这一组加到 YAML 里");
                    continue;
                }
                String kind = "penalty".equalsIgnoreCase(text(g, "kind")) ? "penalty" : "bonus";
                GroupBuilder gb = groups.computeIfAbsent(
                        dim + "|" + name.trim(),
                        k -> new GroupBuilder(dim, name.trim(), kind, text(g, "evidence"), text(g, "note")));
                if (gb.note == null) {
                    gb.note = text(g, "note");
                }

                for (JsonNode it : g.path("items")) {
                    RuleDraft.Item item = toItem(it, pr.pageNo(), name, problems);
                    if (item == null) {
                        continue;
                    }
                    if (seenItems.add(gb.dimension + "|" + gb.name + "#" + item.key())) {
                        gb.items.add(item);
                    }
                }
            }

            for (JsonNode p : json.path("problems")) {
                String msg = p.asText("");
                if (!msg.isBlank()) {
                    problems.add("第 " + pr.pageNo() + " 页：" + msg);
                }
            }
        }

        List<RuleDraft.Group> out = new ArrayList<>();
        for (GroupBuilder gb : groups.values()) {
            if (!gb.items.isEmpty()) {
                out.add(new RuleDraft.Group(gb.dimension, gb.name, gb.kind, gb.evidence, gb.note,
                        List.copyOf(gb.items)));
            }
        }

        if (out.isEmpty()) {
            problems.add("整份文件没有识别出任何规则组。可能原因："
                    + "① 传的是学校办法而不是学院细则（学院细则才有加分表）；"
                    + "② 扫描件太模糊；③ 模型把表格判成「非表格」。建议换清晰的 PDF 重试。");
        }
        if (!noTablePages.isEmpty()) {
            problems.add("第 " + joinPages(noTablePages) + " 页判定为「没有评分表」，未产出规则。"
                    + "如果那里其实有表，请人工补进 YAML。");
        }
        return new RuleDraft(fileName, out, problems, totalPages, pagesRead, tokens);
    }

    private RuleDraft.Item toItem(JsonNode it, int pageNo, String groupName, List<String> problems) {
        String level = normalizeLevel(text(it, "level"));
        String rank = text(it, "rank");
        String title = text(it, "title");
        Double score = it.path("score").isNumber() ? it.path("score").asDouble() : null;
        if (title == null) {
            if (level == null && rank == null) {
                return null;
            }
            title = (level == null ? "" : level) + (rank == null ? "" : rank);
        }
        if (score == null) {
            problems.add("第 " + pageNo + " 页的「" + groupName + " · " + title
                    + "」没有读到分值，已保留为待填——请对照原文补上，或删掉这一条");
        }
        return new RuleDraft.Item(title, level, rank, score, text(it, "note"));
    }

    /** 可变的分组累加器，避免每次 computeIfAbsent 都新建不可变列表 */
    private static final class GroupBuilder {
        private final String dimension;
        private final String name;
        private final String kind;
        private final String evidence;
        private String note;
        private final List<RuleDraft.Item> items = new ArrayList<>();

        private GroupBuilder(String dimension, String name, String kind, String evidence, String note) {
            this.dimension = dimension;
            this.name = name;
            this.kind = kind;
            this.evidence = evidence;
            this.note = note;
        }
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private static String dimensionOf(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return DIMENSION_ALIAS.get(t);
    }

    /** 级别枚举之外的写法一律收敛成「不确定」，否则引擎匹配不上会静默不给分 */
    private static String normalizeLevel(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return switch (t) {
            case "国家级", "省级", "市级", "校级", "院级", "不确定" -> t;
            case "国家", "全国" -> "国家级";
            case "省", "省部级" -> "省级";
            case "市", "市校" -> "市级";
            case "校", "学校" -> "校级";
            case "院", "学院" -> "院级";
            default -> "不确定";
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String joinPages(List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(pages.get(i));
        }
        return sb.toString();
    }

    /** 容忍 ```json 围栏和前后废话，只取第一个 { 到最后一个 } */
    private static String firstJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
    }

    private static String loadPrompt() {
        Path dir = ConfigPaths.resolveConfigDir();
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve("prompt").resolve(PROMPT_FILE);
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            log.warn("读取规则抽取 prompt 失败：{}", e.toString());
        }
        return null;
    }
}
