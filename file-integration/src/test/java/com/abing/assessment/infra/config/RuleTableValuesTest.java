package com.abing.assessment.infra.config;

import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.RuleGroup;
import com.abing.assessment.domain.model.RuleItem;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import com.abing.assessment.domain.rules.ScoreEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 把细则 PDF 里那几张表的<b>具体数字</b>钉死在测试里。
 *
 * <p>为什么值得单独一个类：{@link RuleConfigContractTest} 只保证「结构对」，结构对而
 * 数字错是最危险的——算出来一个看着正常的分数，没人会去逐格核对 PDF。
 * 2026-10-28 那次核对是人工把 P23–P37 渲染成图片一格一格看的，成果必须留得住。
 *
 * <p>改细则 → 改 YAML → 这里红了 → 对着 PDF 再核一遍。这才是这条测试的用法，
 * 不是"看到红了就把期望值改掉"。
 */
class RuleTableValuesTest {

    private static RuleConfig cfg;

    @BeforeAll
    static void load() {
        YamlRuleLoader loader = new YamlRuleLoader();
        loader.load();
        assumeTrue(loader.configDir() != null, "没有找到 configs 目录");
        cfg = loader.rules().get(0);
    }

    // ── 德育 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("学生干部：班级 12/10/6/3/2，院级 12/8/5，校级 15/10/6（P19–P21）")
    void studentOfficer() {
        RuleGroup g = group("student_officer");
        assertEquals(12d, itemByTitle(g, "班长").score());
        assertEquals(10d, itemByTitle(g, "学习委员").score());
        assertEquals(6d, itemByTitle(g, "班委").score());
        assertEquals(3d, itemByTitle(g, "宿舍长").score());
        assertEquals(2d, itemByTitle(g, "课代表").score());
        assertEquals(12d, itemByLevel(g, "院级", "常委").score());
        assertEquals(8d, itemByLevel(g, "院级", "部长").score());
        assertEquals(5d, itemByLevel(g, "院级", "干事").score());
        assertEquals(15d, itemByLevel(g, "校级", "常委").score());
        assertEquals(10d, itemByLevel(g, "校级", "部长").score());
        assertEquals(6d, itemByLevel(g, "校级", "干事").score());
    }

