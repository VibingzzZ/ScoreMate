package com.abing.assessment.infra.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 验证 Key 的存储行为。
 *
 * <p>最要紧的一条是 <b>落盘的一定是密文</b>——这条不能靠"看代码觉得没问题"，
 * 因为它一旦错了，用户的钱就裸着躺在磁盘上。
 */
class DpapiApiKeyStoreTest {

    private static final String KEY = "sk-test-abcdefghijklmnop-1234567890";

    @Test
    void dpapi往返一致且磁盘上是密文(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"),
                "DPAPI 只在 Windows 生效");

        Path keyFile = tempDir.resolve("llm.key");
        DpapiApiKeyStore store = new DpapiApiKeyStore(keyFile);

        assertNull(store.load(), "还没保存时应该读不到");

        store.save(KEY);

        assertTrue(Files.exists(keyFile), "保存后应该有文件");
        assertEquals(KEY, store.load(), "读回来的应该和存进去的完全一致");

        // 核心断言：文件内容不能包含明文
        String raw = Files.readString(keyFile, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(KEY), "磁盘上出现了明文 Key，这是严重问题");
        assertFalse(raw.contains("sk-test"), "磁盘上出现了部分明文 Key");

        store.clear();
        assertNull(store.load(), "清除后应该读不到");
        assertFalse(Files.exists(keyFile), "清除后文件应该被删除");
    }

    @Test
    void 空值保存等同于清除(@TempDir Path tempDir) throws Exception {
        DpapiApiKeyStore store = new DpapiApiKeyStore(tempDir.resolve("llm.key"));
        store.save(KEY);
        store.save("   ");
        assertNull(store.load(), "保存空白串应该等于清除");
    }
}
