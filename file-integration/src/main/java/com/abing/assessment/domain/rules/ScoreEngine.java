package com.abing.assessment.domain.rules;

import com.abing.assessment.domain.model.DimensionSpec;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.PenaltyEntry;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.RuleGroup;
import com.abing.assessment.domain.model.RuleItem;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则引擎：材料事实 → 分数。
 *
 * <p>**纯函数**：无 IO、无 Spring、无随机、不读 {@code now()}。同样的输入永远同样的输出。
 * 这一条决定了它可以被穷举测试，也决定了用户申诉时有据可查——这是整个项目里
 * "AI 只负责看懂材料、算分必须可复现"那条底线（架构文档 ADR-16）的落脚点。
 *
 * <p>五步顺序（顺序错了结果就错，见架构文档 §5.2）：
 * <ol>
 *   <li>窗口校验：日期不在学年内 → 标 {@code inWindow=false}，<b>不删除</b>，由人最终定</li>
 *   <li>查表定级：材料事实去匹配 {@code match} 条件；级别不明时<b>给出各等级候选</b>，绝不猜死</li>
 *   <li>规则匹配：命中多条 → 全部保留为候选行，默认取最高分那条，用户可改选</li>
 *   <li>冲突合并：按组 {@code max_items} 截断（同类别取最高），被挤掉的留在页面上</li>
 *   <li>维度聚合：{@code formula} 求值（内置解析器），再按权重算总分</li>
 * </ol>
 *
 * <p><b>不做的事</b>：不猜数量（"志愿服务"读不出小时数就不给分，标人工）、
 * 不把没匹配上的材料丢掉（进 UNMATCHED 列表）。
 */
public final class ScoreEngine {

    private ScoreEngine() {
    }

