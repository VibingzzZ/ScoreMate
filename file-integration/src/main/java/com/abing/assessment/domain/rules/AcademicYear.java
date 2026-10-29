package com.abing.assessment.domain.rules;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评测学年窗口（2025-2026 → 2025-09-01 ~ 2026-08-31）。
 *
 * <p>作为入参而非取 {@code now()}：引擎必须纯函数，否则今天跑和明天跑结果不同，
 * 既没法申诉也没法写稳定单测。
 *
 * <p>日期解析放宽：{@code 2025-06-28}、{@code 2025/6/28}、{@code 2025年6月28日} 都收；
 * 只有 {@code 2025-06} 时按学年中点算，<b>不判失败</b>——判失败等于丢材料。
 */
public record AcademicYear(String label, LocalDate start, LocalDate end) {

    private static final Pattern DATE_PARTS =
            Pattern.compile("(\\d{4})\\s*[-/.年]?\\s*(\\d{1,2})?\\s*[-/.月]?\\s*(\\d{1,2})?");

    /**
     * 解析学年标签。
     *
     * @param label {@code "2025-2026"} / {@code "2025-2026学年"} / {@code "2025"} 都行
     */
    public static AcademicYear parse(String label) {
        String s = label == null ? "" : label.trim();
        Matcher m = Pattern.compile("(\\d{4})\\s*[-—~至到]?\\s*(\\d{4})?").matcher(s);
        int startYear;
        if (m.find()) {
            startYear = Integer.parseInt(m.group(1));
        } else {
            // 没写学年就按"当前所在学年"兜底（9 月之前属于上一学年）
            LocalDate now = LocalDate.now();
            startYear = now.getMonthValue() >= 9 ? now.getYear() : now.getYear() - 1;
        }
        return of(startYear);
    }

    public static AcademicYear of(int startYear) {
        return new AcademicYear(
                startYear + "-" + (startYear + 1),
                LocalDate.of(startYear, 9, 1),
                LocalDate.of(startYear + 1, 8, 31));
    }

    /**
     * 判断材料日期是否落在本学年内。
     *
     * @return {@code null} 表示日期读不出来（不是"不在窗口内"，是"不知道"——这两种必须区分开）
     */
    public Boolean contains(String rawDate) {
        LocalDate d = parseDate(rawDate);
        if (d == null) {
            return null;
        }
        return !d.isBefore(start) && !d.isAfter(end);
    }

    /**
     * 宽松日期解析，读不出来返回 null。
     *
     * <p>只有年份时取该学年中点（当年 12 月 31 日）保守估计；
     * 只有年月时取该月 1 日——只要月份落在窗口内，结果就基本没错。
     */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.contains("至今") || s.contains("至今") || "今".equals(s)) {
            return null;      // 聘期"至今"不是签发日期，交给窗口判"不确定"
        }
        Matcher m = DATE_PARTS.matcher(s);
        if (!m.find()) {
            return null;
        }
        int year = Integer.parseInt(m.group(1));
        if (year < 1900 || year > 2200) {
            return null;
        }
        Integer month = intOrNull(m.group(2));
        Integer day = intOrNull(m.group(3));

        if (month == null || month < 1 || month > 12) {
            return LocalDate.of(year, 12, 31);      // 只有年份：取学年内的保守点
        }
        if (day == null || day < 1 || day > 31) {
            return LocalDate.of(year, month, 1);
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            return LocalDate.of(year, month, 1);
        }
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return label + "（" + start + " ~ " + end + "）";
    }
}
