package com.abing.assessment.infra.export;

import com.abing.assessment.domain.export.PackPlan;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.PenaltyEntry;
import com.abing.assessment.domain.model.ScoreReport;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成「综测评定表.xlsx」，含两张 sheet。<b>表结构照抄官方模板</b>
 * （{@code 附件3}），不自拟表头——班委按模板看表，格式不对整包会被退回。
 *
 * <pre>
 * 综合测评表（四列）            加分明细（五列）
 * X育测评 | X育项目 |         加分项目 | 材料X | 项目明细 | 项目得分 | 备注
 *          加、扣分事项明细 | 得分
 * </pre>
 *
 * <p>两个坑：总表的加分列<b>必须留空</b>（细则原文要求），但扣分必须写事由，只填数字会被退回；
 * 加分明细的「材料X」用该维度文件夹内的编号（每个文件夹各自从材料1开始）。
 */
public final class FormWriter {

    /** sheet 名与模板一致 */
    private static final String SHEET_MAIN = "综合测评表";
    private static final String SHEET_DETAIL = "加分明细";

    /** 压缩包里表格的文件名（规格要求顶层只有「五个文件夹 + 综测评定表」） */
    public static final String FILE_NAME = "综测评定表.xlsx";

    /**
     * 总表里五育的段落顺序与行名，来自细则原文表格。
     *
     * <p><b>这里是「德智体美劳」</b>——和打包文件夹的「德智美体劳」不一样，
     * 两个顺序各有出处：表按 {@code 附件3} 模板，文件夹按 {@code 材料2} 第 3 页原文。
     * 不要"顺手统一"，两边都对。
     */
    private static final List<DimForm> FORMS = List.of(
            new DimForm("moral", "德育", "德育素质加分", "德育扣分"),
            new DimForm("intellect", "智育", "智育创新能力加分", "智育扣分"),
            new DimForm("physique", "体育", "体育竞赛加分", "体育扣分"),
            new DimForm("aesthetic", "美育", "美育活动加分", "美育扣分"),
            new DimForm("labour", "劳育", "劳育实践加分", "劳育扣分"));

    /** 公式里的合成变量，不属于用户手填项，不能渲染成输入行 */
    private static final List<String> SYNTHETIC = List.of("bonus", "deduction");

    private FormWriter() {
    }

    private record DimForm(String id, String name, String bonusLabel, String deductLabel) {
    }

