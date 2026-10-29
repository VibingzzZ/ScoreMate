package com.abing.assessment.domain.export;

import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把算分结果翻译成学院要求的交付结构。
 *
 * <p>规格来源：{@code 材料2：综测评定注意事项.pdf} 第 3 页 + {@code 附件3} 模板。
 * 三条最容易写错：
 * <ol>
 *   <li>文件夹顺序是<b>德智美体劳</b>，美育在体育前</li>
 *   <li>文件夹内排序 = 加分明细的行序，不是文件名也不是日期</li>
 *   <li>命名「材料X：活动＋奖项/身份」，<b>每个文件夹各自从材料1开始</b></li>
 * </ol>
 * <p>纯函数，无 IO / 时间依赖，包结构可直接单测断言。
 */
public final class PackPlanner {

    /**
     * 五个育的官方顺序。<b>不要改成"德智体美劳"</b>——桌面那张思维导图就是这么画错的。
     */
    public static final List<Dim> FOLDER_ORDER = List.of(
            new Dim("moral", "德育"),
            new Dim("intellect", "智育"),
            new Dim("aesthetic", "美育"),
            new Dim("physique", "体育"),
            new Dim("labour", "劳育"));

    /** Windows 文件名非法字符 + 控制字符 */
    private static final String ILLEGAL = "\\/:*?\"<>|";

    /** 文件名单段上限，留点余量给路径和扩展名 */
    private static final int MAX_NAME = 72;

    private PackPlanner() {
    }

    public record Dim(String id, String name) {
    }

    /**
     * @param report    算分结果（决定哪些材料计入、归到哪个育、行序）
     * @param materials 参与算分的材料，顺序必须与 {@code report} 里的 materialIndex 一致
     * @param owner     本人信息，只用来拼压缩包名
     */
    public static PackPlan plan(ScoreReport report, List<ScoreInput.Material> materials, OwnerProfile owner) {
        List<ScoreReport.ScoreLine> lines = report.lines() == null ? List.of() : report.lines();

        // ── 1 步：按官方顺序建五个文件夹（空的也建，结构是硬规格）──────
        Map<String, String> nameOfDim = new LinkedHashMap<>();
        if (report.dimensions() != null) {
            report.dimensions().forEach(d -> nameOfDim.put(d.id(), d.name()));
        }
        Map<String, List<PackPlan.Item>> itemsOfDim = new LinkedHashMap<>();
        Map<String, Set<Integer>> seen = new LinkedHashMap<>();
        for (Dim d : FOLDER_ORDER) {
            itemsOfDim.put(d.id(), new ArrayList<>());
            seen.put(d.id(), new LinkedHashSet<>());
        }

        // ── 2 步：把计入的行塞进对应文件夹 ───────────────────────────
    // 同一育内命中多条规则 → 只放一份，编号取首次出现位置；跨育则各文件夹独立一份、
    // 各自编号（用户确认"不同方向的材料不会放一起"），不共用编号。
        Set<Integer> packed = new LinkedHashSet<>();
        for (ScoreReport.ScoreLine line : lines) {
            if (!line.included() || !itemsOfDim.containsKey(line.dimension())) {
                continue;
            }
            if (!seen.get(line.dimension()).add(line.materialIndex())) {
                continue;   // 本文件夹里已经有这份材料了
            }
            List<PackPlan.Item> bucket = itemsOfDim.get(line.dimension());
            ExtractedFact fact = factOf(materials, line.materialIndex());
            // 编号只看本文件夹：序号 = 该文件夹内第几个
            int number = bucket.size() + 1;
            // 扩展名必须以"真正要打进包的原件"为准：ScoreLine 里的文件名只是算分时的标记，
            // 两者不一致时若取错，包里会出现没有后缀、双击打不开的材料
            String original = originalNameOf(materials, line.materialIndex(), line.fileName());
            bucket.add(new PackPlan.Item(
                    number,
                    line.materialIndex(),
                    original,
                    rename(number, fact, original),
                    line.groupName(),
                    line.score(),
                    line.basis()));
            packed.add(line.materialIndex());
        }

        List<PackPlan.Folder> folders = new ArrayList<>();
        for (Dim d : FOLDER_ORDER) {
            folders.add(new PackPlan.Folder(nameOfDim.getOrDefault(d.id(), d.name()), d.id(),
                    List.copyOf(itemsOfDim.get(d.id()))));
        }

        // ── 3 步：没进包的材料必须说清为什么 ────────────────────────
        List<PackPlan.Skipped> skipped = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            if (packed.contains(i)) {
                continue;
            }
            String fileName = materials.get(i).fileName() == null ? "" : materials.get(i).fileName();
            skipped.add(new PackPlan.Skipped(i, fileName, skipReason(report, lines, i)));
        }

