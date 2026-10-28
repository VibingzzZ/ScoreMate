package com.abing.assessment.domain.rules;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormulaEvaluatorTest {

    private static final double EPS = 1e-9;

    @Test
    void 先乘除后加减() {
        assertEquals(7.0, FormulaEvaluator.evaluate("1 + 2 * 3", Map.of()), EPS);
        assertEquals(4.0, FormulaEvaluator.evaluate("10 - 2 * 3", Map.of()), EPS);
        assertEquals(15.0, FormulaEvaluator.evaluate("10 / 2 * 3", Map.of()), EPS);
    }

    @Test
    void 括号改变优先级() {
        assertEquals(9.0, FormulaEvaluator.evaluate("(1 + 2) * 3", Map.of()), EPS);
        assertEquals(20.0, FormulaEvaluator.evaluate("2 * (3 + 7)", Map.of()), EPS);
    }

    @Test
    void 中文变量与权重() {
        Map<String, Double> vars = Map.of("德育", 80.0, "智育", 90.0);
        assertEquals(88.0, FormulaEvaluator.evaluate("德育*0.2 + 智育*0.8", vars), EPS);
    }

    @Test
    void 多变量计分公式() {
        Map<String, Double> vars = Map.of(
                "base", 70.0, "peer", 60.0, "bonus", 20.0, "deduction", 5.0);
        // 70*0.30 + 60*0.20 + 20*0.50 - 5 = 21 + 12 + 10 - 5 = 38
        assertEquals(38.0,
                FormulaEvaluator.evaluate("base*0.30 + peer*0.20 + bonus*0.50 - deduction", vars), EPS);
    }

    @Test
    void 一元负号() {
        assertEquals(5.0, FormulaEvaluator.evaluate("-5 + 10", Map.of()), EPS);
        assertEquals(-5.0, FormulaEvaluator.evaluate("-(2 + 3)", Map.of()), EPS);
        assertEquals(-1.5, FormulaEvaluator.evaluate("-1.5", Map.of()), EPS);
    }

    @Test
    void 小数与空白() {
        assertEquals(3.0, FormulaEvaluator.evaluate("1.5 * 2", Map.of()), EPS);
        assertEquals(3.0, FormulaEvaluator.evaluate("  1  +  2  ", Map.of()), EPS);
    }

    @Test
    void 空公式视为零() {
        assertEquals(0.0, FormulaEvaluator.evaluate("", Map.of()), EPS);
        assertEquals(0.0, FormulaEvaluator.evaluate(null, Map.of()), EPS);
        assertEquals(0.0, FormulaEvaluator.evaluate("   ", Map.of()), EPS);
    }

    @Test
    void 未知变量抛异常() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FormulaEvaluator.evaluate("德育*0.2 + 未知*0.8", Map.of("德育", 80.0)));
        assertEquals(true, e.getMessage().contains("未知变量"));
    }

    @Test
    void 除零抛异常() {
        assertThrows(IllegalArgumentException.class,
                () -> FormulaEvaluator.evaluate("10 / 0", Map.of()));
    }

    @Test
    void 语法错抛异常() {
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.evaluate("1 +", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.evaluate("(1 + 2", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> FormulaEvaluator.evaluate("1 2", Map.of()));
    }
}
