package com.abing.assessment.infra.llm;

import java.io.IOException;

/**
 * API Key 存取。BYOK 下 Key 是用户自己的钱：不明文落盘、不进日志、不回显完整值。
 * Windows 上用 DPAPI 加密，换用户 / 换机器都解不开。
 */
public interface ApiKeyStore {

    /** 保存。实现方负责加密，绝不明文落盘 */
    void save(String apiKey) throws IOException;

    /** 读取；没有保存过返回 null */
    String load();

    /** 清除已保存的 Key */
    void clear();

    /** 存储位置的说明，给页面显示用（不含 Key 本身） */
    String describeStorage();
}