    public static ScoreReport evaluate(ScoreInput input, RuleConfig cfg) {
        AcademicYear year = AcademicYear.parse(input.academicYear());
        List<String> issues = new ArrayList<>();

        if (cfg.dimensions() == null || cfg.dimensions().isEmpty()) {
            issues.add("规则配置里没有 dimensions，无法算分");
            return new ScoreReport(cfg.school(), cfg.college(), cfg.ruleVersion(),
                    year.label(), year.start().toString(), year.end().toString(),
                    List.of(), List.of(), List.of(), issues, null, "empty", List.of());
        }

        List<Draft> drafts = new ArrayList<>();
        List<ScoreReport.Unmatched> unmatched = new ArrayList<>();

        // ── 1~3 步：逐材料匹配，产出行（含候选） ──────────────────────
        List<ScoreInput.Material> materials = input.materials() == null ? List.of() : input.materials();
        for (int mi = 0; mi < materials.size(); mi++) {
            ScoreInput.Material m = materials.get(mi);
            ExtractedFact f = m.fact();
            String fileName = m.fileName() == null ? ("材料 " + (mi + 1)) : m.fileName();

            if (f == null) {
                unmatched.add(new ScoreReport.Unmatched(mi, fileName, "这份材料没有识别结果"));
                continue;
            }
            if (isNonCertificate(f)) {
                unmatched.add(new ScoreReport.Unmatched(mi, fileName,
                        "不是证明材料（doc_type=" + f.docType() + "），不参与算分"));
                continue;
            }

            boolean matchedAny = false;
            for (RuleGroup g : cfg.ruleGroups()) {
                if (!cfg.dimensionIndex().containsKey(g.dimension())) {
                    continue;
                }
                // 组级「育」过滤：AI 已判定这张证书属于哪个育，规则组也知道自己属于哪个育。
                // 两边都说得出话却对不上 → 整组跳过。防的是同一张摄影奖状被美育和德育
                // 各算一遍——重复计分比漏算更难被发现，学生也更容易被说成虚报。
                if (!dimensionCompatible(f, g, cfg)) {
                    continue;
                }
                for (int ii = 0; ii < g.items().size(); ii++) {
                    RuleItem item = g.items().get(ii);
                    MatchResult mr = match(item, f);
                    if (!mr.matched()) {
                        continue;
                    }
                    matchedAny = true;

                    Draft d = new Draft();
                    d.id = mi + "#" + g.id() + "#" + ii;
                    d.materialIndex = mi;
                    d.fileName = fileName;
                    d.dimension = g.dimension();
                    d.groupId = g.id();
                    d.groupName = g.name();
                    d.basis = item.describe();
                    d.evidence = f.evidenceQuote();
                    d.manual = item.needsManualInput();
                    d.reasons = new ArrayList<>();

                    // 分值
                    if (item.needsManualInput()) {
                        d.score = null;
                        d.reasons.add(item.unit() == null
                                ? "需要人工定分"
                                : "需要人工填写数量（单位：" + item.unit() + "）");
                    } else if (item.isMatrix()) {
                        Integer col = columnIndex(g, item, f, mr.columnWanted());
                        if (col == null) {
                            d.score = null;
                            d.manual = true;
                            d.reasons.add("无法确定等次（材料上的奖项等级对不上「"
                                    + String.join(" / ", g.columns()) + "」）");
                        } else if (col >= item.scores().size()) {
                            d.score = null;
                            d.manual = true;
                            d.reasons.add("等次「" + g.columns().get(col) + "」在分值表里没有对应项");
                        } else {
                            Double v = item.scores().get(col);
                            // 把挑中的等次写进「依据」：只给个数字，班委没法复核
                            d.basis = d.basis + "｜等次：" + g.columns().get(col);
                            if (v == null) {
                                d.score = 0d;
                                d.reasons.add("等次「" + g.columns().get(col) + "」细则规定不计分");
                            } else {
                                d.score = v;
                            }
                        }
                    } else {
                        d.score = item.score();
                    }

                    // 级别不明：这是"候选"的来源，必须让人选
                    if (mr.levelUnknown()) {
                        d.reasons.add("级别未确定，已列出各等级候选");
                    }

                    // 窗口校验
                    if (cfg.timeWindowEnabled()) {
                        d.inWindow = year.contains(f.date());
                        if (Boolean.FALSE.equals(d.inWindow)) {
                            d.reasons.add("日期 " + f.date() + " 不在 " + year.label() + " 学年内");
                        } else if (d.inWindow == null) {
                            d.reasons.add("材料上没有可识别的日期，无法判断是否在学年内");
                        }
                    }

                    d.needsConfirm = d.manual || mr.levelUnknown() || Boolean.FALSE.equals(d.inWindow);
                    drafts.add(d);
                }
            }

            if (!matchedAny) {
                unmatched.add(new ScoreReport.Unmatched(mi, fileName,
                        "细则里没有匹配到任何加分明细——需要你判断它算不算分、算哪一类"));
            }
        }

        // ── 4a 步：每组选出采用的那一条（默认最高分，用户可改） ─────────
        Map<String, List<Draft>> buckets = new LinkedHashMap<>();
        for (Draft d : drafts) {
            buckets.computeIfAbsent(d.materialIndex + ":" + d.groupId, k -> new ArrayList<>()).add(d);
        }
        Map<String, String> choices = input.choicesOrEmpty();
        for (Map.Entry<String, List<Draft>> e : buckets.entrySet()) {
            List<Draft> bucket = e.getValue();
            String chosen = choices.get(e.getKey());
            Draft pick = null;
            if (chosen != null) {
                pick = bucket.stream().filter(d -> d.id.equals(chosen)).findFirst().orElse(null);
            }
            if (pick == null) {
                pick = bucket.stream()
                        .filter(d -> d.score != null)
                        .max(Comparator.comparingDouble(d -> d.score))
                        .orElse(bucket.get(0));
                if (bucket.size() > 1) {
                    pick.reasons.add("同组有 " + bucket.size() + " 条候选，暂定按最高分这条计，可改选");
                    pick.needsConfirm = true;
                }
            }
            pick.selected = true;
        }

        // ── 4b 步：排除 + 窗口 + 组内 max_items 截断 ─────────────────
        Set<String> excluded = new LinkedHashSet<>(input.excludedOrEmpty());
        List<String> forced = input.forcedOrEmpty();
        for (Draft d : drafts) {
            boolean isForced = forced.contains(d.id);
            boolean ok = d.selected && !excluded.contains(d.id) && d.score != null
                    && (isForced || !Boolean.FALSE.equals(d.inWindow));
            d.included = ok;
            if (!d.selected) {
                d.reasons.add("同组已采用其他候选");
            } else if (excluded.contains(d.id)) {
                d.reasons.add("你手动排除了这一条");
            } else if (d.score == null) {
                d.reasons.add("分值未定，未计入");
            } else if (Boolean.FALSE.equals(d.inWindow) && !isForced) {
                d.reasons.add("超出评测学年，未计入（如学院认可可手动勾上）");
            } else if (isForced && Boolean.FALSE.equals(d.inWindow)) {
                d.reasons.add("超出评测学年，但你已确认计入");
            }
        }
        applyGroupCaps(drafts, cfg, forced);

        // ── 5 步：维度聚合 ──────────────────────────────────────────
        Map<String, Double> bonusByDim = new LinkedHashMap<>();
        Map<String, Double> deductByDim = new LinkedHashMap<>();
        List<PenaltyEntry> penalties = new ArrayList<>();
        for (Draft d : drafts) {
            if (!d.included || d.score == null) {
                continue;
            }
            if (d.score >= 0) {
                bonusByDim.merge(d.dimension, d.score, Double::sum);
            } else {
                deductByDim.merge(d.dimension, -d.score, Double::sum);
                // 材料里扣出来的（如体测不达标证书），也要能写进评定表的扣分事由列
                penalties.add(new PenaltyEntry(d.dimension, d.basis, -d.score));
            }
        }
        // 手填扣分：违纪处分、宿舍不合格这类没有证书可拍，只能由用户填
        for (PenaltyEntry pe : input.penaltiesOrEmpty()) {
            if (pe == null || !pe.usable()) {
                continue;
            }
            if (!cfg.dimensionIndex().containsKey(pe.dimension())) {
                issues.add("扣分事由「" + pe.reasonText() + "」挂在不存在的维度 " + pe.dimension() + " 上，已忽略");
                continue;
            }
            deductByDim.merge(pe.dimension(), pe.points(), Double::sum);
            penalties.add(pe);
        }

        List<ScoreReport.DimensionScore> dims = new ArrayList<>();
        Map<String, Double> overallVars = new LinkedHashMap<>();
        boolean allScored = true;

        for (DimensionSpec spec : cfg.dimensions()) {
            double bonus = round2(bonusByDim.getOrDefault(spec.id(), 0d));
            double deduction = round2(deductByDim.getOrDefault(spec.id(), 0d));

            Map<String, Double> vars = new LinkedHashMap<>();
            List<String> warnings = new ArrayList<>();
            boolean missing = false;

            for (DimensionSpec.InputSpec is : spec.inputs()) {
                if (!spec.formulaUses(is.key())) {
                    continue;
                }
                Double v = input.inputsOrEmpty().get(spec.id() + "." + is.key());
                if (v == null) {
                    // 也接受不带维度前缀的写法（单维度配置时更省事）
                    v = input.inputsOrEmpty().get(is.key());
                }
                if (v == null) {
                    missing = true;
                    warnings.add("缺少「" + is.name() + "」" + (is.hasHint() ? "（" + is.hint() + "）" : ""));
                } else {
                    vars.put(is.key(), v);
                }
            }
            vars.put("bonus", bonus);
            vars.put("deduction", deduction);

            Double score = null;
            String status;
            if (missing) {
                status = "need_input";
            } else {
                try {
                    double v = FormulaEvaluator.evaluate(spec.formula(), vars);
                    if (spec.floorZero() && v < 0) {
                        warnings.add("公式结果 " + round2(v) + " 小于 0，按 0 计");
                        v = 0;
                    }
                    score = round2(v);
                    status = "ok";
                    overallVars.put(spec.name(), score);
                } catch (RuntimeException e) {
                    status = "need_input";
                    warnings.add("公式求值失败：" + e.getMessage());
                    issues.add(spec.name() + " 的公式「" + spec.formula() + "」求值失败：" + e.getMessage());
                }
            }
            if (status.equals("need_input")) {
                allScored = false;
            }
            if (spec.note() != null && !spec.note().isBlank()) {
                warnings.add(spec.note());
            }
            dims.add(new ScoreReport.DimensionScore(spec.id(), spec.name(), spec.weight(),
                    spec.formula(), vars, bonus, deduction, score, status, spec.note(), warnings));
        }

        // 总分
        Double overall = null;
        String overallStatus;
        if (materials.isEmpty()) {
            overallStatus = "empty";
        } else if (!allScored) {
            overallStatus = "partial";
            issues.add("有维度缺少基础分输入，总分暂时算不出来");
        } else {
            try {
                overall = round2(FormulaEvaluator.evaluate(cfg.overallFormula(), overallVars));
                overallStatus = "ok";
            } catch (RuntimeException e) {
                overallStatus = "partial";
                issues.add("总分公式求值失败：" + e.getMessage() + "（可用变量：" + overallVars.keySet() + "）");
            }
        }

        List<ScoreReport.ScoreLine> lines = drafts.stream().map(Draft::toLine).toList();
        return new ScoreReport(cfg.school(), cfg.college(), cfg.ruleVersion(),
                year.label(), year.start().toString(), year.end().toString(),
                dims, lines, unmatched, issues, overall, overallStatus, List.copyOf(penalties));
    }

