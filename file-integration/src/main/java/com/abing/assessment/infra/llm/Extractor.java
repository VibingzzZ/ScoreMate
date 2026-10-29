package com.abing.assessment.infra.llm;

import com.abing.assessment.domain.model.ExtractionResult;

/**
 * 材料抽取端口。domain 只依赖它，不关心背后是云端 API、Dify 还是本地 Ollama；
 * 换模型 = 换实现或改配置（ADR-4）。
 *
 * <p>实现类必须保证：<b>不把 Key 写进日志、异常消息、返回值</b>。
 */
public interface Extractor {

    /**
     * 从一张材料图片里抽出结构化事实。
     *
     * @param image    图片字节
     * @param mimeType 例如 image/jpeg
     * @return 事实 + 本次调用的模型/耗时/token
     * @throws com.abing.assessment.common.BusinessException 未配置、鉴权失败、限流、模型输出不合法等
     */
    ExtractionResult extract(byte[] image, String mimeType);

    /**
     * 用指定 prompt 抽一张图，返回模型原始文本（不做固定 schema 解析）。
     *
     * <p>给「细则转规则」复用：那套 schema 与 {@code ExtractedFact} 完全不同，prompt 更长、
     * 输出更大。与其再写一份 HttpClient + Key 管理 + 重试 + thinking 开关，不如复用这一处——
     * Key 管理只有一份，才不会漏打日志或漏关思考。
     *
     * @param image     图片字节
     * @param mimeType  如 image/jpeg
     * @param prompt    完整 prompt（上下文由调用方拼好）
     * @param maxTokens 输出预算（规则页一次可能吐几十条）
     * @return 正文文本 + 本次调用信息
     */
    RawExtraction extractRaw(byte[] image, String mimeType, String prompt, int maxTokens);

    /** {@link #extractRaw} 的结果：只带原始文本，不做 schema 绑定 */
    record RawExtraction(String text, String model, long latencyMs, Integer totalTokens) {
    }

    /**
     * 连通性测试：只验证 Key 是否有效、模型名是否存在。
     * <p>不发图片，所以很快，也不消耗多少 token。用户填完 Key 后立刻调一次，
     * 让他当场知道"填对了没有"，而不是等上传证书才发现 401。
     *
     * @throws com.abing.assessment.common.BusinessException Key 无效 / 余额不足 / 模型名错 / 限流
     */
    void ping();

    /** 是否具备调用条件（有 Key、有 prompt） */
    boolean isConfigured();

    /** 给页面展示的描述，不含 Key */
    String describe();
}
