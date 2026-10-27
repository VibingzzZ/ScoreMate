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
