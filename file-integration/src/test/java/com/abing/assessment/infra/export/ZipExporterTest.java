package com.abing.assessment.infra.export;

import com.abing.assessment.domain.export.PackPlan;
import com.abing.assessment.domain.export.PackPlanner;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.PenaltyEntry;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端级别的单测：真的打一个 zip，再把包里的 xlsx 读回来断言单元格。
 *
 * <p>这一层专门抓"单测都说对、实际包是坏的"那类问题——比如 xlsx 写坏了、
 * zip 条目名中文乱码、文件夹层级少一层。人工验一次要开压缩软件点半天，这里 1 秒。
 */
class ZipExporterTest {

    @Test
    @DisplayName("zip 顶层是五个文件夹 + 综测评定表")
    void zipTree() throws Exception {
        byte[] zip = build();
        List<String> entries = entries(zip);

        assertTrue(entries.contains("德育/材料1：融媒体中心干事.jpg"), "德育里应有聘书：" + entries);
        assertTrue(entries.contains("智育/材料1：蓝桥杯+国赛一等奖.png"), "智育里应有获奖证书（各文件夹独立编号）：" + entries);
        assertTrue(entries.contains("美育/"), "空文件夹要留条目");
        assertTrue(entries.contains("体育/"));
        assertTrue(entries.contains("劳育/"));
        assertTrue(entries.contains("综测评定表.xlsx"));
    }

    @Test
    @DisplayName("判定评定表：结构与附件3模板一致，加分不填总分表、扣分必须写事由")
    void formContent() throws Exception {
        byte[] zip = build();
        byte[] xlsx = formBytes(zip);
        java.nio.file.Files.write(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "form-debug.xlsx"), xlsx);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet main = wb.getSheet("综合测评表");
            assertNotNull(main, "总表 sheet 名要与附件3模板一致");
            Sheet detail = wb.getSheet("加分明细");
            assertNotNull(detail, "必须有加分明细 sheet");

            // 标题 + 信息行（姓名/班级/学号）
            assertEquals("计算机学院综合测评表", main.getRow(0).getCell(0).getStringCellValue());
            String info = main.getRow(1).getCell(0).getStringCellValue();
            assertTrue(info.contains("廖展滨") && info.contains("2024xxxx"), "信息行要有姓名学号：" + info);

            // 五段都要在，且行名用细则原文
            List<String> colB = new ArrayList<>();
            for (int r = 2; r <= main.getLastRowNum(); r++) {
                Row row = main.getRow(r);
                if (row != null && row.getCell(1) != null) {
                    colB.add(row.getCell(1).toString());
                }
            }
            for (String must : List.of("德育基础分", "德育互评分", "德育素质加分", "德育扣分", "德育总分",
                    "智育创新能力加分", "体育竞赛加分", "美育活动加分", "劳育实践加分")) {                assertTrue(colB.contains(must), "总表缺少「" + must + "」行，实际有：" + colB);
            }

            // 加分行的明细列必须写「详见加分明细」，不能罗列项目
            int moralBonusRow = rowOf(main, "德育素质加分");
            assertEquals("（不填，详见加分明细）", main.getRow(moralBonusRow).getCell(2).toString());

            // 扣分行的明细列必须有事由，得分列是负数
            int moralDeductRow = rowOf(main, "德育扣分");
            String reason = main.getRow(moralDeductRow).getCell(2).toString();
            String scoreTxt = main.getRow(moralDeductRow).getCell(3).toString();
            assertTrue(reason.startsWith("1、") && reason.contains("宿舍检查不合格"),
                    "扣分要写事由：" + reason);
            assertTrue(scoreTxt.contains("-20"), "扣分得分列应为负数：" + scoreTxt);

