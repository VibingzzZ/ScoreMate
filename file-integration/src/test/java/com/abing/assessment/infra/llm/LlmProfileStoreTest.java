package com.abing.assessment.infra.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多配置档案存储的行为锁。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>新增 / 切换 / 删除，启用项自动兜底</li>
 *   <li>旧版单档案（裸 profile JSON）能自动迁移，不丢配置</li>
 *   <li>快照 + 回滚（"先测后留"的底层保证）</li>
 * </ol>
 */
class LlmProfileStoreTest {

    /** 内存版存储：不碰真实 DPAPI 文件，也不污染用户目录 */
    static class MemoryStore implements ApiKeyStore {
        private String value;

        @Override
        public void save(String apiKey) {
            this.value = apiKey;
        }

        @Override
        public String load() {
            return value;
        }

        @Override
        public void clear() {
            value = null;
        }

        @Override
        public String describeStorage() {
            return "memory";
        }
    }

    private LlmProfile profile(String name, String provider, String model) {
        return new LlmProfile(null, name, provider,
                ProviderDefaults.defaultBaseUrl(provider), model, "sk-test-1234567890");
    }

    @Test
    void upsert_assignsId_andFirstProfileBecomesActive() {
        LlmProfileStore store = new LlmProfileStore(new MemoryStore());

        LlmProfile saved = store.upsert(profile("我的 DS", "deepseek", "deepseek-flash"));

        assertNotNull(saved.id(), "新增后应分配 id");
        assertEquals(1, store.list().size());
        assertEquals(saved.id(), store.active().id(), "第一份配置应自动启用");
    }

    @Test
    void upsert_sameId_updatesInPlace_andCanSwitch() {
        LlmProfileStore store = new LlmProfileStore(new MemoryStore());

        LlmProfile a = store.upsert(profile("A", "deepseek", "deepseek-flash"));
        LlmProfile b = store.upsert(profile("B", "openai", "gpt-4o-mini"));

        assertEquals(2, store.list().size(), "两次新增应是两份配置");
        // 约定：已经有启用项时，新增配置不抢占启用状态（由 Controller 显式 activate 刚保存的那份）
        assertEquals(a.id(), store.active().id(), "已有启用项时，新增配置不应抢占");

        assertTrue(store.activate(b.id()));
        assertEquals("B", store.active().name(), "切换后启用项应为 B");

        // 同 id 再写 = 原位更新，不新增，也不改变启用项
        LlmProfile edited = new LlmProfile(a.id(), "A 改名", a.provider(), a.baseUrl(), a.model(), a.apiKey());
        store.upsert(edited);
        assertEquals(2, store.list().size());
        assertTrue(store.list().stream().anyMatch(p -> "A 改名".equals(p.name())), "应按 id 原位更新");
        assertEquals(b.id(), store.active().id(), "编辑非启用配置不应改变启用项");
    }

    @Test
    void delete_active_fallsBackToRemaining() {
        LlmProfileStore store = new LlmProfileStore(new MemoryStore());

        LlmProfile a = store.upsert(profile("A", "deepseek", "deepseek-flash"));
        LlmProfile b = store.upsert(profile("B", "openai", "gpt-4o-mini"));
        store.activate(b.id());

        assertTrue(store.delete(b.id()));
        assertEquals(1, store.list().size());
        assertEquals(a.id(), store.active().id(), "删掉启用项后应自动切到剩下那份");

        assertTrue(store.delete(a.id()));
        assertTrue(store.isEmpty());
        assertNull(store.active());
    }

    @Test
    void legacySingleProfile_isMigrated() {
        MemoryStore backing = new MemoryStore();
        // 单档案时代的落盘格式：裸 profile，没有 profiles/activeId 外层
        backing.save("{\"provider\":\"deepseek\",\"baseUrl\":\"https://api.deepseek.com/v1\","
                + "\"model\":\"deepseek-flash\",\"apiKey\":\"sk-old-key-123456\"}");

        LlmProfileStore store = new LlmProfileStore(backing);

        List<LlmProfile> list = store.list();
        assertEquals(1, list.size(), "旧单档案应被识别为一份配置");
        assertNotNull(list.get(0).id(), "迁移时应补 id");
        assertEquals("deepseek", store.active().provider());
        assertEquals("sk-old-key-123456", store.active().apiKey(), "旧 Key 不能丢");
    }

    @Test
    void snapshotAndRestore_rollsBackFailedSave() {
        LlmProfileStore store = new LlmProfileStore(new MemoryStore());
        LlmProfile a = store.upsert(profile("A", "deepseek", "deepseek-flash"));

        String snapshot = store.snapshot();
        store.upsert(profile("坏配置", "openai", "no-such-model"));

        store.restore(snapshot);

        assertEquals(1, store.list().size(), "回滚后不应留下坏配置");
        assertEquals(a.id(), store.active().id());
    }

    @Test
    void corruptedJson_degradesToEmpty_notCrash() {
        MemoryStore backing = new MemoryStore();
        backing.save("这不是 JSON");

        LlmProfileStore store = new LlmProfileStore(backing);

        assertTrue(store.isEmpty(), "解析失败应按没有配置处理，不能让应用起不来");
        assertNull(store.active());
    }

    @Test
    void displayName_fallsBackToProviderAndModel() {
        LlmProfile unnamed = new LlmProfile("id1", null, "deepseek", "u", "deepseek-flash", "k-1234567890");
        assertEquals("DeepSeek · deepseek-flash", unnamed.displayName());

        LlmProfile named = new LlmProfile("id2", "  实验室 Qwen  ", "custom", "u", "qwen-vl-max", "k-1234567890");
        assertEquals("实验室 Qwen", named.displayName());
    }

    @Test
    void toString_neverLeaksPlainKey() {
        LlmProfile p = new LlmProfile("id", "n", "deepseek", "u", "m", "sk-abcdefghijklmnop");
        assertFalse(p.toString().contains("sk-abcdefghijklmnop"), "toString 不能带明文 Key");
    }

    @Test
    void saveThenLoad_roundTripsThroughJson() throws IOException {
        MemoryStore backing = new MemoryStore();
        LlmProfileStore store = new LlmProfileStore(backing);
        store.upsert(profile("A", "deepseek", "deepseek-flash"));

        Map<String, Object> unused = new HashMap<>();
        String raw = backing.load();
        assertNotNull(raw);

        // 用新实例读同一份落盘内容，模拟"重启程序"
        LlmProfileStore reopened = new LlmProfileStore(backing);
        assertEquals(1, reopened.list().size());
        assertEquals("sk-test-1234567890", reopened.active().apiKey());
    }
}
