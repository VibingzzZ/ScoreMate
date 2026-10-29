package com.abing.assessment.infra.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 从细则里抽出来的规则草稿。<b>它不是配置，是待确认的中间态</b>——细则表是
 * 「级别 × 等次」的二维表，读错一格就是给全班算错分，且在页面上看不出来。
 *
 * <p>{@code problems} / {@code pagesRead} 让核对有重点（读了几页、哪几页不确定），
 * 而不是逐格重看 20 页。
 *
 * @param sourceFile  原始文件名，写进 meta.source_file 供明年对照
 * @param groups      识别到的规则组（按维度 + 组名归并）
 * @param problems    读不准 / 结构对不齐 / 某页没表，如实带出
 * @param totalPages  文件实际页数
 * @param pagesRead   实际送模型的页数（受上限截断）
 * @param totalTokens 本次消耗的 token
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
     * 找出「条件相同、分值不同」的条目——写进 YAML 后会互相抢分。
     *
     * <p>典型来源是表里有「角色」列而非等次（星级宿舍：寝室长 10 / 成员 8）。模型吐成两条后，
     * match 完全相同，引擎分不清谁是谁，只会按其中一条给分，<b>不报错也不提示</b>。
     * 调用方据此把条目标成人工定分，让用户自己填。
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
