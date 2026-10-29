package com.abing.assessment.api;

import com.abing.assessment.common.Result;
import com.abing.assessment.domain.model.OwnerProfile;
import com.abing.assessment.infra.profile.OwnerProfileStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本人信息（姓名 / 学号 / 班级 / 学院 / 年级）。
 *
 * <p>填一次，全流程复用：批量材料统一采用这里的姓名，
 * 不再要求模型把名字"读准"，只在识别值与它不一致时提示用户确认。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final OwnerProfileStore store;

    public ProfileController(OwnerProfileStore store) {
        this.store = store;
    }

    @GetMapping
    public Result<Map<String, Object>> get() {
        return Result.success(view(store.load()));
    }

    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody OwnerProfile body) throws IOException {
        return Result.success(view(store.save(body)));
    }

    private Map<String, Object> view(OwnerProfile p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", p.name());
        data.put("studentId", p.studentId());
        data.put("className", p.className());
        data.put("college", p.college());
        data.put("grade", p.grade());
        data.put("hasName", p.hasName());
        data.put("storage", store.describeLocation());
        return data;
    }
}
