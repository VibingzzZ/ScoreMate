package com.abing.assessment.infra.llm;

import com.abing.assessment.common.BusinessException;
import com.abing.assessment.domain.model.ExtractedFact;
import com.abing.assessment.domain.model.ExtractionResult;
import com.abing.assessment.infra.config.ConfigPaths;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的视觉模型接入（DeepSeek / 豆包 / OpenAI / 经网关的 Claude 都走这套）。
 *
 * <p>用 JDK 自带的 {@link HttpClient}，不引第三方 HTTP 库：少一个依赖，少一处升级风险，
 * 而且超时行为明确可控。
 *
 * <p>四个刻意的约束：
 * <ol>
 *   <li>prompt 从 {@code configs/prompt/} 读，改 prompt 不用重新打包</li>
 *   <li>Key / 地址 / 模型来自 {@link ApiKeyProvider} 的当前档案（页面填的优先，其次环境变量），本类不持有</li>
 *   <li>日志里只出现 model / status / 耗时 / token，<b>永不出现 Key</b></li>
 *   <li>错误按 HTTP 状态分类成用户能行动的话（401 和 404 长得像但原因完全不同）</li>
 * </ol>
 */
@Component
public class OpenAiCompatExtractor implements Extractor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatExtractor.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_TOKENS = 1500;
    private static final int PING_MAX_TOKENS = 8;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int LOG_BODY_LIMIT = 300;

    private final LlmProperties props;
    private final ApiKeyProvider apiKeys;
    private final HttpClient http;
    /** null 表示 prompt 模板缺失，此时抽取不可用但不影响应用启动 */
    private final String prompt;

    public OpenAiCompatExtractor(LlmProperties props, ApiKeyProvider apiKeys) {
        this.props = props;
        this.apiKeys = apiKeys;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .build();
        this.prompt = loadPrompt(props);

        if (prompt == null) {
            log.warn("未找到 prompt 模板 configs/prompt/{}，抽取功能不可用", props.promptFile());
        } else if (!apiKeys.hasKey()) {
            log.info("尚未配置 API Key，请在页面上填写（或设置环境变量 LLM_API_KEY）");
        } else {
            log.info("抽取器就绪：{}，Key 来源={}，prompt {} 字符",
                    props.describe(), apiKeys.source(), prompt.length());
        }
    }

    @Override
    public ExtractionResult extract(byte[] image, String mimeType) {
        LlmProfile profile = requireProfile();

        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image);
        String requestBody = toJson(payload(dataUrl, profile.model()));

        long startedAt = System.currentTimeMillis();
        HttpResponse<String> response = postJson(requestBody, profile);
        long latencyMs = System.currentTimeMillis() - startedAt;

        if (response.statusCode() != 200) {
            throw classify(response.statusCode(), response.body());
        }
        return parse(response.body(), latencyMs, profile.model());
    }

    /**
     * 只验证 Key 与模型名。用一条纯文本 ping，不发图片。
     */
    @Override
    public void ping() {
        LlmProfile profile = requireProfile();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", profile.model());
        body.put("temperature", 0);
        body.put("max_tokens", PING_MAX_TOKENS);
        body.put("messages", List.of(Map.of("role", "user", "content", "ping")));

        HttpResponse<String> response = postJson(toJson(body), profile);
        int status = response.statusCode();

        if (status == 200) {
            log.info("连通性测试通过：{}", describe());
            return;
        }
        if (status == 400) {
            log.info("连通性测试收到 400（模型可能不接受纯文本），凭据视为可用");
            return;
        }
        throw classify(status, response.body());
    }

    @Override
    public boolean isConfigured() {
        return prompt != null && apiKeys.currentProfile() != null;
    }

    @Override
    public String describe() {
        LlmProfile p = apiKeys.currentProfile();
        if (p == null) {
            return props.describe();
        }
        return p.model() + " @ " + p.baseUrl() + "（" + (p.provider() == null ? "custom" : p.provider()) + "）";
    }

    // ── 内部实现 ──────────────────────────────────────────────────────────

    private LlmProfile requireProfile() {
        if (prompt == null) {
            throw new BusinessException("缺少 prompt 模板：configs/prompt/" + props.promptFile(), 500);
        }
        LlmProfile profile = apiKeys.currentProfile();
        if (profile == null || !profile.hasKey()) {
            throw new BusinessException("还没有配置 API Key，请在页面顶部填写", 400);
        }
        return profile;
    }

    /** 发请求。apiKey 只在这一层出现，不进入任何日志或异常消息 */
    private HttpResponse<String> postJson(String requestBody, LlmProfile profile) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(profile.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + profile.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("调用模型被中断", 504);
        } catch (IOException e) {
            // 注意：不能把 request 内容带进异常，里面有 Authorization
            throw new BusinessException("连不上模型服务（" + profile.baseUrl() + "）："
                    + e.getClass().getSimpleName() + "，检查网络或代理设置", 504);
        }
    }

    private ExtractionResult parse(String responseBody, long latencyMs, String model) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new BusinessException("模型返回的不是 JSON，无法解析", 502);
        }

        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            throw new BusinessException("模型没有返回内容，可能是图片无法识别或触发了内容审查", 502);
        }

        ExtractedFact fact;
        try {
            fact = MAPPER.readValue(firstJsonObject(content), ExtractedFact.class);
        } catch (JsonProcessingException e) {
            log.warn("模型输出无法解析为 JSON，前 {} 字：{}", LOG_BODY_LIMIT, brief(content));
            throw new BusinessException("模型输出不是合法 JSON，可重试一次；若持续失败请换模型或调整 prompt", 502);
        }

        JsonNode usage = root.path("usage").path("total_tokens");
        Integer tokens = usage.isNumber() ? usage.asInt() : null;

        log.info("抽取完成 model={} {}ms tokens={} docType={} confidence={}",
                model, latencyMs, tokens, fact.docType(), fact.confidence());

        return new ExtractionResult(fact, model, latencyMs, tokens);
    }

    private Map<String, Object> payload(String dataUrl, String model) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", dataUrl));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", List.of(message));
        return body;
    }

    /**
     * 把上游错误翻译成用户能采取行动的提示。
     */
    private BusinessException classify(int status, String body) {
        String hint = switch (status) {
            case 401 -> "API Key 无效或已失效，检查是否复制完整";
            case 402, 403 -> "账户余额不足，或没有开通这个模型";
            case 404 -> "模型名不存在，或接口地址不对";
            case 429 -> "请求过于频繁，等 30 秒再试";
            default -> "模型服务返回异常";
        };
        log.warn("LLM 调用失败 status={} resp={}", status, brief(body));
        int code = (status >= 400 && status < 500) ? status : 502;
        return new BusinessException(hint + "（HTTP " + status + "）", code);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException("构造请求失败：" + e.getOriginalMessage(), 500);
        }
    }

    private static String loadPrompt(LlmProperties props) {
        Path dir = ConfigPaths.resolveConfigDir();
        if (dir == null) {
            return null;
        }
        Path file = dir.resolve("prompt").resolve(props.promptFile());
        try {
            if (Files.isRegularFile(file)) {
                return Files.readString(file, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            log.warn("读取 prompt 失败：{}", e.toString());
        }
        return null;
    }

    /** 容忍 ```json 围栏和前后废话，只取第一个 { 到最后一个 } */
    private static String firstJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static String stripTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= LOG_BODY_LIMIT ? s : s.substring(0, LOG_BODY_LIMIT) + "…";
    }
}
