package com.abing.assessment.domain.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcademicYearTest {

    @Test
    @DisplayName("2025-2026 → 2025-09-01 ~ 2026-08-31")
    void parseLabel() {
        AcademicYear y = AcademicYear.parse("2025-2026");
        assertEquals(LocalDate.of(2025, 9, 1), y.start());
        assertEquals(LocalDate.of(2026, 8, 31), y.end());
        assertEquals("2025-2026", y.label());
    }

    @Test
    @DisplayName("只写年份、带「学年」后缀都能认")
    void tolerantLabel() {
        assertEquals("2025-2026", AcademicYear.parse("2025").label());
        assertEquals("2025-2026", AcademicYear.parse("2025-2026学年").label());
        assertEquals("2025-2026", AcademicYear.parse(" 2025 — 2026 ").label());
    }

    @Test
    @DisplayName("各种日期写法都要能解析")
    void parseDateVariants() {
        LocalDate expect = LocalDate.of(2025, 6, 28);
        assertEquals(expect, AcademicYear.parseDate("2025-06-28"));
        assertEquals(expect, AcademicYear.parseDate("2025/6/28"));
        assertEquals(expect, AcademicYear.parseDate("2025年6月28日"));
        assertEquals(expect, AcademicYear.parseDate("2025.06.28"));
        assertEquals(expect, AcademicYear.parseDate("  2025-06-28  "));
    }

    @Test
    @DisplayName("只有年月 / 只有年份也要给个保守值，不能直接判失败")
    void partialDates() {
        assertEquals(LocalDate.of(2025, 6, 1), AcademicYear.parseDate("2025年6月"));
        assertEquals(LocalDate.of(2025, 12, 31), AcademicYear.parseDate("2025"));
    }

    @Test
    @DisplayName("空值与「至今」返回 null（不确定，不是 false）")
    void unreadable() {
        assertNull(AcademicYear.parseDate(null));
        assertNull(AcademicYear.parseDate("  "));
        assertNull(AcademicYear.parseDate("至今"));
        assertNull(AcademicYear.parseDate("无"));
    }

    @Test
    @DisplayName("contains 三态：true / false / null")
    void contains() {
        AcademicYear y = AcademicYear.of(2025);
        assertEquals(Boolean.TRUE, y.contains("2025-09-01"));
        assertEquals(Boolean.TRUE, y.contains("2026-08-31"));
        assertEquals(Boolean.FALSE, y.contains("2025-08-31"));
        assertEquals(Boolean.FALSE, y.contains("2024-09-24"));
        assertNull(y.contains(null));
    }

    @Test
    @DisplayName("学年边界是闭区间")
    void boundaries() {
        AcademicYear y = AcademicYear.of(2025);
        assertTrue(y.contains(y.start().toString()));
        assertTrue(y.contains(y.end().toString()));
        assertFalse(y.contains(y.start().minusDays(1).toString()));
        assertFalse(y.contains(y.end().plusDays(1).toString()));
    }
}
