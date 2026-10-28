package com.abing.assessment.domain.rules;

import java.util.Map;

/**
 * 计分公式的表达式求值器（内置，零第三方依赖）。
 *
 * <p>支持四则运算 {@code + - * /}、括号、小数、变量引用（含中文变量名）。
 * 变量值由调用方以 {@link Map} 传入。例：
 * <pre>
 *   "德育*0.20 + 智育*0.60 + 体育*0.10"
 *   "base*0.30 + peer*0.20 + bonus*0.50 - deduction"
 * </pre>
 *
 * <p><b>决策（2026-10-27）</b>：formula 用引擎内置小解析器，而非 exp4j / JEXL——
 * 计分公式复杂度低，引第三方库不划算。若将来需要函数（min/max/round）再扩。
 */
public final class FormulaEvaluator {

    private final String src;
    private final Map<String, Double> vars;
    private int pos;

    private FormulaEvaluator(String src, Map<String, Double> vars) {
        this.src = src;
        this.vars = vars;
        this.pos = 0;
    }

    /**
     * 求值入口。
     *
     * @param formula 计分公式，空白或 null 视为 0
     * @param vars    变量名 → 值（缺失变量会抛异常）
     * @return 计算结果
     * @throws IllegalArgumentException 语法错 / 未知变量 / 除零 / 末尾多余内容
     */
    public static double evaluate(String formula, Map<String, Double> vars) {
        if (formula == null || formula.isBlank()) {
            return 0d;
        }
        FormulaEvaluator e = new FormulaEvaluator(formula.trim(), vars);
        double v = e.parseExpr();
        e.skipSpaces();
        if (e.pos < e.src.length()) {
            throw new IllegalArgumentException("公式末尾有多余内容：" + e.src.substring(e.pos));
        }
        return v;
    }

    /** expr := term (('+' | '-') term)* */
    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            skipSpaces();
            char c = peek();
            if (c == '+') {
                pos++;
                v += parseTerm();
            } else if (c == '-') {
                pos++;
                v -= parseTerm();
            } else {
                return v;
            }
        }
    }

    /** term := factor (('*' | '/') factor)* */
    private double parseTerm() {
        double v = parseFactor();
        while (true) {
            skipSpaces();
            char c = peek();
            if (c == '*') {
                pos++;
                v *= parseFactor();
            } else if (c == '/') {
                pos++;
                double d = parseFactor();
                if (d == 0d) {
                    throw new IllegalArgumentException("公式出现除零：" + src);
                }
                v /= d;
            } else {
                return v;
            }
        }
    }

    /** factor := ('+' | '-') factor | NUMBER | IDENT | '(' expr ')' */
    private double parseFactor() {
        skipSpaces();
        char c = peek();
        if (c == '+') {
            pos++;
            return parseFactor();
        }
        if (c == '-') {
            pos++;
            return -parseFactor();
        }
        if (c == '(') {
            pos++;
            double v = parseExpr();
            skipSpaces();
            if (peek() != ')') {
                throw new IllegalArgumentException("括号不匹配：" + src);
            }
            pos++;
            return v;
        }
        if (Character.isDigit(c) || c == '.') {
            return parseNumber();
        }
        if (isIdentStart(c)) {
            return parseIdent();
        }
        throw new IllegalArgumentException("公式非法字符 '" + c + "' 位置 " + pos + "：" + src);
    }

    private double parseNumber() {
        int start = pos;
        while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
            pos++;
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("数字格式错：" + src.substring(start, pos), e);
        }
    }

    private double parseIdent() {
        int start = pos;
        while (pos < src.length() && isIdentPart(src.charAt(pos))) {
            pos++;
        }
        String name = src.substring(start, pos);
        Double v = vars.get(name);
        if (v == null) {
            throw new IllegalArgumentException("公式引用了未知变量「" + name + "」，可提供值：" + vars.keySet());
        }
        return v;
    }

    /** 标识符开头：字母或下划线（Character.isLetter 覆盖中文） */
    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void skipSpaces() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }
}
