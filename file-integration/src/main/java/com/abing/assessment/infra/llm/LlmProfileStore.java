package com.abing.assessment.infra.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 多份模型配置档案的存取（CC-SWITCH 式「配置列表 + 当前启用」）。
 *
 * <p><b>为什么不让存储层直接管列表</b>：{@link ApiKeyStore} 的职责就一件事——把一段字符串
 * 加密/解密落盘。多配置只是「这段字符串里装什么」的问题，属于上层编排，所以列表结构放在这里，
 * 底层依旧是一个加密文件，安全性完全不变（DPAPI 整包加密）。
 *
 * <p><b>落盘格式</b>：
 * <pre>{@code
 * { "activeId": "a1b2c3", "profiles": [ {id,name,provider,baseUrl,model,apiKey}, ... ] }
 * }</pre>
 *
 * <p><b>旧版本迁移</b>：单档案时代存的是裸的 {@code {provider,baseUrl,model,apiKey}}。
 * 读取时若发现没有 {@code profiles} 字段，就当成"只有一份档案"，自动补 id 并设为启用——
 * 用户升级后不会丢配置，也不用重新填 Key。
 */
@Component
public class LlmProfileStore {

    private static final Logger log = LoggerFactory.getLogger(LlmProfileStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ApiKeyStore backing;

    public LlmProfileStore(ApiKeyStore backing) {
        this.backing = backing;
    }

    /** 落盘用的整体状态（public 是为了让 Jackson 能反射构造 record） */
    public record State(String activeId, List<LlmProfile> profiles) {
        static State empty() {
            return new State(null, List.of());
        }
    }

    // ── 读 ───────────────────────────────────────────────────────────────

    /** 全部档案（含明文 Key，仅供内部使用，绝不直接返回给页面） */
    public synchronized List<LlmProfile> list() {
        return load().profiles();
    }

    /**
     * 当前启用的档案；没有启用标记时回落为第一份。
     * 一份都没有则返回 null。
     */
    public synchronized LlmProfile active() {
        State s = load();
        if (s.profiles().isEmpty()) {
            return null;
        }
        if (s.activeId() != null) {
            for (LlmProfile p : s.profiles()) {
                if (s.activeId().equals(p.id())) {
                    return p;
                }
            }
        }
        return s.profiles().get(0);
    }

    // ── 写 ───────────────────────────────────────────────────────────────

    /**
     * 新增或更新一份档案。
     * <ul>
     *   <li>id 为空 → 新建（分配 id），并在没有启用项时自动启用</li>
     *   <li>id 已存在 → 按 id 原位覆盖元信息</li>
     * </ul>
     * 返回落盘后的档案（带 id）。
     *
     * <p><b>注意</b>：调用方要"先测后留"的话，请先用 {@link #snapshot()} 存档，
     * 测失败后 {@link #restore(String)} 回滚。
     */
    public synchronized LlmProfile upsert(LlmProfile incoming) {
        State s = load();
        List<LlmProfile> next = new ArrayList<>(s.profiles());
        LlmProfile toStore = incoming.withIdIfAbsent();

        int idx = indexOf(next, toStore.id());
        if (idx >= 0) {
            next.set(idx, toStore);
        } else {
            next.add(toStore);
        }

        String activeId = s.activeId();
        if (activeId == null || indexOf(next, activeId) < 0) {
            activeId = toStore.id();
        }
        save(new State(activeId, next));
        log.info("模型配置已保存：{}（共 {} 份，启用 {}）",
                toStore.displayName(), next.size(), activeId);
        return toStore;
    }

    /** 切换启用项；id 不存在返回 false */
    public synchronized boolean activate(String id) {
        State s = load();
        if (indexOf(s.profiles(), id) < 0) {
            return false;
        }
        save(new State(id, s.profiles()));
        return true;
    }

    /**
     * 删除一份；若删的正是启用项，自动切到剩下的第一份。
     * 返回是否确实删掉了。
     */
    public synchronized boolean delete(String id) {
        State s = load();
        List<LlmProfile> next = new ArrayList<>(s.profiles());
        if (indexOf(next, id) < 0) {
            return false;
        }
        next.removeIf(p -> id.equals(p.id()));
        String activeId = id.equals(s.activeId())
                ? (next.isEmpty() ? null : next.get(0).id())
                : s.activeId();
        save(new State(activeId, next));
        return true;
    }

    /** 清空所有档案（页面上的"全部清除"） */
    public synchronized void clearAll() {
        backing.clear();
    }

    public synchronized boolean isEmpty() {
        return load().profiles().isEmpty();
    }

    /** 存储位置说明（给页面显示，不含任何 Key） */
    public String storageDescription() {
        return backing.describeStorage();
    }

    // ── 事务性回滚（"先测后留"用） ─────────────────────────────────────────

    /** 当前落盘内容的快照（加密前的原始 JSON），用于测试失败时回滚 */
    public synchronized String snapshot() {
        return backing.load();
    }

    /** 回滚到快照；快照为 null 表示当时什么都没有，直接清空 */
    public synchronized void restore(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                backing.clear();
            } else {
                backing.save(raw);
            }
        } catch (IOException e) {
            log.warn("回滚模型配置失败：{}", e.toString());
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private void save(State state) {
        try {
            backing.save(MAPPER.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("模型配置序列化失败：" + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("模型配置保存失败：" + e.getMessage(), e);
        }
    }

    private State load() {
        String raw = backing.load();
        if (raw == null || raw.isBlank()) {
            return State.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            if (root == null || root.isNull()) {
                return State.empty();
            }
            // 旧格式：单个 profile 裸露在根上（没有 profiles 字段）
            if (!root.has("profiles")) {
                LlmProfile legacy = MAPPER.treeToValue(root, LlmProfile.class);
                if (legacy == null || !legacy.hasKey()) {
                    return State.empty();
                }
                LlmProfile migrated = legacy.withIdIfAbsent();
                log.info("检测到旧版单档案配置，已自动迁移为列表（{}）", migrated.displayName());
                return new State(migrated.id(), List.of(migrated));
            }
            State state = MAPPER.treeToValue(root, State.class);
            if (state == null || state.profiles() == null) {
                return State.empty();
            }
            List<LlmProfile> cleaned = new ArrayList<>();
            for (LlmProfile p : state.profiles()) {
                if (p != null && p.hasKey()) {
                    cleaned.add(p.withIdIfAbsent());
                }
            }
            return new State(state.activeId(), cleaned);
        } catch (Exception e) {
            // 不抛：让用户重新填一次，比启动失败好（和 DpapiApiKeyStore 的处理一致）
            log.warn("已保存的模型配置无法解析（可能格式变化），按空处理：{}", e.toString());
            return State.empty();
        }
    }

    private static int indexOf(List<LlmProfile> list, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).id())) {
                return i;
            }
        }
        return -1;
    }
}
