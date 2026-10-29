package com.abing.assessment.infra.llm;

import java.io.IOException;

/**
 * API Key 的存取。
 *
 * <p>存在的理由：BYOK 模式下 Key 是<b>用户自己的钱</b>，不能明文落盘、不能进日志、
 * 不能让页面回显完整值（架构文档 §2.7）。
 *
 * <p>Windows 上用 DPAPI 加密（只有当前 Windows 用户能解开），
 * 换用户、换机器、把文件拷走都解不开。
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