            // 加分明细：第 3 行起是材料行，B 列写「材料X」
            assertEquals("材料1", detail.getRow(2).getCell(1).getStringCellValue());
            assertTrue(detail.getRow(2).getCell(3).getNumericCellValue() > 0);
            assertEquals("德育素质加分项目", detail.getRow(2).getCell(0).getStringCellValue());
        }
    }

    /** 在总表里按 B 列文字找行号 */
    private static int rowOf(Sheet sheet, String label) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getCell(1) != null && label.equals(row.getCell(1).toString())) {
                return r;
            }
        }
        throw new AssertionError("总表里找不到「" + label + "」行");
    }

    @Test
    @DisplayName("没进包的材料不产生文件条目")
    void skippedNotPacked() throws Exception {
        List<String> entries = entries(build());
        assertTrue(entries.stream().noneMatch(e -> e.contains("随便拍的")));
    }

    // ── 构造 ───────────────────────────────────────────────────────────

    private static byte[] build() throws Exception {
        List<ScoreInput.Material> materials = List.of(
                new ScoreInput.Material("聘书-融媒体干事.jpg", fact("融媒体中心干事", null)),
                new ScoreInput.Material("获奖证书-摄影三等奖.png", fact("蓝桥杯", "国赛一等奖")));

        List<ScoreReport.ScoreLine> lines = List.of(
                new ScoreReport.ScoreLine("1:innovation", 1, "获奖证书-摄影三等奖.png", "intellect",
                        "innovation", "创新能力加分", "细则 §2.3", 20d, true, true, false, true, false, "", "国赛一等奖"),
                new ScoreReport.ScoreLine("0:student_officer", 0, "聘书-融媒体干事.jpg", "moral",
                        "student_officer", "学生干部", "细则 §1.2", 10d, true, true, false, true, false, "", "融媒体中心干事"));

        OwnerProfile owner = new OwnerProfile("廖展滨", "2024xxxx", "计科2班", "计算机学院", "2024级");
        ScoreReport report = report(lines);
        PackPlan plan = PackPlanner.plan(report, materials, owner);

        Map<Integer, byte[]> originals = new LinkedHashMap<>();
        originals.put(0, "fake-jpg".getBytes(StandardCharsets.UTF_8));
        originals.put(1, "fake-png".getBytes(StandardCharsets.UTF_8));
        return ZipExporter.build(plan, report, owner, originals);
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                names.add(e.getName());
            }
        }
        return names;
    }

    private static byte[] formBytes(byte[] zip) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().endsWith(".xlsx")) {
                    return in.readAllBytes();
                }
            }
        }
        throw new AssertionError("包里没有 xlsx");
    }

    private static ExtractedFact fact(String title, String achievement) {
        return new ExtractedFact("证书", "廖展滨", false, "广东金融学院", "2025-09-01",
                null, null, title, achievement, "国家级", "智育", "原文", 0.92);
    }

    private static ScoreReport report(List<ScoreReport.ScoreLine> lines) {
        List<ScoreReport.DimensionScore> dims = new ArrayList<>();
        dims.add(dim("moral", "德育", 0.20, 80d, 10d, 20d));
        dims.add(dim("intellect", "智育", 0.60, 85d, 20d, 0d));
        dims.add(dim("aesthetic", "美育", 0.05, 80d, 0d, 0d));
        dims.add(dim("physique", "体育", 0.10, 75d, 0d, 0d));
        dims.add(dim("labour", "劳育", 0.05, 80d, 0d, 0d));
        // 一条手填扣分：官方模板要求扣分列写事由，只填数字会被退回
        List<PenaltyEntry> penalties = List.of(
                new PenaltyEntry("moral", "2025.10.12，因宿舍检查不合格被通报批评", 20d));
        return new ScoreReport("广东金融学院", "计算机学院", "2026-07-02", "2025-2026",
                "2025-09-01", "2026-08-31", dims, lines, List.of(), List.of(), 86.4, "ok", penalties);
    }

    private static ScoreReport.DimensionScore dim(String id, String name, double weight, double base,
                                                 double bonus, double deduction) {
        Map<String, Double> vars = new LinkedHashMap<>();
        vars.put("base", base);
        if ("moral".equals(id)) {
            vars.put("peer", 90d);   // 德育是唯一有互评分的维度，公式 base*0.30+peer*0.20+...
        }
        vars.put("bonus", bonus);
        vars.put("deduction", deduction);
        return new ScoreReport.DimensionScore(id, name, weight, "base*0.8", vars,
                bonus, deduction, base, "ok", null, List.of());
    }
}