    @Test
    @DisplayName("集体荣誉：国家级 20/20/15、省级 15/12/8、校级 6/5/3（P23）")
    void honorCollective() {
        RuleGroup g = group("honor_collective");
        assertEquals(List.of(20d, 20d, 15d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(15d, 12d, 8d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(6d, 5d, 3d), itemByLevel(g, "校级").scores());
    }

    @Test
    @DisplayName("一般性文化活动：国家级 20/18/15/10/8 …院级 5/4/3/2/1（P24）")
    void activityAward() {
        RuleGroup g = group("activity_award");
        assertEquals(5, g.columns().size());
        assertEquals(List.of(20d, 18d, 15d, 10d, 8d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(15d, 12d, 10d, 6d, 4d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(8d, 6d, 4d, 3d, 2d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(5d, 4d, 3d, 2d, 1d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("三下乡：省级 15/12/10/8/5、校级 10/8/6/5/3、院级 8/6/5/4/2（P25）")
    void summerPractice() {
        RuleGroup g = group("summer_practice");
        assertEquals(List.of(15d, 12d, 10d, 8d, 5d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(10d, 8d, 6d, 5d, 3d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(8d, 6d, 5d, 4d, 2d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("单列类：重大贡献 15 / 见义勇为 12 / 献血 8 / 志愿 0.25 每小时（P27）")
    void standalone() {
        RuleGroup g = group("standalone");
        assertEquals(15d, itemByTitle(g, "重大贡献").score());
        assertEquals(12d, itemByTitle(g, "见义勇为").score());
        assertEquals(10d, itemByTitle(g, "好人好事").score());
        assertEquals(8d, itemByTitle(g, "献血").score());
        RuleItem volunteer = itemByTitle(g, "志愿");
        assertEquals(0.25d, volunteer.score());
        assertEquals("小时", volunteer.unit());
        assertEquals(10d, volunteer.cap());
    }

    // ── 智育 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("竞赛：国家级 20/18/15/12/10、院级 8/6/4/3/2（P31）")
    void competition() {
        RuleGroup g = group("competition");
        assertEquals(List.of(20d, 18d, 15d, 12d, 10d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(15d, 12d, 10d, 8d, 5d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(10d, 8d, 6d, 4d, 3d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(8d, 6d, 4d, 3d, 2d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("科研立项：20/15/15/10/10/5/4/2（P32，该项无院级）")
    void researchProject() {
        RuleGroup g = group("research_project");
        assertEquals(8, g.columns().size());
        assertEquals(List.of(20d, 15d, 15d, 10d, 10d, 5d, 4d, 2d), g.items().get(0).scores());
    }

    @Test
    @DisplayName("专利：发明 10/5/8/4/6/3，实用新型与外观设计的「受理」列不计分（P34）")
    void patent() {
        RuleGroup g = group("patent");
        assertEquals(List.of(10d, 5d, 8d, 4d, 6d, 3d), itemByTitle(g, "发明专利").scores());
        // null = 细则原文写「--」，该项不计分。不能用 List.of（它不收 null）
        assertEquals(Arrays.asList(6d, null, 4d, null, 3d, null), itemByTitle(g, "实用新型").scores());
        assertEquals(Arrays.asList(4d, null, 3d, null, 2d, null), itemByTitle(g, "外观设计").scores());
    }

    @Test
    @DisplayName("外语证书：四级 5 / 六级 8 / 专业四级 10（P35）")
    void foreignLang() {
        assertEquals(List.of(5d, 8d, 10d), group("foreign_lang").items().get(0).scores());
        assertEquals(List.of(5d, 8d, 10d), group("skill_cert").items().get(0).scores());
    }

    // ── 体育 / 美育 / 劳育 ──────────────────────────────────────────

    @Test
    @DisplayName("大型运动会：省级 25/15/14/13/12/11/10/10/5，共 9 列（P36）")
    void sportsMeet() {
        RuleGroup g = group("sports_meet");
        assertEquals(9, g.columns().size());
        assertEquals(List.of(35d, 25d, 24d, 23d, 22d, 21d, 20d, 20d, 8d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(25d, 15d, 14d, 13d, 12d, 11d, 10d, 10d, 5d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(15d, 8d, 7d, 6d, 5d, 4d, 3d, 2d, 4d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(10d, 6d, 5d, 4d, 3d, 2d, 1d, 1d, 2d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("一般性体育：全国 20/18/15/12/10、院级 6/5/4/3/2（P37）")
    void sportsGeneral() {
        RuleGroup g = group("sports_general");
        assertEquals(List.of(20d, 18d, 15d, 12d, 10d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(15d, 12d, 10d, 8d, 5d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(8d, 7d, 6d, 4d, 3d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(6d, 5d, 4d, 3d, 2d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("美育：校级 8/7/6/4/3、院级 6/5/4/3/2（P38）")
    void aesthetic() {
        RuleGroup g = group("aesthetic_award");
        assertEquals(List.of(20d, 18d, 15d, 12d, 10d), itemByLevel(g, "国家级").scores());
        assertEquals(List.of(15d, 12d, 10d, 8d, 5d), itemByLevel(g, "省级").scores());
        assertEquals(List.of(8d, 7d, 6d, 4d, 3d), itemByLevel(g, "校级").scores());
        assertEquals(List.of(6d, 5d, 4d, 3d, 2d), itemByLevel(g, "院级").scores());
    }

    @Test
    @DisplayName("星级宿舍：校级五星 10/8、院级五星 5/3；劳模国家级 25（P39）")
    void labour() {
        RuleGroup dorm = group("dorm_star");
        assertEquals(List.of(10d, 8d), itemByTitle(dorm, "五星宿舍").scores());
        assertEquals(List.of(8d, 6d), itemByTitle(dorm, "四星宿舍").scores());
        assertEquals(List.of(6d, 4d), itemByTitle(dorm, "三星宿舍").scores());
        assertEquals(List.of(5d, 3d), itemByTitle(dorm, "院级五星").scores());
        assertEquals(25d, itemByLevel(group("labour_model"), "国家级").score());
        assertEquals(10d, itemByLevel(group("labour_model"), "校级").score());
    }

    // ── 引擎行为：这两条是"静默多给分"的防线 ─────────────────────────

    @Test
    @DisplayName("读出了院级，就不能被「级别未定」兜底条目按校级给 6 分")
    void levelUnknownFallbackDoesNotSteal() {
        ScoreReport r = evaluate(fact("融媒体中心干事", "干事", "院级", "德育", "聘书"));
        assertEquals(1, r.lines().size(), "不该出现两条候选：" + r.lines());
        assertEquals(5d, r.lines().get(0).score(), "院级干事是 5 分");
    }

    @Test
    @DisplayName("级别真的读不出来时，兜底条目才出现，并且标成待确认")
    void levelUnknownFallbackKicksIn() {
        ScoreReport r = evaluate(fact("干事聘书", "干事", null, "德育", "聘书"));
        assertTrue(r.lines().stream().anyMatch(l -> l.score() != null && l.score() == 6d));
        assertTrue(r.lines().stream().anyMatch(ScoreReport.ScoreLine::needsConfirm));
    }

    @Test
    @DisplayName("同一张摄影证书不会被德育和智育各算一遍（组级「育」过滤）")
    void dimensionGuardPreventsDoubleCounting() {
        ScoreReport r = evaluate(fact("校园摄影大赛", "三等奖", "校级", "美育", "证书"));

        List<String> dims = r.lines().stream().map(ScoreReport.ScoreLine::dimension).distinct().toList();
        assertEquals(List.of("aesthetic"), dims, "只该落在美育：" + r.lines());
        assertEquals(6d, r.lines().get(0).score());
    }

    @Test
    @DisplayName("AI 判成「其他」时不拦，仍按规则匹配（不能因一个字段没识别就废掉材料）")
    void dimensionGuardAllowsOther() {
        ScoreReport r = evaluate(fact("校园摄影大赛", "三等奖", "校级", "其他", "证书"));
        assertFalse(r.lines().isEmpty());
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private static RuleGroup group(String id) {
        return cfg.ruleGroups().stream().filter(g -> g.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("没有规则组 " + id));
    }

    private static RuleItem itemByLevel(RuleGroup g, String level) {
        return g.items().stream()
                .filter(it -> level.equals(String.valueOf(it.match().get("level"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError(g.id() + " 里没有 level=" + level + " 的条目"));
    }

    private static RuleItem itemByLevel(RuleGroup g, String level, String titleKeyword) {
        return g.items().stream()
                .filter(it -> level.equals(String.valueOf(it.match().get("level"))))
                .filter(it -> String.valueOf(it.match().get("titles")).contains(titleKeyword))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        g.id() + " 里没有 level=" + level + " 且含「" + titleKeyword + "」的条目"));
    }

    private static RuleItem itemByTitle(RuleGroup g, String keyword) {
        return g.items().stream()
                .filter(it -> String.valueOf(it.match().get("titles")).contains(keyword))
                .filter(it -> it.match().get("level") == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(g.id() + " 里没有含「" + keyword + "」的条目"));
    }

    private static ExtractedFact fact(String title, String achievement, String level,
                                     String category, String docType) {
        return new ExtractedFact(docType, "廖展滨", false, "广东金融学院", "2026-05-10",
                null, null, title, achievement, level, category, title + " " + achievement, 0.9);
    }

    private static ScoreReport evaluate(ExtractedFact f) {
        ScoreInput in = new ScoreInput(
                List.of(new ScoreInput.Material("测试.jpg", f)),
                "2025-2026",
                Map.of("moral.base", 80d, "moral.peer", 90d, "intellect.base", 85d,
                        "physique.base", 75d, "aesthetic.base", 80d, "labour.base", 80d),
                Map.of(), List.of(), List.of(), List.of());
        return ScoreEngine.evaluate(in, cfg);
    }
}