    /**
     * @param plan   打包计划（用来取每个文件夹内的材料编号，保证明细里的「材料X」与文件名一致）
     * @param report 算分结果
     * @param owner  本人信息
     */
    public static void write(OutputStream out, PackPlan plan, ScoreReport report, OwnerProfile owner)
            throws java.io.IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            mainSheet(wb, report, owner);
            detailSheet(wb, plan, report);
            wb.write(out);
        }
    }

    // ── 总表 ───────────────────────────────────────────────────────────

    private static void mainSheet(XSSFWorkbook wb, ScoreReport report, OwnerProfile owner) {
        Sheet sheet = wb.createSheet(SHEET_MAIN);
        Style style = new Style(wb);
        Map<String, ScoreReport.DimensionScore> dimById = new LinkedHashMap<>();
        if (report.dimensions() != null) {
            report.dimensions().forEach(d -> dimById.put(d.id(), d));
        }

        int rowNo = 0;

        // 标题
        Row title = sheet.createRow(rowNo++);
        title.setHeightInPoints(28);
        Cell tc = title.createCell(0);
        tc.setCellValue("计算机学院综合测评表");
        tc.setCellStyle(style.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 姓名 / 班级 / 学号（模板第 2 行）
        Row info = sheet.createRow(rowNo++);
        info.setHeightInPoints(22);
        Cell ic = info.createCell(0);
        ic.setCellValue("姓名：" + safe(owner == null ? null : owner.name())
                + "        班级：" + safe(owner == null ? null : owner.className())
                + "        学号：" + safe(owner == null ? null : owner.studentId()));
        ic.setCellStyle(style.info);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 3));

        for (DimForm form : FORMS) {
            rowNo = dimensionBlock(sheet, rowNo, form, dimById.get(form.id()), report, style);
            sheet.createRow(rowNo++);   // 段落间空行（模板也有）
        }

        // 综合测评总分
        Row total = sheet.createRow(rowNo);
        total.setHeightInPoints(22);
        text(total, 0, "综合测评总分", style.totalLabel);
        total.createCell(1).setCellStyle(style.totalLabel);
        total.createCell(2).setCellStyle(style.totalLabel);
        sheet.addMergedRegion(new CellRangeAddress(rowNo, rowNo, 0, 2));
        num(total, 3, report.overall(), style.total);

        // 说明（学年、规则版本、免责）
        Row note = sheet.createRow(rowNo + 2);
        Cell nc = note.createCell(0);
        nc.setCellValue("评测学年 " + safe(report.academicYear())
                + "（" + safe(report.windowStart()) + " ~ " + safe(report.windowEnd()) + "）"
                + "；规则依据 " + safe(report.school()) + " " + safe(report.college())
                + "（" + safe(report.ruleVersion()) + "）。本表由综测整理工具生成，交前请自行核对。");
        nc.setCellStyle(style.note);
        sheet.addMergedRegion(new CellRangeAddress(rowNo + 2, rowNo + 2, 0, 3));

        sheet.setColumnWidth(0, 11 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        sheet.setColumnWidth(2, 52 * 256);
        sheet.setColumnWidth(3, 13 * 256);
    }

    /** 一个育的整段：表头 + 各填分行 + 加分 + 扣分 + 总分，返回下一个可用行号 */
    private static int dimensionBlock(Sheet sheet, int startRow, DimForm form,
                                      ScoreReport.DimensionScore dim, ScoreReport report, Style style) {
        int rowNo = startRow;

        // 段头
        Row head = sheet.createRow(rowNo++);
        text(head, 0, form.name() + "测评", style.head);
        text(head, 1, form.name() + "项目", style.head);
        text(head, 2, "加、扣分事项明细", style.head);
        text(head, 3, "得分", style.head);

        // 用户手填项（基础分 / 互评分…），名称按细则原文
        List<String> filledKeys = new ArrayList<>();
        Map<String, Double> inputs = dim == null || dim.inputs() == null ? Map.of() : dim.inputs();
        for (String key : inputs.keySet()) {
            if (!SYNTHETIC.contains(key)) {
                filledKeys.add(key);
            }
        }
        for (String key : filledKeys) {
            Row r = sheet.createRow(rowNo++);
            text(r, 1, inputLabel(form, key), style.labelLeft);
            text(r, 2, "", style.cellLeft);
            num(r, 3, inputs.get(key), style.cell);
        }
        if (filledKeys.isEmpty()) {
            // 细则里这个育没有可手填的基础分（极端兜底，不静默少一行）
            Row r = sheet.createRow(rowNo++);
            text(r, 1, form.name() + "基础分", style.labelLeft);
            text(r, 2, "", style.cellLeft);
            num(r, 3, null, style.cell);
        }

        // 加分（官方要求：明细不填，只填累计总分）
        Row bonus = sheet.createRow(rowNo++);
        text(bonus, 1, form.bonusLabel(), style.labelLeft);
        text(bonus, 2, dim == null ? "" : "（不填，详见加分明细）", style.cellLeftMuted);
        num(bonus, 3, dim == null ? null : dim.bonus(), style.cell);

        // 扣分（这里必须写事由，且与得分列一一对应）
        List<PenaltyEntry> mine = new ArrayList<>();
        if (report.penalties() != null) {
            for (PenaltyEntry p : report.penalties()) {
                if (p != null && form.id().equals(p.dimension())) {
                    mine.add(p);
                }
            }
        }
        Row deduct = sheet.createRow(rowNo++);
        deduct.setHeightInPoints(Math.max(20, 16f * Math.max(mine.size(), 1) + 4));
        text(deduct, 1, form.deductLabel(), style.labelLeft);
        text(deduct, 2, mine.isEmpty() ? "" : numbered(mine, true), style.cellLeftWrap);
        text(deduct, 3, mine.isEmpty() ? "" : numbered(mine, false), style.cellWrap);

        // 维度总分
        Row total = sheet.createRow(rowNo++);
        text(total, 1, form.name() + "总分", style.labelLeft);
        text(total, 2, "（不填）", style.cellLeftMuted);
        num(total, 3, dim == null ? null : dim.score(), style.cell);

        // A 列「X育测评」纵向合并整段
        sheet.addMergedRegion(new CellRangeAddress(startRow, rowNo - 1, 0, 0));
        return rowNo;
    }

    /** {@code 1、事由   2、事由} / {@code 1、-20   2、-5} */
    private static String numbered(List<PenaltyEntry> list, boolean reason) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(i + 1).append('、').append(reason ? list.get(i).reasonText() : list.get(i).scoreText());
        }
        return sb.toString();
    }

    /** 输入项的中文名：细则固定术语，base=基础分、peer=互评分 */
    private static String inputLabel(DimForm form, String key) {
        return switch (key) {
            case "base" -> form.name() + "基础分";
            case "peer" -> form.name() + "互评分";
            default -> form.name() + key;
        };
    }

    // ── 加分明细 ───────────────────────────────────────────────────────

    private static void detailSheet(XSSFWorkbook wb, PackPlan plan, ScoreReport report) {
        Sheet sheet = wb.createSheet(SHEET_DETAIL);
        Style style = new Style(wb);

        Row title = sheet.createRow(0);
        title.setHeightInPoints(26);
        Cell tc = title.createCell(0);
        tc.setCellValue("加分明细表");
        tc.setCellStyle(style.title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        String[] head = {"加分项目", "", "项目明细", "项目得分", "备注"};
        Row hr = sheet.createRow(1);
        for (int i = 0; i < head.length; i++) {
            text(hr, i, head[i], style.head);
        }
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));

        // 「材料X」用该维度文件夹内的编号，不是全包连续号
        Map<String, Integer> numberAt = new LinkedHashMap<>();
        for (PackPlan.Folder f : plan.folders()) {
            for (PackPlan.Item it : f.items()) {
                numberAt.putIfAbsent(f.dimensionId() + ":" + it.materialIndex(), it.number());
            }
        }

        List<ScoreReport.ScoreLine> lines = report.lines() == null ? List.of() : report.lines();
        int rowNo = 2;
        boolean any = false;
        for (DimForm form : FORMS) {
            List<ScoreReport.ScoreLine> mine = lines.stream()
                    .filter(l -> l.included() && form.id().equals(l.dimension()))
                    .toList();
            int blockStart = rowNo;
            for (ScoreReport.ScoreLine line : mine) {
                Row r = sheet.createRow(rowNo++);
                Integer no = numberAt.get(form.id() + ":" + line.materialIndex());
                text(r, 1, no == null ? "（未进包）" : ("材料" + no), style.cellLeft);
                text(r, 2, safe(line.groupName()), style.cellLeft);
                num(r, 3, line.score(), style.cell);
                text(r, 4, basisOf(line), style.cellLeftWrap);
                any = true;
            }
            // A 列：这个育的加分项目名，纵向合并整组。
            // 注意只动 A 列——B 列是「材料X」，不能重建单元格，否则会把值抹掉
            Row first = sheet.getRow(blockStart);
            if (first == null) {
                first = sheet.createRow(blockStart);
                rowNo = blockStart + 1;
            }
            text(first, 0, form.name() + "素质加分项目", style.groupHead);
            if (rowNo - 1 > blockStart) {
                sheet.addMergedRegion(new CellRangeAddress(blockStart, rowNo - 1, 0, 0));
            }
        }
        if (!any) {
            Row r = sheet.createRow(rowNo);
            text(r, 0, "没有任何计入的加分项", style.note);
            sheet.addMergedRegion(new CellRangeAddress(rowNo, rowNo, 0, 4));
        }

        int[] widths = {20, 10, 24, 10, 56};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
    }

    /** 依据 = 细则条款 + 材料原文摘录，两样都留，班委才认 */
    private static String basisOf(ScoreReport.ScoreLine line) {
        StringBuilder sb = new StringBuilder(safe(line.basis()));
        if (line.evidence() != null && !line.evidence().isBlank()) {
            sb.append("｜原文：").append(line.evidence());
        }
        return sb.toString();
    }

    // ── 小工具 ─────────────────────────────────────────────────────────

    private static void text(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) {
            c.setCellValue(value);
        }
        c.setCellStyle(style);
    }

    private static void num(Row row, int col, Double value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value != null) {
            c.setCellValue(value);
        }
        c.setCellStyle(style);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 一次性把样式集中建好，避免每格 new 一个（POI 有样式数量上限） */
    private static final class Style {
        final CellStyle title;
        final CellStyle info;
        final CellStyle head;
        final CellStyle groupHead;
        final CellStyle labelLeft;
        final CellStyle cell;
        final CellStyle cellLeft;
        final CellStyle cellWrap;
        final CellStyle cellLeftWrap;
        final CellStyle cellLeftMuted;
        final CellStyle totalLabel;
        final CellStyle total;
        final CellStyle note;

        Style(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);

            title = boxed(wb);
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            info = boxed(wb);
            info.setAlignment(HorizontalAlignment.LEFT);
            info.setVerticalAlignment(VerticalAlignment.CENTER);
            info.setIndention((short) 1);

            head = boxed(wb);
            head.setFont(bold);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            groupHead = boxed(wb);
            groupHead.setFont(bold);
            groupHead.setAlignment(HorizontalAlignment.CENTER);
            groupHead.setVerticalAlignment(VerticalAlignment.CENTER);
            groupHead.setWrapText(true);

            labelLeft = boxed(wb);
            labelLeft.setAlignment(HorizontalAlignment.LEFT);
            labelLeft.setVerticalAlignment(VerticalAlignment.CENTER);
            labelLeft.setIndention((short) 1);

            cell = boxed(wb);
            cell.setAlignment(HorizontalAlignment.CENTER);
            cell.setVerticalAlignment(VerticalAlignment.CENTER);
            cell.setDataFormat(wb.createDataFormat().getFormat("0.##"));

            cellLeft = boxed(wb);
            cellLeft.setAlignment(HorizontalAlignment.LEFT);
            cellLeft.setVerticalAlignment(VerticalAlignment.CENTER);

            cellWrap = boxed(wb);
            cellWrap.setAlignment(HorizontalAlignment.CENTER);
            cellWrap.setVerticalAlignment(VerticalAlignment.CENTER);
            cellWrap.setWrapText(true);

            cellLeftWrap = boxed(wb);
            cellLeftWrap.setAlignment(HorizontalAlignment.LEFT);
            cellLeftWrap.setVerticalAlignment(VerticalAlignment.CENTER);
            cellLeftWrap.setWrapText(true);

            cellLeftMuted = boxed(wb);
            cellLeftMuted.setAlignment(HorizontalAlignment.LEFT);
            cellLeftMuted.setVerticalAlignment(VerticalAlignment.CENTER);
            Font grey = wb.createFont();
            grey.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            cellLeftMuted.setFont(grey);
            cellLeftMuted.setIndention((short) 1);

            totalLabel = boxed(wb);
            totalLabel.setFont(bold);
            totalLabel.setAlignment(HorizontalAlignment.CENTER);
            totalLabel.setVerticalAlignment(VerticalAlignment.CENTER);

            total = boxed(wb);
            total.setFont(bold);
            total.setAlignment(HorizontalAlignment.CENTER);
            total.setVerticalAlignment(VerticalAlignment.CENTER);
            total.setDataFormat(wb.createDataFormat().getFormat("0.##"));

            note = wb.createCellStyle();
            note.setAlignment(HorizontalAlignment.LEFT);
            note.setVerticalAlignment(VerticalAlignment.TOP);
            note.setWrapText(true);
        }

        private static CellStyle boxed(XSSFWorkbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            return s;
        }
    }
}