    // ── 组内截断：同组最多算 N 项 ────────────────────────────────────

    private static void applyGroupCaps(List<Draft> drafts, RuleConfig cfg, List<String> forced) {
        for (RuleGroup g : cfg.ruleGroups()) {
            Integer cap = g.maxItems();
            if (cap == null || cap <= 0) {
                continue;
            }
            List<Draft> inGroup = drafts.stream()
                    .filter(d -> d.groupId.equals(g.id()) && d.included)
                    .sorted(Comparator.comparingDouble((Draft d) -> d.score == null ? -1e9 : d.score).reversed())
                    .toList();
            int kept = 0;
            for (Draft d : inGroup) {
                // 用户显式勾上的不挤掉：细则的最终解释权在班委，工具只提示不硬压
                if (forced.contains(d.id)) {
                    kept++;
                    continue;
                }
                if (kept >= cap) {
                    d.included = false;
                    d.needsConfirm = true;
                    d.reasons.add("「" + g.name() + "」最多计入 " + cap + " 项，这一项按同组最高原则被挤掉");
                }
                kept++;
            }
        }
    }

    // ── 匹配 ────────────────────────────────────────────────────────

    private record MatchResult(boolean matched, boolean levelUnknown, String columnWanted) {
        static final MatchResult NO = new MatchResult(false, false, null);
    }

