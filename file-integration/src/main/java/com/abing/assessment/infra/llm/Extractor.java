package com.abing.assessment.infra.llm;

import com.abing.assessment.domain.model.ExtractionResult;

/**
 * 材料抽取端口。
 *
 * <p>domain 只依赖这个接口，不知道背后是云端 API、Dify 工作流还是本机 Ollama。
 * 换模型/换供应商 = 换实现或改配置，业务代码不动（架构文档 ADR-4）。
 *
 * <p>实现类必须保证：<b>不把 API Key 写进日志、异常消息、返回值</b>。
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
     * 用指定的 prompt 抽一张图，返回模型原始文本（不做固定 schema 解析）。
     *
     * <p>为什么要有这个口子：材料抽取的字段是固定的（{@code ExtractedFact}），
     * 而「把评分细则表转成规则」是完全不同的 schema，且 prompt 更长、输出更大、
     * 还要带页码上下文。与其为它再写一份 HttpClient + Key 管理 + 重试 + thinking 开关，
     * 不如把「怎么调模型」这部分复用出来——Key 管理只有一处，才不会漏打日志或漏关思考。
     *
     * @param image     图片字节
     * @param mimeType  例如 image/jpeg
     * @param prompt    完整 prompt（调用方负责拼好上下文）
     * @param maxTokens 本次输出预算（规则页可能一次吐几十条，需要比材料抽取大）
     * @return 模型返回的正文文本 + 本次调用信息
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
