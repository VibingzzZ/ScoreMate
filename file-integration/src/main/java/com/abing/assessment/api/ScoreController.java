package com.abing.assessment.api;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.DimensionSpec;
import com.abing.assessment.domain.model.RuleConfig;
import com.abing.assessment.domain.model.ScoreInput;
import com.abing.assessment.domain.model.ScoreReport;
import com.abing.assessment.domain.rules.AcademicYear;
import com.abing.assessment.domain.rules.ScoreEngine;
import com.abing.assessment.infra.config.YamlRuleLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 算分接口。
 *
 * <p>前端把「已扫描并人工确认过的材料事实」整包发过来，后端用规则引擎算，
 * <b>不落库、不留状态</b>：同一份输入永远同一个结果，刷新页面重算也没副作用。
 */
@RestController
@RequestMapping("/api/score")
public class ScoreController {

    private final YamlRuleLoader ruleLoader;

    public ScoreController(YamlRuleLoader ruleLoader) {
        this.ruleLoader = ruleLoader;
    }

    /**
     * 算分所需的元信息：可选的评测学年、当前生效的细则、每个维度要用户填什么。
     * 页面打开时拉一次，用来渲染学年下拉和基础分输入框。
     */
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        RuleConfig cfg = activeRule();
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("school", cfg.school());
        data.put("college", cfg.college());
        data.put("ruleVersion", cfg.ruleVersion());
        data.put("sourceFile", cfg.sourceFile());
        data.put("hasStructuredRules", cfg.hasStructuredRules());

        // 默认给最近三个学年，用户自己选
        int base = LocalDate.now().getMonthValue() >= 9
                ? LocalDate.now().getYear() : LocalDate.now().getYear() - 1;
        List<Map<String, Object>> years = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            AcademicYear y = AcademicYear.of(base - i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", y.label());
            m.put("start", y.start().toString());
            m.put("end", y.end().toString());
            years.add(m);
        }
        data.put("academicYears", years);
        data.put("defaultYear", AcademicYear.of(base).label());

        List<Map<String, Object>> dims = new ArrayList<>();
        for (DimensionSpec d : cfg.dimensions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name());
            m.put("weight", d.weight());
            m.put("formula", d.formula());
            m.put("note", d.note());
            m.put("hasRules", cfg.hasRulesFor(d.id()));
            List<Map<String, Object>> inputs = new ArrayList<>();
            for (DimensionSpec.InputSpec is : d.inputs()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("key", d.id() + "." + is.key());
                im.put("rawKey", is.key());
                im.put("name", is.name());
                im.put("hint", is.hint());
                inputs.add(im);
            }
            m.put("inputs", inputs);
            dims.add(m);
        }
        data.put("dimensions", dims);

        // 配置层面的问题（例如某组挂错维度）也一并暴露，别让人以为算分"正常"其实规则没加载
        data.put("configProblems", ruleLoader.problems());
        return Result.success(data);
    }

    @PostMapping
    public Result<ScoreReport> score(@RequestBody ScoreInput body) {
        RuleConfig cfg = activeRule();
        if (body == null) {
            throw new BusinessException("请求体为空", 400);
        }
        if (body.academicYear() == null || body.academicYear().isBlank()) {
            throw new BusinessException("没指定评测学年", 400);
        }
        return Result.success(ScoreEngine.evaluate(body, cfg));
    }

    private RuleConfig activeRule() {
        List<RuleConfig> rules = ruleLoader.rules();
        if (rules.isEmpty()) {
            throw new BusinessException("还没有加载到任何规则配置：请确认 configs/ 下有今年份的细则 YAML", 409);
        }
        return rules.get(0);
    }
}
