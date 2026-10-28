package com.abing.assessment.infra.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key 泄露回归保护：锁死 {@code toString()} 永不打印明文 Key。
 * 将来有人不小心 log 了整个 profile，或误删掩码重写，这里会先红。
 */
class LlmProfileTest {

    @Test
    void toString不泄露明文Key() {
        String secret = "sk-1234567890abcdef1234567890abcdef";
        LlmProfile p = new LlmProfile("openai", "https://api.openai.com/v1", "gpt-4o", secret);
        String s = p.toString();
        assertFalse(s.contains(secret), "toString 不应包含完整 Key");
        assertFalse(s.contains(secret.substring(10, 26)), "toString 不应包含 Key 中段");
        assertTrue(s.contains("****"), "应打码");
    }

    @Test
    void 短Key整体遮蔽() {
        LlmProfile p = new LlmProfile("x", "u", "m", "shortkey");
        String s = p.toString();
        assertTrue(s.contains("****"));
        assertFalse(s.contains("shortkey"), "短 Key 也不应外露");
    }

    @Test
    void 空Key显示为空() {
        LlmProfile p = new LlmProfile("x", "u", "m", null);
        assertTrue(p.toString().contains("(空)"));
    }

    @Test
    void LlmProperties的toString同样不泄露() {
        LlmProperties props = new LlmProperties(
                "https://api.openai.com/v1", "gpt-4o", "sk-abcdef0123456789xyz", "openai", 120, "extract-v1.txt");
        String s = props.toString();
        assertFalse(s.contains("sk-abcdef0123456789xyz"), "LlmProperties.toString 不应含明文 Key");
        assertTrue(s.contains("****"));
    }
}
