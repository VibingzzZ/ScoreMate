package com.abing.assessment.domain.rules;

import com.abing.assessment.domain.model.DimensionSpec;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.RuleGroup;
import com.abing.assessment.domain.model.RuleItem;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎的表驱动测试。
 *
 * <p>这是整个项目"算分可复现"的证明：每一行断言都能直接说清
 * 「哪份材料 + 哪条细则 → 多少分 → 为什么」。
 */
class ScoreEngineTest {

    // ── 构造工具 ────────────────────────────────────────────────────

    private static ExtractedFact fact(String docType, String title, String achievement,
                                      String level, String date) {
        return new ExtractedFact(docType, "廖展滨", Boolean.FALSE, "广东金融学院",
                date, null, null, title, achievement, level, null, "原文摘录", 0.9);
    }

    private static RuleItem item(Map<String, Object> match, Double score) {
        return new RuleItem(match, score, null, null, null, false, null);
    }

    private static RuleItem matrix(Map<String, Object> match, List<Double> scores, String note) {
        return new RuleItem(match, null, scores, null, null, false, note);
    }

    private static Map<String, Object> match(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static DimensionSpec moral() {
        return new DimensionSpec("moral", "德育", 0.2,
                "base*0.30 + peer*0.20 + bonus*0.50 - deduction", true,
                List.of(new DimensionSpec.InputSpec("base", "德育基础分", null),
                        new DimensionSpec.InputSpec("peer", "互评分", null)),
                null);
    }

    private static DimensionSpec intellect() {
        return new DimensionSpec("intellect", "智育", 0.6, "base*0.80 + bonus*0.20", false,
                List.of(new DimensionSpec.InputSpec("base", "智育基础分", null)), null);
    }

    private static RuleConfig config(List<RuleGroup> groups) {
        return new RuleConfig("广东金融学院", "计算机学院", "2026-07-02", "test.yaml",
                Path.of("test.yaml"),
                Map.of("overall", "德育*0.20 + 智育*0.60"),
                Map.of("time_window", true),
                List.of(moral(), intellect()), groups, Map.of());
    }

    /** 学生干部组：班级 助理 10 分 / 院级 干事 5 分 / 校级 干事 6 分 */
    private static RuleGroup officerGroup(int maxItems) {
        return new RuleGroup("student_officer", "moral", "bonus", "学生干部", maxItems, List.of(),
                List.of(),
                List.of(item(match("doc_type", "聘书", "level", "班级", "titles", List.of("班主任助理")), 10d),
                        item(match("doc_type", "聘书", "level", "院级", "titles", List.of("干事")), 5d),
                        item(match("doc_type", "聘书", "level", "校级", "titles", List.of("干事")), 6d)),
                null);
    }

    private static RuleGroup honorGroup(int maxItems) {
        return new RuleGroup("honor_individual", "moral", "bonus", "个人荣誉", maxItems, List.of(),
                List.of(),
                List.of(item(match("level", "校级", "titles", List.of("先进个人")), 6d),
                        item(match("level", "院级", "titles", List.of("先进个人")), 4d)),
                null);
    }

    private static ScoreInput input(String year, List<ScoreInput.Material> materials,
                                    Map<String, Double> inputs) {
        return new ScoreInput(materials, year, inputs, Map.of(), List.of(), List.of(), List.of());
    }

    private static Map<String, Double> fullInputs() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("moral.base", 80d);
        m.put("moral.peer", 90d);
        m.put("intellect.base", 85d);
        return m;
    }

    private static ScoreReport.ScoreLine lineOf(ScoreReport r, String groupId, int materialIndex) {
        return r.lines().stream()
                .filter(l -> l.groupId().equals(groupId) && l.materialIndex() == materialIndex)
                .findFirst().orElseThrow(() -> new AssertionError("没有 groupId=" + groupId + " 的行"));
    }

    // ── 1. 窗口校验 ────────────────────────────────────────────────

    @Nested
    @DisplayName("时间窗口")
    class Window {

