package com.abing.assessment.infra.llm;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用 Windows DPAPI 保存 API Key。不自己写 AES：AES 的密钥总得放在某处，
 * 塞进 jar 等于把钥匙和箱子一起送人；DPAPI 用系统托管的用户主密钥。
 *
 * <p>边界诚实：挡不住以你身份运行的程序，防的是疏忽（误传文件、看日志、拷给别人）。
 * 非 Windows 退化为仅内存保存，重启重输——宁可麻烦，不明文落盘。
 */
@Component
public class DpapiApiKeyStore implements ApiKeyStore {

    private static final Logger log = LoggerFactory.getLogger(DpapiApiKeyStore.class);

    private static final String FILE_NAME = "llm.key";

    private final Path file;
    private final boolean windows;

    /** 非 Windows 时的兜底：只在本次进程内有效 */
    private volatile String memoryOnly;

    public DpapiApiKeyStore() {
        this(defaultFile());
    }

    /** 测试用：允许指定文件位置，避免碰到用户真实的 Key */
    DpapiApiKeyStore(Path file) {
        this.file = file;
        this.windows = Platform.isWindows();
        if (!windows) {
            log.warn("当前不是 Windows，API Key 不会落盘（重启后需重新填写）");
        }
    }

    private static Path defaultFile() {
        return baseDir().resolve(FILE_NAME);
    }

    @Override
    public void save(String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            clear();
            return;
        }
        if (!windows) {
            memoryOnly = apiKey;
            return;
        }
        Files.createDirectories(file.getParent());
        byte[] encrypted = Crypt32Util.cryptProtectData(apiKey.getBytes(StandardCharsets.UTF_8));
        Files.write(file, encrypted);
        log.info("API Key 已加密保存（DPAPI，当前 Windows 用户可用）：{}", file);
    }

    @Override
    public String load() {
        if (!windows) {
            return memoryOnly;
        }
        if (!Files.exists(file)) {
            return null;
        }
        try {
            byte[] encrypted = Files.readAllBytes(file);
            byte[] plain = Crypt32Util.cryptUnprotectData(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 换了 Windows 用户 / 文件被替换 / 系统重装，都会走到这里。
            // 不抛异常：让用户重新填一次，比启动失败好
            log.warn("读取已保存的 Key 失败（可能换了 Windows 用户或文件损坏），请重新填写：{}", e.toString());
            return null;
        }
    }

    @Override
    public void clear() {
        memoryOnly = null;
        if (!windows) {
            return;
        }
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("已清除保存的 API Key");
            }
        } catch (IOException e) {
            log.warn("清除 Key 文件失败：{}", e.toString());
        }
    }

    @Override
    public String describeStorage() {
        return windows
                ? "已用 Windows DPAPI 加密保存在本机（只有当前 Windows 用户能解开）"
                : "当前系统不支持 DPAPI，Key 仅保存在内存中";
    }

    private static Path baseDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return Paths.get(System.getProperty("user.home"), ".assessment");
        }
        return Paths.get(localAppData, "Assessment");
    }
}
