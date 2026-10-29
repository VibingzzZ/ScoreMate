package com.abing.assessment.infra.profile;

import com.abing.assessment.domain.model.OwnerProfile;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本人信息的本地存取：{@code %LOCALAPPDATA%\Assessment\owner.json}，不加密——
 * 里面只有姓名学号，没有密钥，加密反而让人以为存了密码。
 *
 * <p>读写失败一律降级为空档案：最多少一层校验，不能让程序起不来。
 */
@Component
public class OwnerProfileStore {

    private static final Logger log = LoggerFactory.getLogger(OwnerProfileStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;

    public OwnerProfileStore() {
        this(defaultFile());
    }

    /** 测试用 */
    OwnerProfileStore(Path file) {
        this.file = file;
    }

    public OwnerProfile load() {
        if (!Files.exists(file)) {
            return OwnerProfile.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            OwnerProfile p = MAPPER.readValue(json, OwnerProfile.class);
            return p == null ? OwnerProfile.empty() : p.normalized();
        } catch (Exception e) {
            log.warn("读取本人信息失败（按空处理，可重新填写）：{}", e.toString());
            return OwnerProfile.empty();
        }
    }

    public OwnerProfile save(OwnerProfile profile) throws IOException {
        OwnerProfile normalized = (profile == null ? OwnerProfile.empty() : profile).normalized();
        Files.createDirectories(file.getParent());
        Files.writeString(file, MAPPER.writeValueAsString(normalized), StandardCharsets.UTF_8);
        log.info("本人信息已保存：姓名={} 学号={}", normalized.name(), normalized.studentId());
        return normalized;
    }

    public String describeLocation() {
        return file.toString();
    }

    private static Path defaultFile() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = (localAppData == null || localAppData.isBlank())
                ? Paths.get(System.getProperty("user.home"), ".assessment")
                : Paths.get(localAppData, "Assessment");
        return base.resolve("owner.json");
    }
}
