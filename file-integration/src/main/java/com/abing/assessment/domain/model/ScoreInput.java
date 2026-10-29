package com.abing.assessment.domain.model;

import java.util.List;
import java.util.Map;

/**
 * 算分入参。
 *
 * @param materials    参与算分的材料（文件名 + 已确认的事实）
 * @param academicYear 评测学年，如 {@code 2025-2026}
 * @param inputs       用户手填的输入，键形如 {@code moral.base} / {@code moral.peer}
 * @param choices      候选选择：键 {@code 材料序号:规则组id}，值 {@code ScoreLine.id}
 *                     （一份材料在一组里可能有多个等级的候选，用户挑一个）
 * @param excluded     明确不计入的条目 id（用户在页面上勾掉的行）
 * @param forced       强制计入的条目 id（超出学年但学院认可的奖项，解释权在班委）
 * @param penalties    手填的扣分事由（违纪/宿舍不合格/体测未达标这类没有证书可拍的）
 */
public record ScoreInput(
        List<Material> materials,
        String academicYear,
        Map<String, Double> inputs,
        Map<String, String> choices,
        List<String> excluded,
        List<String> forced,
        List<PenaltyEntry> penalties
) {

    /** 一份材料：文件名 + 抽取出来的事实 */
    public record Material(String fileName, ExtractedFact fact) {
    }

    public static ScoreInput empty() {
        return new ScoreInput(List.of(), null, Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    public Map<String, Double> inputsOrEmpty() {
        return inputs == null ? Map.of() : inputs;
    }

    public Map<String, String> choicesOrEmpty() {
        return choices == null ? Map.of() : choices;
    }

    public List<String> excludedOrEmpty() {
        return excluded == null ? List.of() : excluded;
    }

    public List<String> forcedOrEmpty() {
        return forced == null ? List.of() : forced;
    }

    public List<PenaltyEntry> penaltiesOrEmpty() {
        return penalties == null ? List.of() : penalties;
    }
}
