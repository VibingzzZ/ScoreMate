package com.abing.assessment.domain.model;

/**
 * 本人信息，填一次全流程复用。
 *
 * <p>材料都是同一个人的，姓名 / 学号 / 班级没必要每份都靠模型读——省成本，
 * 也避免"一份读成束展涛、一份读成廖展滨"这类自相矛盾。
 *
 * <p>模型照常识别姓名，但用途改为比对：一致则正常，不一致视为<b>歧义项</b>
 * 交用户确认（可能是别人的材料混进来）。
 */
public record OwnerProfile(
        String name,
        String studentId,
        String className,
        String college,
        String grade
) {

    public static OwnerProfile empty() {
        return new OwnerProfile(null, null, null, null, null);
    }

    /** 有没有填姓名——没填就没法做"材料是否属于本人"的校验 */
    public boolean hasName() {
        return name != null && !name.isBlank();
    }

    /** 归一化空白，避免用户多打空格导致比对失败 */
    public OwnerProfile normalized() {
        return new OwnerProfile(trim(name), trim(studentId), trim(className), trim(college), trim(grade));
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
