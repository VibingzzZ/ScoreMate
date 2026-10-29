package com.abing.assessment.domain.export;

import java.util.List;

/**
 * 打包计划：算分结果 → 交付结构。只做归类 / 命名 / 排序，不碰字节，
 * 落盘在 infra 层，因此包结构可用纯单测断言。
 *
 * @param zipName  压缩包名「学号＋姓名」
 * @param folders  五个育的文件夹，顺序即官方顺序（见 {@link PackPlanner#FOLDER_ORDER}）
 * @param skipped  未进包的材料 + 原因，绝不静默丢弃
 * @param warnings 需用户过目的提醒
 */
public record PackPlan(
        String zipName,
        String academicYear,
        List<Folder> folders,
        List<Skipped> skipped,
        List<String> warnings
) {

    /**
     * 一个育的文件夹。
     *
     * @param name        文件夹名（德育 / 智育 / 美育 / 体育 / 劳育）
     * @param dimensionId 对应规则里的维度 id，方便前端反查算分明细
     */
    public record Folder(String name, String dimensionId, List<Item> items) {
    }

    /**
     * 文件夹里的一份材料。
     *
     * @param number    材料序号，<b>本文件夹内</b>从 1 开始（每个文件夹独立编号）
     * @param newName   重命名后的文件名，形如 {@code 材料1：蓝桥杯+国赛一等奖.jpg}
     * @param groupName 命中的加分项名称，用来让用户判断这份材料用得对不对
     */
    public record Item(
            int number,
            int materialIndex,
            String originalName,
            String newName,
            String groupName,
            Double score,
            String basis
    ) {
    }

    /** 没进包的材料 */
    public record Skipped(int materialIndex, String fileName, String reason) {
    }

    /** 进包的材料总数（同一份材料落在两个育里只算一次） */
    public int packedCount() {
        return (int) folders.stream()
                .flatMap(f -> f.items().stream())
                .mapToInt(Item::materialIndex)
                .distinct()
                .count();
    }
}
