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
import java.util.ArrayList;
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
    /** 单次请求的 max_tokens 来自 llm.max-tokens；这里是"被截断后自动重试"的天花板 */
    private static final int MAX_TOKEN_CEILING = 16000;
    private static final int PING_MAX_TOKENS = 8;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int LOG_BODY_LIMIT = 300;

    private final LlmProperties props;
    private final ApiKeyProvider apiKeys;
    private final HttpClient http;
    /** null 表示 prompt 模板缺失，此时抽取不可用但不影响应用启动 */
    private final String prompt;
    /**
     * 该端点是否拒绝 thinking 参数。一旦收到一次 400 就置 true，后续请求不再带，
     * 避免每份材料都白撞一次 400。
     */
    private volatile boolean thinkingRejected = false;

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
            log.info("抽取器就绪：{}，Key 来源={}，prompt {} 字符，思考模式={}",
                    props.describe(), apiKeys.source(), prompt.length(),
                    props.thinkingOff() ? "关（抽取不需要推理）" : "开");
        }
    }

    @Override
    public ExtractionResult extract(byte[] image, String mimeType) {
        LlmProfile profile = requireProfile();

        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image);

        // 额度不够时自动翻倍（3000→6000→12000），用户不需要知道 max_tokens 是什么。
        // 关掉思考后单份正文只要 200 token 左右，正常一次就过，翻倍只是兜底。
        int budget = props.maxTokens();
        int attempt = 1;
        int rateLimited = 0;
        while (true) {
            String requestBody = toJson(payload(dataUrl, prompt, profile.model(), budget));

            long startedAt = System.currentTimeMillis();
            HttpResponse<String> response = postJson(requestBody, profile);
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (response.statusCode() != 200) {
                // 并发识别时可能撞上上游限流：等 3 秒重试一次，别让用户看到"请求过于频繁"
                // 就以为整份材料废了。只重试一次，避免把限流放大成雪崩。
                if (response.statusCode() == 429 && rateLimited < 1) {
                    rateLimited++;
                    log.info("上游限流，3 秒后重试一次");
                    sleepQuietly(3000);
                    continue;
                }
                // 该端点不认识 thinking 字段（非 DeepSeek 或老网关）：摘掉重发一次，结果不受影响。
                // 只做一次，且不消耗截断重试的次数。
                if (response.statusCode() == 400 && thinkingOn() && !thinkingRejected) {
                    thinkingRejected = true;
                    log.info("该端点不接受 thinking 参数，已自动去掉后重试（不影响抽取结果）");
                    continue;
                }
                throw classify(response.statusCode(), response.body());
            }
            try {
                ExtractionResult result = parse(response.body(), latencyMs, profile.model());
                log.info("抽取完成 model={} {}ms tokens={} docType={} confidence={}",
                        profile.model(), latencyMs, result.totalTokens(),
                        result.fact().docType(), result.fact().confidence());
                return result;
            } catch (TruncatedOutput e) {
                int next = Math.min(budget * 2, MAX_TOKEN_CEILING);
                if (attempt >= 3 || next == budget) {
                    throw e.toBusinessException();
                }
                log.warn("模型输出被 max_tokens={} 截断，自动放大到 {} 重试", budget, next);
                budget = next;
                attempt++;
            }
        }
    }

    /**
     * 用指定 prompt 抽一张图，返回原始文本。
     *
     * <p>重试/降级规则与 {@link #extract} 完全一致（限流一次、thinking 400 降级、
     * 输出被截断则翻倍预算），保证两条链路的行为不会因为一处改了而分叉。
     */
    @Override
    public RawExtraction extractRaw(byte[] image, String mimeType, String prompt, int maxTokens) {
        LlmProfile profile = requireProfile();
        String dataUrl = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image);

        int budget = maxTokens <= 0 ? props.maxTokens() : maxTokens;
        int attempt = 1;
        int rateLimited = 0;
        while (true) {
            String requestBody = toJson(payload(dataUrl, prompt, profile.model(), budget));

            long startedAt = System.currentTimeMillis();
            HttpResponse<String> response = postJson(requestBody, profile);
            long latencyMs = System.currentTimeMillis() - startedAt;

            if (response.statusCode() != 200) {
                if (response.statusCode() == 429 && rateLimited < 1) {
                    rateLimited++;
                    log.info("上游限流，3 秒后重试一次");
                    sleepQuietly(3000);
                    continue;
                }
                if (response.statusCode() == 400 && thinkingOn() && !thinkingRejected) {
                    thinkingRejected = true;
                    log.info("该端点不接受 thinking 参数，已自动去掉后重试（不影响结果）");
                    continue;
                }
                throw classify(response.statusCode(), response.body());
            }
            try {
                Content c = readContent(response.body());
                return new RawExtraction(c.text(), profile.model(), latencyMs, c.totalTokens());
            } catch (TruncatedOutput e) {
                int next = Math.min(budget * 2, MAX_TOKEN_CEILING);
                if (attempt >= 3 || next == budget) {
                    throw e.toBusinessException();
                }
                log.warn("模型输出被 max_tokens={} 截断，自动放大到 {} 重试", budget, next);
                budget = next;
                attempt++;
            }
        }
    }

    /** 是否真的要发 thinking 参数（用户开了、且这个端点还没拒绝过） */
    private boolean thinkingOn() {
        return props.thinkingOff() && !thinkingRejected;
    }

    /** 重试等待，被中断就老实交回中断标记（吞掉中断会让关应用卡住） */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("调用模型被中断", 504);
        }
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
        // 不关思考的话，ping 的 8 个 token 会全被思维链吃掉，连通性测试要白等十几秒
        if (thinkingOn()) {
            body.put("thinking", Map.of("type", "disabled"));
        }
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
        Content c = readContent(responseBody);

        ExtractedFact fact;
        try {
            fact = MAPPER.readValue(firstJsonObject(c.text()), ExtractedFact.class);
        } catch (JsonProcessingException e) {
            log.warn("模型输出无法解析为 JSON（finish_reason={}），前 {} 字：{}",
                    c.truncated() ? "length" : "(空)", LOG_BODY_LIMIT, brief(c.text()));
            if (c.truncated()) {
                throw new TruncatedOutput("length");
            }
            throw new BusinessException("模型输出不是合法 JSON，可重试一次；若持续失败请换模型或调整 prompt", 502);
        }
        return new ExtractionResult(fact, model, latencyMs, c.totalTokens());
    }

    /** 一段模型返回的正文 + 本次用量 */
    private record Content(String text, Integer totalTokens, boolean truncated) {
    }

    /**
     * 从响应体里取正文。空内容和被截断都在这里分类，避免每个调用方各判断一次。
     *
     * <p>抽出来是因为规则导入（{@link #extractRaw}）和材料抽取走的是同一套响应格式，
     * 两处各写一遍迟早会分叉——比如一处记得判 finish_reason=length，另一处忘了。
     */
    private Content readContent(String responseBody) {
        JsonNode root;
        try {
            root = MAPPER.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new BusinessException("模型返回的不是 JSON，无法解析", 502);
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finishReason = choice.path("finish_reason").asText("");
        boolean truncated = "length".equalsIgnoreCase(finishReason);
        String text = textOf(message.path("content"));

        if (text.isBlank()) {
            // 抽不出东西时唯一的现场记录：必须留下 finish_reason 和原始响应，
            // 否则只剩一句"没有返回内容"，分不清是被截断、被审核、还是模型读不了图。
            log.warn("模型返回空内容 finish_reason={} message字段={} 原始响应={}",
                    finishReason.isEmpty() ? "(空)" : finishReason, fieldNames(message), brief(responseBody, 800));
            if (truncated) {
                throw new TruncatedOutput(finishReason);
            }
            throw new BusinessException(emptyContentHint(finishReason), 502);
        }

        // 诊断用：thinking 没被关掉时会有 reasoning_tokens，那是纯粹的浪费
        JsonNode reasoning = root.path("usage").path("completion_tokens_details").path("reasoning_tokens");
        if (reasoning.isNumber() && reasoning.asInt() > 0) {
            log.warn("本次调用仍消耗了 {} 个思考 token（thinking 未生效，可能是端点忽略了该参数）",
                    reasoning.asInt());
        }

        JsonNode usage = root.path("usage").path("total_tokens");
        return new Content(text, usage.isNumber() ? usage.asInt() : null, truncated);
    }

    private Map<String, Object> payload(String dataUrl, String userText, String model, int maxTokens) {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", userText);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", dataUrl));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("max_tokens", maxTokens);
        // 抽取是「看字抄字」，不需要推理。开思考会先把预算烧在思维链上，
        // 表现为 content 为空 + finish_reason=length，然后代码翻倍重试——单份成本直接翻倍。
        if (thinkingOn()) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        body.put("messages", List.of(message));
        return body;
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BusinessException("模型返回的不是 JSON，无法解析", 502);
        }
    }

    /** 输出被 max_tokens 截断：不是用户的错，调用方会自动放大预算重试一次 */
    private static final class TruncatedOutput extends RuntimeException {

        TruncatedOutput(String finishReason) {
            super(finishReason);
        }

        BusinessException toBusinessException() {
            return new BusinessException("模型输出被 max_tokens 截断（推理模型会先把 token 花在思考上），"
                    + "已经自动加大预算重试仍不够。建议换用非推理模型，或换一张更小更清晰的证书图", 502);
        }
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
        return brief(s, LOG_BODY_LIMIT);
    }

    private static String brief(String s, int limit) {
        if (s == null) {
            return "";
        }
        return s.length() <= limit ? s : s.substring(0, limit) + "…";
    }

    /** 取文本：兼容字符串、content 数组、缺失节点三种形态 */
    private static String textOf(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : node) {
                String t = part.path("text").asText("");
                if (!t.isBlank()) {
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return node.asText("");
    }

    /** message 里到底有哪些字段，用于判断"模型是不是把内容放别处了" */
    private static String fieldNames(JsonNode message) {
        if (message == null || !message.isObject()) {
            return "[]";
        }
        List<String> names = new ArrayList<>();
        message.fieldNames().forEachRemaining(names::add);
        return names.toString();
    }

    /** 把"内容为空"翻译成能指导用户下一步动作的话 */
    private static String emptyContentHint(String finishReason) {
        if ("length".equalsIgnoreCase(finishReason)) {
            return "模型的输出被 max_tokens 截断了（推理类模型会先消耗在思考上），"
                    + "可以在配置里把 max_tokens 调大，或换一个非推理模型";
        }
        if ("content_filter".equalsIgnoreCase(finishReason)) {
            return "内容被模型侧的安全策略拦截了，换一张更清晰的证书图或换模型试试";
        }
        return "模型没有返回内容。已把原始响应写进日志（%LOCALAPPDATA%\\Assessment\\logs\\app.log），"
                + "常见原因：这个模型不支持图像输入，或图片太大/太模糊";
    }
}