    /**
     * 判断一条规则是否命中这份材料。
     *
     * <p>{@code level} 键特殊：材料级别读不出来（null 或「不确定」）时<b>不算未命中</b>——
     * 否则一份看不清颁发单位的证书就永远匹配不上。改成"命中但标记级别未知"，
     * 让各等级的条目都成为候选，由人挑。
     */
    private static MatchResult match(RuleItem item, ExtractedFact f) {
        boolean levelUnknown = false;
        String columnWanted = null;
        String text = join(f.title(), f.achievement());

        for (Map.Entry<String, Object> e : item.match().entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            switch (key) {
                case "doc_type" -> {
                    if (!equalsAny(f.docType(), v)) return MatchResult.NO;
                }
                case "level" -> {
                    String have = f.levelHint();
                    if (have == null || have.isBlank() || "不确定".equals(have.trim())) {
                        levelUnknown = true;
                    } else if (!equalsAny(have, v)) {
                        return MatchResult.NO;
                    }
                }
                case "titles" -> {
                    if (!containsAny(text, v)) return MatchResult.NO;
                }
                case "achievement" -> {
                    if (!containsAny(f.achievement(), v)) return MatchResult.NO;
                }
                case "issuer" -> {
                    if (!containsAny(f.issuer(), v)) return MatchResult.NO;
                }
                case "column" -> columnWanted = v == null ? null : String.valueOf(v);
                case "level_unknown" -> {
                    // 只在「读不出级别」时才让兜底条目参与。没有这一条，院级干事会被
                    // 「级别未定·干事 +6」这条兜底抢走分（引擎默认取同组最高），
                    // 明明读出了院级却按校级给分，是货真价实的多给分。
                    boolean want = "true".equalsIgnoreCase(String.valueOf(v));
                    String have = f.levelHint();
                    boolean unknown = have == null || have.isBlank() || "不确定".equals(have.trim());
                    if (want != unknown) {
                        return MatchResult.NO;
                    }
                }
                default -> {
                    // 未知键：不参与判定，也不让它误伤匹配（配置侧会记问题清单）
                }
            }
        }
        return new MatchResult(true, levelUnknown, columnWanted);
    }

