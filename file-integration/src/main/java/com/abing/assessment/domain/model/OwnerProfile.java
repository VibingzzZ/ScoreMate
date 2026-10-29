package com.abing.assessment.domain.model;

/**
 * 本人信息（一次性填写，全流程复用）。
 *
 * <p>综测材料都是同一个人的，所以姓名 / 学号 / 班级这类身份字段<b>根本不需要每份材料都靠模型读</b>：
 * 用户填一次，之后所有材料统一采用这份值。这能省掉大量"为了读准两个字"的模型调用成本，
 * 也避免出现"一份读成束展涛、一份读成廖展滨"这种自相矛盾的结果。
 *
 * <p>模型仍然会照常识别姓名字段，但用途变了：
 * <ul>
 *   <li>识别值 == 本人姓名 → 正常</li>
 *   <li>识别值 != 本人姓名 → 视为<b>歧义项</b>抛给用户确认（可能是别人的材料混进来了）</li>
 * </ul>
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