        @Test
        @DisplayName("学年内的聘书：算分且计入")
        void inWindow() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("聘书.jpg",
                                    fact("聘书", "班主任助理", null, "班级", "2025-09-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            ScoreReport.ScoreLine l = lineOf(r, "student_officer", 0);
            assertEquals(10d, l.score());
            assertEquals(Boolean.TRUE, l.inWindow());
            assertTrue(l.included());
            assertFalse(l.needsConfirm());
        }

        @Test
        @DisplayName("跨学年的荣誉：标 inWindow=false 且不计入，但绝不删除")
        void outOfWindowKeptButNotCounted() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("军训.jpg",
                                    fact("荣誉证书", "军训先进个人", null, "校级", "2024-09-24"))),
                            fullInputs()),
                    config(List.of(honorGroup(2))));

            ScoreReport.ScoreLine l = lineOf(r, "honor_individual", 0);
            assertEquals(6d, l.score());
            assertEquals(Boolean.FALSE, l.inWindow());
            assertFalse(l.included(), "超出学年不应计入合计");
            assertTrue(l.needsConfirm());
            assertTrue(l.reason().contains("不在"), "要说清为什么不计入：" + l.reason());
        }

        @Test
        @DisplayName("日期读不出来：inWindow=null，不是 false")
        void unreadableDate() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("荣誉证书", "先进个人", null, "校级", null))),
                            fullInputs()),
                    config(List.of(honorGroup(2))));

            ScoreReport.ScoreLine l = lineOf(r, "honor_individual", 0);
            assertNull(l.inWindow(), "读不出日期是「不知道」，不是「不在窗口内」");
            assertTrue(l.included(), "不知道就照算，交由人确认");
        }
    }

    // ── 2. 级别不明 → 候选 ─────────────────────────────────────────

    @Nested
    @DisplayName("级别未确定")
    class LevelUnknown {

        @Test
        @DisplayName("列出各等级候选，暂定最高分那条，并标记待确认")
        void candidatesWithTentativePick() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("聘书.jpg",
                                    fact("聘书", "影像部干事", null, "不确定", "2025-10-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            List<ScoreReport.ScoreLine> rows = r.lines().stream()
                    .filter(l -> l.groupId().equals("student_officer")).toList();
            assertEquals(2, rows.size(), "院级干事与校级干事都应成为候选");
            assertEquals(1, rows.stream().filter(ScoreReport.ScoreLine::selected).count());

            ScoreReport.ScoreLine picked = rows.stream()
                    .filter(ScoreReport.ScoreLine::selected).findFirst().orElseThrow();
            assertEquals(6d, picked.score(), "默认取最高分那条");
            assertTrue(picked.needsConfirm());
            assertTrue(picked.reason().contains("候选"));
        }

        @Test
        @DisplayName("用户改选候先后，计入的分数随之变化")
        void userChoiceOverrides() {
            RuleConfig cfg = config(List.of(officerGroup(2)));
            List<ScoreInput.Material> materials = List.of(new ScoreInput.Material("聘书.jpg",
                    fact("聘书", "影像部干事", null, "不确定", "2025-10-01")));

            ScoreReport auto = ScoreEngine.evaluate(input("2025-2026", materials, fullInputs()), cfg);
            String lowId = auto.lines().stream()
                    .filter(l -> l.score() != null && l.score() == 5d)
                    .findFirst().orElseThrow().id();

            ScoreReport picked = ScoreEngine.evaluate(
                    new ScoreInput(materials, "2025-2026", fullInputs(),
                            Map.of("0:student_officer", lowId), List.of(), List.of(), List.of()),
                    cfg);

            ScoreReport.ScoreLine l = picked.lines().stream()
                    .filter(ScoreReport.ScoreLine::selected).findFirst().orElseThrow();
            assertEquals(5d, l.score());
        }
    }

    // ── 3. 同组截断 ────────────────────────────────────────────────

    @Nested
    @DisplayName("同组最多计 N 项")
    class Caps {

        @Test
        @DisplayName("三份聘书只计最高的两项，被挤掉的仍在结果里")
        void keepTopN() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                            fact("聘书", "班主任助理", null, "班级", "2025-09-01")),
                                    new ScoreInput.Material("b.jpg",
                                            fact("聘书", "班主任助理", null, "班级", "2025-10-01")),
                                    new ScoreInput.Material("c.jpg",
                                            fact("聘书", "班主任助理", null, "班级", "2025-11-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            List<ScoreReport.ScoreLine> rows = r.lines().stream()
                    .filter(l -> l.groupId().equals("student_officer")).toList();
            assertEquals(3, rows.size(), "被挤掉的那条不能被删掉");
            assertEquals(2, rows.stream().filter(ScoreReport.ScoreLine::included).count());
            assertTrue(rows.stream().anyMatch(l -> l.reason().contains("最多计入 2 项")));
            // 德育加分 = 10 + 10
            assertEquals(20d, r.dimensions().stream()
                    .filter(d -> d.id().equals("moral")).findFirst().orElseThrow().bonus());
        }

        @Test
        @DisplayName("max_items=1 表达「同一类别取最高」")
        void highestOnly() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("聘书", "影像部干事", null, "院级", "2025-09-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(1))));

            assertEquals(1, r.lines().stream().filter(ScoreReport.ScoreLine::included).count());
        }
    }

    // ── 4. 维度聚合与公式 ─────────────────────────────────────────

    @Nested
    @DisplayName("维度聚合")
    class Aggregation {

        @Test
        @DisplayName("德育 = 基础×0.3 + 互评×0.2 + 加分×0.5")
        void moralFormula() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("聘书", "班主任助理", null, "班级", "2025-09-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            ScoreReport.DimensionScore moral = r.dimensions().stream()
                    .filter(d -> d.id().equals("moral")).findFirst().orElseThrow();
            assertEquals(10d, moral.bonus());
            assertEquals(0d, moral.deduction());
            // 80*0.3 + 90*0.2 + 10*0.5 = 24 + 18 + 5 = 47
            assertEquals(47d, moral.score(), 0.001);
            assertEquals("ok", moral.status());
        }

        @Test
        @DisplayName("缺基础分时维度算不出来，但加分照常显示")
        void missingInput() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("聘书", "班主任助理", null, "班级", "2025-09-01"))),
                            Map.of("intellect.base", 85d)),
                    config(List.of(officerGroup(2))));

            ScoreReport.DimensionScore moral = r.dimensions().stream()
                    .filter(d -> d.id().equals("moral")).findFirst().orElseThrow();
            assertEquals("need_input", moral.status());
            assertNull(moral.score());
            assertEquals(10d, moral.bonus(), "加分不该因为缺基础分而消失");
            assertNull(r.overall());
            assertEquals("partial", r.status());
        }

        @Test
        @DisplayName("扣分大于前三项时德育归零")
        void floorZero() {
            RuleGroup deduction = new RuleGroup("moral_deduction", "moral", "deduction", "德育扣分",
                    null, List.of(), List.of(),
                    List.of(item(match("titles", List.of("作弊")), -200d)), null);

            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("处分", "考试作弊", null, "校级", "2025-10-01"))),
                            fullInputs()),
                    config(List.of(deduction)));

            ScoreReport.DimensionScore moral = r.dimensions().stream()
                    .filter(d -> d.id().equals("moral")).findFirst().orElseThrow();
            assertEquals(200d, moral.deduction());
            assertEquals(0d, moral.score(), "负分应归零");
            assertTrue(moral.warnings().stream().anyMatch(w -> w.contains("按 0 计")));
        }

        @Test
        @DisplayName("总分 = 德×0.2 + 智×0.6")
        void overall() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("聘书", "班主任助理", null, "班级", "2025-09-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            // 德 47 * 0.2 + 智 (85*0.8=68) * 0.6 = 9.4 + 40.8 = 50.2
            assertEquals(50.2d, r.overall(), 0.001);
            assertEquals("ok", r.status());
        }
    }

    // ── 5. 二维矩阵 ───────────────────────────────────────────────

    @Nested
    @DisplayName("二维矩阵（等次查表）")
    class Matrix {

        private RuleGroup activity() {
            return new RuleGroup("activity_award", "moral", "bonus", "主题教育活动获奖", null,
                    List.of("一等类", "二等类", "三等类", "最佳类", "优秀类", "优胜类", "参与未获奖"),
                    List.of(),
                    List.of(matrix(match("level", "校级", "titles", List.of("演讲")),
                            java.util.Arrays.asList(8d, 6d, 4d, 3d, null, null, null), null),
                            matrix(match("level", "院级", "titles", List.of("演讲")),
                                    java.util.Arrays.asList(5d, 4d, 3d, 2d, null, null, null), null)),
                    null);
        }

        @Test
        @DisplayName("「三等奖」能对上列名「三等类」")
        void thirdPrize() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("获奖证书", "演讲比赛", "三等奖", "校级", "2025-10-01"))),
                            fullInputs()),
                    config(List.of(activity())));

            ScoreReport.ScoreLine l = lineOf(r, "activity_award", 0);
            assertEquals(4d, l.score());
            assertTrue(l.included());
        }

        @Test
        @DisplayName("等次对不上时不定分，交人工 —— 绝不默认给个中间值")
        void unknownGrade() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("a.jpg",
                                    fact("获奖证书", "演讲比赛", "特等奖", "校级", "2025-10-01"))),
                            fullInputs()),
                    config(List.of(activity())));

            ScoreReport.ScoreLine l = lineOf(r, "activity_award", 0);
            assertNull(l.score());
            assertTrue(l.manual());
            assertFalse(l.included());
            assertTrue(l.reason().contains("等次"));
        }
    }

    // ── 6. 未匹配与排除 ───────────────────────────────────────────

    @Nested
    @DisplayName("未匹配 / 排除")
    class UnmatchedAndExclude {

        @Test
        @DisplayName("匹配不到任何规则的材料进 unmatched，不静默丢弃")
        void unmatchedKept() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("照片.jpg",
                                    fact("获奖证书", "摄影比赛", "三等奖", "校级", "2025-10-01"))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            assertEquals(1, r.unmatched().size());
            assertEquals("照片.jpg", r.unmatched().get(0).fileName());
            assertTrue(r.unmatched().get(0).reason().contains("没有匹配"));
        }

        @Test
        @DisplayName("doc_type=其他 不进 unmatched 噪音区，单独说明")
        void nonCertificate() {
            ScoreReport r = ScoreEngine.evaluate(
                    input("2025-2026",
                            List.of(new ScoreInput.Material("思维导图.jpg",
                                    fact("其他", null, null, null, null))),
                            fullInputs()),
                    config(List.of(officerGroup(2))));

            assertTrue(r.unmatched().get(0).reason().contains("不是证明材料"));
        }

        @Test
        @DisplayName("用户手动排除的条目不进合计")
        void excluded() {
            RuleConfig cfg = config(List.of(officerGroup(2)));
            List<ScoreInput.Material> materials = List.of(new ScoreInput.Material("a.jpg",
                    fact("聘书", "班主任助理", null, "班级", "2025-09-01")));

            ScoreReport auto = ScoreEngine.evaluate(input("2025-2026", materials, fullInputs()), cfg);
            String id = auto.lines().get(0).id();

            ScoreReport r = ScoreEngine.evaluate(
                    new ScoreInput(materials, "2025-2026", fullInputs(), Map.of(), List.of(id), List.of(), List.of()), cfg);

            assertFalse(r.lines().get(0).included());
            assertEquals(0d, r.dimensions().stream().filter(d -> d.id().equals("moral"))
                    .findFirst().orElseThrow().bonus());
        }

        @Test
        @DisplayName("超出学年但用户勾上「强制计入」，就该计入")
        void forcedInclude() {
            RuleConfig cfg = config(List.of(honorGroup(2)));
            List<ScoreInput.Material> materials = List.of(new ScoreInput.Material("军训.jpg",
                    fact("荣誉证书", "军训先进个人", null, "校级", "2024-09-24")));

            ScoreReport auto = ScoreEngine.evaluate(input("2025-2026", materials, fullInputs()), cfg);
            String id = auto.lines().get(0).id();
            assertFalse(auto.lines().get(0).included());

            ScoreReport r = ScoreEngine.evaluate(
                    new ScoreInput(materials, "2025-2026", fullInputs(), Map.of(), List.of(), List.of(id), List.of()), cfg);

            assertTrue(r.lines().get(0).included());
            assertEquals(6d, r.dimensions().stream().filter(d -> d.id().equals("moral"))
                    .findFirst().orElseThrow().bonus());
        }
    }

    // ── 7. 空输入 ─────────────────────────────────────────────────

    @Test
    @DisplayName("没有材料时 status=empty，不抛异常")
    void emptyMaterials() {
        ScoreReport r = ScoreEngine.evaluate(input("2025-2026", List.of(), fullInputs()),
                config(List.of(officerGroup(2))));
        assertEquals("empty", r.status());
        assertNull(r.overall());
        assertNotNull(r.dimensions());
    }
}
