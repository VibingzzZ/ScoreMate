package com.abing.assessment.infra.config;

import com.abing.assessment.domain.model.DimensionSpec;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.RuleGroup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 契约测试：真实的细则 YAML 必须能被解析成预期的结构。
 *
 * <p>它盯的是「配置写错但代码不报错」这类最恶心的问题——比如某个规则组挂到了
 * 不存在的维度上、某条 items 忘了写 score。这类错误不会抛异常，只会让算分静默偏向 0。
 */
class RuleConfigContractTest {

    private static YamlRuleLoader loader;

    @BeforeAll
    static void loadOnce() {
        loader = new YamlRuleLoader();
        loader.load();
    }

    @Test
    @DisplayName("configs 目录可达，且没有配置问题")
    void noProblems() {
        assumeTrue(loader.configDir() != null, "没有找到 configs 目录（可能是测试工作目录不对）");
        assertEquals(List.of(), loader.problems(), "细则 YAML 有问题必须修，不能带着问题跑");
        assertFalse(loader.rules().isEmpty(), "至少要加载出一份规则");
    }

    @Test
    @DisplayName("五个维度齐全，权重和为 1")
    void dimensions() {
        assumeTrue(loader.configDir() != null);
        RuleConfig cfg = loader.rules().get(0);
        assertEquals(5, cfg.dimensions().size());
        assertEquals(List.of("moral", "intellect", "physique", "aesthetic", "labour"),
                cfg.dimensions().stream().map(DimensionSpec::id).toList());
        double sum = cfg.dimensions().stream().mapToDouble(DimensionSpec::weight).sum();
        assertEquals(1d, sum, 0.001);
        assertNotNull(cfg.overallFormula());
        assertTrue(cfg.overallFormula().contains("德育"));
    }

    @Test
    @DisplayName("德育公式与官方原文一致：基础×30% + 互评×20% + 加分×50% − 扣分")
    void moralFormula() {
        assumeTrue(loader.configDir() != null);
        DimensionSpec moral = loader.rules().get(0).dimensionIndex().get("moral");
        assertNotNull(moral);
        assertEquals("base*0.30 + peer*0.20 + bonus*0.50 - deduction", moral.formula());
        assertTrue(moral.floorZero(), "细则规定扣分大于前三项时为 0");
        assertEquals(2, moral.inputs().size());
    }

    @Test
    @DisplayName("德育规则组齐全，且每条 item 都有匹配条件")
    void groups() {
        assumeTrue(loader.configDir() != null);
        RuleConfig cfg = loader.rules().get(0);
        List<String> ids = cfg.ruleGroups().stream().map(RuleGroup::id).toList();
        assertTrue(ids.containsAll(List.of("student_officer", "honor_individual", "honor_collective",
                "activity_award", "summer_practice", "article", "standalone", "moral_deduction")),
                "缺组：" + ids);

        cfg.ruleGroups().forEach(g -> {
            assertFalse(g.items().isEmpty(), g.id() + " 没有条目");
            g.items().forEach(it -> assertFalse(it.match().isEmpty(),
                    g.id() + " 里有一条没有 match，会误伤所有材料"));
        });
    }

    @Test
    @DisplayName("学生干部组最多计 2 项（细则：身兼多项最多选两项）")
    void officerCap() {
        assumeTrue(loader.configDir() != null);
        RuleGroup officer = loader.rules().get(0).ruleGroups().stream()
                .filter(g -> g.id().equals("student_officer")).findFirst().orElseThrow();
        assertEquals(2, officer.maxItems());
    }

    @Test
    @DisplayName("集体荣誉的 scores 列数与 columns 对齐")
    void matrixAligned() {
        assumeTrue(loader.configDir() != null);
        loader.rules().get(0).ruleGroups().stream()
                .filter(RuleGroup::hasColumns)
                .forEach(g -> g.items().stream().filter(it -> it.isMatrix()).forEach(it ->
                        assertEquals(g.columns().size(), it.scores().size(),
                                g.id() + " 的 scores 与 columns 列数不一致")));
    }
}
