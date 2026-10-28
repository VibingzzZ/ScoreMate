package com.abing.assessment.domain.export;

import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包结构是"交上去会被退回"的硬规格，所以全部用单测钉死。
 * 规格来源：材料2《综测评定注意事项.pdf》第 3 页（架构文档 §5.5）。
 */
class PackPlannerTest {

    // ── 规格 1：文件夹顺序 ─────────────────────────────────────────────

    @Test
    @DisplayName("文件夹顺序是德智美体劳，美育在体育前")
    void folderOrder() {
        PackPlan plan = PackPlanner.plan(report(List.of(), List.of()), List.of(), owner());

        assertEquals(List.of("德育", "智育", "美育", "体育", "劳育"),
                plan.folders().stream().map(PackPlan.Folder::name).toList());
    }

    @Test
    @DisplayName("没有任何材料时五个文件夹也要存在（结构是硬规格）")
    void emptyFoldersStillExist() {
        PackPlan plan = PackPlanner.plan(report(List.of(), List.of()), List.of(), owner());

        assertEquals(5, plan.folders().size());
        assertTrue(plan.folders().stream().allMatch(f -> f.items().isEmpty()));
    }

    // ── 规格 2：每个文件夹独立编号 + 命名 ──────────────────────────────

    @Test
    @DisplayName("文件夹内按加分明细行序编号，不是按上传顺序")
    void numberingFollowsLineOrder() {
        // 上传顺序：0=聘书 1=获奖证书；但明细里先出现 1
        List<ScoreInput.Material> materials = List.of(
                material("聘书.jpg", "融媒体中心干事", null),
                material("获奖证书.png", "校园摄影大赛", "三等奖"));

        List<ScoreReport.ScoreLine> lines = List.of(
                line(1, "moral", "culture_award", 6, true),
                line(0, "moral", "student_officer", 10, true));

        PackPlan plan = PackPlanner.plan(report(lines, List.of()), materials, owner());

        assertEquals("材料1：校园摄影大赛+三等奖.png", itemOf(plan, "德育", 1).newName());
        assertEquals("材料2：融媒体中心干事.jpg", itemOf(plan, "德育", 0).newName());
    }

    @Test
    @DisplayName("每个文件夹各自从材料1开始编号，不跨文件夹连续")
    void eachFolderRestartsAtOne() {
        List<ScoreInput.Material> materials = List.of(
                material("证书A.jpg", "蓝桥杯", "国赛一等奖"),
                material("证书B.png", "校园摄影大赛", "三等奖"));

        List<ScoreReport.ScoreLine> lines = List.of(
                line(0, "intellect", "innovation", 20, true),
                line(1, "aesthetic", "art_award", 5, true));

        PackPlan plan = PackPlanner.plan(report(lines, List.of()), materials, owner());

        assertEquals("材料1：蓝桥杯+国赛一等奖.jpg", itemOf(plan, "智育", 0).newName());
        assertEquals("材料1：校园摄影大赛+三等奖.png", itemOf(plan, "美育", 1).newName());
    }

    @Test
    @DisplayName("同一份材料跨两个育时，两边都从材料1开始，各放一份")
    void sameMaterialCrossDimensionRestartsNumber() {
        List<ScoreInput.Material> materials = List.of(material("证书.jpg", "蓝桥杯", "国赛一等奖"));
        List<ScoreReport.ScoreLine> lines = List.of(
                line(0, "intellect", "innovation", 20, true),
                line(0, "moral", "honor", 6, true));

        PackPlan plan = PackPlanner.plan(report(lines, List.of()), materials, owner());

        assertEquals("材料1：蓝桥杯+国赛一等奖.jpg", itemOf(plan, "智育", 0).newName());
        assertEquals("材料1：蓝桥杯+国赛一等奖.jpg", itemOf(plan, "德育", 0).newName());
        // packedCount 按原件去重，同一份材料进了两个文件夹仍算 1 份原件
        assertEquals(1, plan.packedCount());
    }

    @Test
    @DisplayName("同一育里命中多条规则，文件只放一份")
    void dedupWithinFolder() {
        List<ScoreInput.Material> materials = List.of(material("聘书.jpg", "融媒体中心干事", null));
        List<ScoreReport.ScoreLine> lines = List.of(
                line(0, "moral", "student_officer", 10, true),
                line(0, "moral", "other_honor", 6, true));

        PackPlan plan = PackPlanner.plan(report(lines, List.of()), materials, owner());

        assertEquals(1, plan.folders().get(0).items().size());
    }

    @Test
    @DisplayName("文件名里的非法字符要清掉，否则解压失败")
    void sanitizeIllegalChars() {
        String name = PackPlanner.rename(1,
                fact("蓝桥杯/国赛:一等奖?*", "a<b>c|d\"e"), "x.jpg");

        assertEquals("材料1：蓝桥杯国赛一等奖+abcde.jpg", name);
    }

    @Test
    @DisplayName("读不出奖项时只留活动名，不硬编一个奖项")
    void noAwardNoPlus() {
        String name = PackPlanner.rename(3, fact("融媒体中心干事", null), "a.jpg");

        assertEquals("材料3：融媒体中心干事.jpg", name);
    }