    private static boolean equalsAny(String have, Object want) {
        if (have == null) {
            return false;
        }
        List<String> wants = asStrings(want);
        return wants.stream().anyMatch(w -> w.equalsIgnoreCase(have.trim()));
    }

    private static boolean containsAny(String haystack, Object want) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        List<String> wants = asStrings(want);
        return wants.stream().anyMatch(w -> !w.isEmpty() && haystack.contains(w));
    }

    private static List<String> asStrings(Object v) {
        if (v instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(v));
    }

    // ── 等次列定位 ──────────────────────────────────────────────────

    private static Integer columnIndex(RuleGroup g, RuleItem item, ExtractedFact f, String wanted) {
        List<String> cols = g.columns();
        if (cols == null || cols.isEmpty()) {
            return null;
        }
        if (wanted != null) {
            for (int i = 0; i < cols.size(); i++) {
                if (norm(cols.get(i)).equals(norm(wanted))) {
                    return i;
                }
            }
        }
        String ach = f.achievement();
        if (ach == null || ach.isBlank()) {
            return null;
        }
        String na = norm(ach);
        for (int i = 0; i < cols.size(); i++) {
            String nc = norm(cols.get(i));
            if (nc.isEmpty()) {
                continue;
            }
            if (na.contains(nc) || nc.contains(na)) {
                return i;
            }
        }
        return null;
    }

    /**
     * 归一化等次文本：去掉「奖 / 类 / 项 / 名 / 别 / 等」这些装饰字再比。
     * 这样「三等奖」能对上列名「三等类」，「一等奖」对「一等类」。
     */
    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s奖类项名别等第\\-—/、]", "");
    }

    // ── 杂项 ────────────────────────────────────────────────────────

    private static boolean isNonCertificate(ExtractedFact f) {
        String t = f.docType();
        return t != null && "其他".equals(t.trim());
    }

    /**
     * 材料判定的「育」（AI 的 {@code category_guess}）与规则组的「育」是否兼容。
     *
     * <p>AI 判成「其他 / 不确定 / 空」时不拦——不能因为一个字段没识别出来就把整份材料废掉。
     * 只要两边都明确表态且对不上，才认定为跨育误命中。
     */
    private static boolean dimensionCompatible(ExtractedFact f, RuleGroup g, RuleConfig cfg) {
        String said = f.categoryGuess();
        if (said == null || said.isBlank()) {
            return true;
        }
        String s = said.trim();
        if ("其他".equals(s) || "不确定".equals(s)) {
            return true;
        }
        DimensionSpec spec = cfg.dimensionIndex().get(g.dimension());
        String target = spec == null ? g.dimension() : spec.name();
        return s.contains(target) || target.contains(s);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                sb.append(p).append(' ');
            }
        }
        return sb.toString();
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    /** 内部可变草稿：算分过程中要多次调整 selected / included / reasons */
    private static final class Draft {
        String id;
        int materialIndex;
        String fileName;
        String dimension;
        String groupId;
        String groupName;
        String basis;
        String evidence;
        Double score;
        boolean selected;
        boolean included;
        boolean manual;
        Boolean inWindow;
        boolean needsConfirm;
        List<String> reasons;

        ScoreReport.ScoreLine toLine() {
            return new ScoreReport.ScoreLine(id, materialIndex, fileName, dimension,
                    groupId, groupName, basis, score, selected, included, manual, inWindow,
                    needsConfirm, String.join("；", new LinkedHashSet<>(reasons)), evidence);
        }
    }
}