        return new PackPlan(
                zipName(owner),
                report.academicYear(),
                List.copyOf(folders),
                List.copyOf(skipped),
                warnings(report, folders, skipped));
    }

    // ── 命名 ───────────────────────────────────────────────────────────

    /**
     * {@code 材料1：蓝桥杯+国赛一等奖.jpg}
     *
     * <p>「活动」取证书名称，「奖项/身份」取 achievement；读不出奖项（例如聘书）就只留活动名，
     * 不硬编一个"参与奖"之类的词。
     */
    public static String rename(int number, ExtractedFact fact, String originalName) {
        String ext = extensionOf(originalName);
        String activity = clean(fact == null ? null : fact.title());
        String award = clean(fact == null ? null : fact.achievement());

        String body;
        if (activity == null) {
            body = clean(stripExtension(originalName));
            if (body == null) {
                body = "未命名材料";
            }
        } else if (award == null || award.equals(activity)) {
            body = activity;
        } else {
            body = activity + "+" + award;
        }
        return "材料" + Math.max(number, 1) + "：" + cut(body) + ext;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase();
        // 太长的"扩展名"多半不是扩展名
        return ext.length() <= 6 ? ext : "";
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 去掉非法字符与控制字符，压掉连续空白 */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toCharArray()) {
            if (Character.isISOControl(c) || ILLEGAL.indexOf(c) >= 0) {
                continue;
            }
            sb.append(c);
        }
        String s = sb.toString().replaceAll("\\s+", " ").trim();
        // Windows 不允许文件名以点或空格结尾
        while (s.endsWith(".") || s.endsWith(" ")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    private static String cut(String s) {
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME).trim();
    }

    /** {@code 2024xxxx_廖展滨.zip}；缺字段时用可读占位，控制器会先拦一道 */
    public static String zipName(OwnerProfile owner) {
        String id = clean(owner == null ? null : owner.studentId());
        String name = clean(owner == null ? null : owner.name());
        if (id == null && name == null) {
            return "综测材料.zip";
        }
        if (id == null) {
            return name + ".zip";
        }
        if (name == null) {
            return id + ".zip";
        }
        return id + "_" + name + ".zip";
    }

    // ── 原因与提醒 ─────────────────────────────────────────────────────

    private static String skipReason(ScoreReport report, List<ScoreReport.ScoreLine> lines, int index) {
        if (report.unmatched() != null) {
            for (ScoreReport.Unmatched u : report.unmatched()) {
                if (u.materialIndex() == index) {
                    return "没命中任何加分规则：" + (u.reason() == null ? "" : u.reason());
                }
            }
        }
        List<String> mine = lines.stream()
                .filter(l -> l.materialIndex() == index)
                .map(ScoreReport.ScoreLine::reason)
                .filter(r -> r != null && !r.isBlank())
                .distinct()
                .toList();
        if (!mine.isEmpty()) {
            return "未计入：" + String.join("；", mine);
        }
        return "没有算出可用分数，未放进压缩包（请在页面上确认这条材料归到哪个育）";
    }

    private static List<String> warnings(ScoreReport report, List<PackPlan.Folder> folders,
                                        List<PackPlan.Skipped> skipped) {
        List<String> out = new ArrayList<>();
        long pending = report.lines() == null ? 0 : report.lines().stream().filter(ScoreReport.ScoreLine::needsConfirm).count();
        if (pending > 0) {
            out.add("还有 " + pending + " 条加分项待确认（候选未选定 / 分值未定），包里的分值可能与最终结果不一致");
        }
        if (!skipped.isEmpty()) {
            out.add("有 " + skipped.size() + " 份材料没进包，原因见「未打包」列表");
        }
        List<String> empty = folders.stream()
                .filter(f -> f.items().isEmpty())
                .map(PackPlan.Folder::name)
                .toList();
        if (!empty.isEmpty()) {
            out.add("这些文件夹是空的：" + String.join("、", empty)
                    + "（细则对应章节还没结构化时会这样，不代表你没有材料）");
        }
        if (report.overall() == null) {
            out.add("总分还没算出来（有维度缺基础分），评定表里对应单元格会留空");
        }
        return out;
    }

    private static ExtractedFact factOf(List<ScoreInput.Material> materials, int index) {
        if (index < 0 || index >= materials.size()) {
            return null;
        }
        return materials.get(index).fact();
    }

    /** 原件名优先取材料清单里的，缺失才退回 ScoreLine 带的 */
    private static String originalNameOf(List<ScoreInput.Material> materials, int index, String fromLine) {
        if (index >= 0 && index < materials.size()) {
            String name = materials.get(index).fileName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return fromLine == null ? "" : fromLine;
    }
}
