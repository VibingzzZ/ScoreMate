package com.abing.assessment.infra.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 从细则文件里抽出来的规则草稿。
 *
 * <p><b>它不是配置，是待确认的中间态。</b>页面必须把它整张表格铺出来让人核对，
 * 确认后才写成 YAML 生效——理由很直接：细则表是「级别 × 等次」的二维表，
 * 模型读错一格就是给全班算错分，而这个错误当场看不出来（要等到某个人拿着奖状来找）。
 *
 * <p>所以 {@code problems} 和 {@code pagesRead} 不是装饰：它们告诉用户
 * 「我读了几页、哪几页不敢确定」，让核对有重点，而不是逐格重看 20 页。
 *
 * @param sourceFile  原始文件名，写进 YAML 的 meta.source_file，方便明年对照
 * @param groups      识别到的规则组（按维度 + 组名归并后的结果）
 * @param problems    读不准 / 结构对不齐 / 某页没识别出表格，全部如实带出来
 * @param totalPages  文件实际页数
 * @param pagesRead   实际送去模型的页数（受上限截断）
 * @param totalTokens 本次导入消耗的 token，给用户一个成本预期
 */
public record RuleDraft(
        String sourceFile,
        List<Group> groups,
        List<String> problems,
        int totalPages,
        int pagesRead,
        int totalTokens
) {

    /**
     * 一个规则组，对应细则里的一个小标题 + 它下面那张表。
     *
     * @param dimension moral / intellect / physique / aesthetic / labour
     * @param kind      bonus（加分）/ penalty（扣分）
     * @param evidence  细则原文的加分说明首句，写进 YAML 注释，将来对账用
     * @param note      表格备注（「身兼多项只能选两项」「不累加」这类决定性规则）
     */
    public record Group(
            String dimension,
            String name,
            String kind,
            String evidence,
            String note,
            List<Item> items
    ) {

        /** 归并键：同一维度下同名组视为同一组（跨页表会分两次吐出来） */
        public String key() {
            return (dimension == null ? "" : dimension) + "|" + (name == null ? "" : name.trim());
        }
    }

    /**
     * 一条「条件 → 分值」。
     *
     * @param level 国家级/省级/市级/校级/院级/不确定
     * @param rank  等次原文（一等奖、干事、一学年…）
     * @param score 分值。null 表示表里没写（这种情况不该发生，出现了要人看）
     * @param note  合并单元格带出来的适用范围（「适用于：团委、学生会…」）
     */
    public record Item(
            String title,
            String level,
            String rank,
            Double score,
            String note
    ) {

        /** 去重键：同一页里同一条件被重复输出时用它收敛 */
        public String key() {
            return (title == null ? "" : title.trim()) + "|" + (level == null ? "" : level)
                    + "|" + (rank == null ? "" : rank.trim()) + "|" + score;
        }
    }

    /** 全部条目数，页面用来显示「共识别 N 条」 */
    public int itemCount() {
        return groups == null ? 0 : groups.stream().mapToInt(g -> g.items().size()).sum();
    }

    /** 没有分值支撑的条目（score=null），必须让用户填或删 */
    public List<Item> unscoredItems() {
        List<Item> out = new ArrayList<>();
        if (groups != null) {
            groups.forEach(g -> g.items().stream().filter(i -> i.score() == null).forEach(out::add));
        }
        return out;
    }

    /**
     * 找出「条件相同、分值不同」的条目——这些条目写进 YAML 后会互相抢分。
     *
     * <p>典型来源：表里有一列是角色而不是等次，比如星级宿舍的「寝室长 10 分 / 普通成员 8 分」。
     * 模型吐成两条独立条目后，转成 YAML 都是 {@code match:{level:校级, titles:[五星]}}，
     * 引擎无法区分谁是谁，只会按某一条给分——<b>这种错不报错、不提示，直接算错</b>，
     * 是整套流程里最危险的一种。
     *
     * <p>所以这里把它挑出来：调用方会把这些条目标成「人工定分」，
     * 页面上变成"需要你自己填"，而不是静默给一个可能是错的分。
     *
     * @return 每条冲突一句人话描述，直接展示给用户
     */
    public List<String> matchConflicts() {
        List<String> out = new ArrayList<>();
        if (groups == null) {
            return out;
        }
        for (Group g : groups) {
            // key = 级别 + 匹配词；value = 出现过的分值集合
            java.util.Map<String, java.util.Set<Double>> byKey = new java.util.LinkedHashMap<>();
            for (Item it : g.items()) {
                String keyword = it.rank() != null ? it.rank() : it.title();
                String key = (it.level() == null ? "任意级别" : it.level()) + " · " + keyword;
                byKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(it.score());
            }
            byKey.forEach((key, scores) -> {
                if (scores.size() > 1) {
                    out.add("「" + g.name() + "」里条件相同的条目有 " + scores.size()
                            + " 个不同分值（" + key + " → " + scores + "）。"
                            + "多半是表里还有一列角色（如寝室长 / 普通成员）没被区分，"
                            + "这些条目已标为人工定分");
                }
            });
        }
        return out;
    }
}