    @Test
    @DisplayName("连活动名都读不出时退回原文件名")
    void fallbackToOriginalName() {
        String name = PackPlanner.rename(2, fact(null, null), "IMG_20250901.jpg");

        assertEquals("材料2：IMG_20250901.jpg", name);
    }

    // ── 规格 3：未计入材料不静默丢弃 ────────────────────────────────────

    @Test
    @DisplayName("没命中规则的材料进 skipped 并带原因")
    void unmatchedIsReported() {
        List<ScoreInput.Material> materials = List.of(
                material("证书.jpg", "蓝桥杯", "国赛一等奖"),
                material("随便拍的.jpg", null, null));
        List<ScoreReport.ScoreLine> lines = List.of(line(0, "intellect", "innovation", 20, true));

        PackPlan plan = PackPlanner.plan(
                report(lines, List.of(new ScoreReport.Unmatched(1, "随便拍的.jpg", "doc_type=其他"))),
                materials, owner());

        assertEquals(1, plan.skipped().size());
        assertEquals(1, plan.skipped().get(0).materialIndex());
        assertTrue(plan.skipped().get(0).reason().contains("doc_type=其他"));
        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("没进包")));
    }

    @Test
    @DisplayName("超出学年的行未计入时，原因要能读出来")
    void outOfWindowReason() {
        List<ScoreInput.Material> materials = List.of(material("证书.jpg", "蓝桥杯", "国赛一等奖"));
        ScoreReport.ScoreLine out = new ScoreReport.ScoreLine(
                "0:innovation", 0, "证书.jpg", "intellect", "innovation", "创新能力加分",
                "校级一等奖 +8", 8d, true, false, false, false, true,
                "超出评测学年，未计入（如学院认可可手动勾上）", null);

        PackPlan plan = PackPlanner.plan(report(List.of(out), List.of()), materials, owner());

        assertEquals(0, plan.packedCount());
        assertTrue(plan.skipped().get(0).reason().contains("超出评测学年"));
    }

    // ── 压缩包名 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("压缩包名是「学号_姓名」")
    void zipName() {
        assertEquals("2024xxxx_廖展滨.zip", PackPlanner.zipName(owner()));
    }

    @Test
    @DisplayName("缺学号时不能拼出「null_姓名.zip」")
    void zipNameFallback() {
        assertEquals("廖展滨.zip", PackPlanner.zipName(new OwnerProfile("廖展滨", null, null, null, null)));
        assertEquals("综测材料.zip", PackPlanner.zipName(OwnerProfile.empty()));
    }

    @Test
    @DisplayName("有维度算不出分时给出提醒，而不是默默留空")
    void warnsWhenOverallMissing() {
        PackPlan plan = PackPlanner.plan(report(List.of(), List.of()), List.of(), owner());

        assertTrue(plan.warnings().stream().anyMatch(w -> w.contains("总分还没算出来")));
        assertFalse(plan.warnings().stream().anyMatch(w -> w.contains("待确认")));
    }

    // ── 构造工具 ───────────────────────────────────────────────────────

    private static PackPlan.Item itemOf(PackPlan plan, String folder, int materialIndex) {
        return plan.folders().stream()
                .filter(f -> f.name().equals(folder))
                .flatMap(f -> f.items().stream())
                .filter(i -> i.materialIndex() == materialIndex)
                .findFirst().orElseThrow();
    }

    private static OwnerProfile owner() {
        return new OwnerProfile("廖展滨", "2024xxxx", "计科2班", "计算机学院", "2024级");
    }

    private static ScoreInput.Material material(String fileName, String title, String achievement) {
        return new ScoreInput.Material(fileName, fact(title, achievement));
    }

    private static ExtractedFact fact(String title, String achievement) {
        return new ExtractedFact("证书", "廖展滨", false, "广东金融学院", "2025-09-01",
                null, null, title, achievement, "校级", "智育", "原文", 0.9);
    }

    private static ScoreReport.ScoreLine line(int materialIndex, String dimension, String groupId,
                                              double score, boolean included) {
        return new ScoreReport.ScoreLine(
                materialIndex + ":" + groupId, materialIndex, "材料" + materialIndex,
                dimension, groupId, groupId, "依据", score,
                included, included, false, true, false, "", "原文");
    }

    private static ScoreReport report(List<ScoreReport.ScoreLine> lines, List<ScoreReport.Unmatched> unmatched) {
        List<ScoreReport.DimensionScore> dims = new ArrayList<>();
        dims.add(dim("moral", "德育", 0.20));
        dims.add(dim("intellect", "智育", 0.60));
        dims.add(dim("aesthetic", "美育", 0.05));
        dims.add(dim("physique", "体育", 0.10));
        dims.add(dim("labour", "劳育", 0.05));
        return new ScoreReport("广东金融学院", "计算机学院", "2026-07-02", "2025-2026",
                "2025-09-01", "2026-08-31", dims, lines, unmatched, List.of(), null, "ok", List.of());
    }

    private static ScoreReport.DimensionScore dim(String id, String name, double weight) {
        return new ScoreReport.DimensionScore(id, name, weight, "base", Map.of(), 0, 0,
                null, "need_input", null, List.of());
    }
}
