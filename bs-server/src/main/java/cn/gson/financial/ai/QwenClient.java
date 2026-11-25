package cn.gson.financial.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FN-AI-T1（185）— 通义千问 Qwen 客户端，走 DashScope <b>OpenAI 兼容端点</b>
 * {@code {base-url}/chat/completions}，强制 {@code response_format={type:json_object}}。
 *
 * <p>配置（{@code application-app.yml} 的 {@code ai.qwen.*}）：key/base/model 经占位符链兼容
 * Midscene 环境变量（{@code MIDSCENE_MODEL_API_KEY/NAME/BASE_URL}）或 {@code QWEN_*}，
 * <b>key 走环境变量、不硬编码进仓库</b>。
 *
 * <p>红线：key 缺失 / 超时 / 非 JSON 一律抛 {@link QwenException}（可识别），供
 * {@code InvoiceSuggestService} 降级，不裸抛 500。失败轻重试 1 次（共 2 次尝试）。
 *
 * <p>用 JDK 内置 {@link HttpClient}（零新增依赖；可自定义 base-url，比 dashscope-sdk 的固定端点更贴合
 * Midscene 复用 base 的需求）。
 */
@Slf4j
@Component
public class QwenClient {

    /** DashScope 兼容端点基址；默认 dashscope compatible-mode，可被 MIDSCENE/QWEN base 覆盖。 */
    @Value("${ai.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    String baseUrl;

    /** 百炼 API Key；<b>禁止硬编码</b>，走环境变量（MIDSCENE_MODEL_API_KEY / QWEN_API_KEY）。空 → 降级。 */
    @Value("${ai.qwen.api-key:}")
    String apiKey;

    /** 模型名，默认 qwen-plus。 */
    @Value("${ai.qwen.model:qwen-plus}")
    String model;

    @Value("${ai.qwen.timeout-ms:20000}")
    long timeoutMs;

    @Value("${ai.qwen.temperature:0.2}")
    double temperature;

    /** 总开关；false 时直接当 key 空降级（test profile 用，杜绝真调用）。 */
    @Value("${ai.qwen.enabled:true}")
    boolean enabled;

    /** FN-AI-T3（187）视觉模型（图片/扫描件），默认 qwen-vl-plus。 */
    @Value("${ai.qwen.vision-model:qwen-vl-plus}")
    String visionModel;

    /** FN-AI-T3（187）vision 调用超时（更慢，放宽到 ~40s）。 */
    @Value("${ai.qwen.vision-timeout-ms:40000}")
    long visionTimeoutMs;

    private HttpClient httpClient;

    private HttpClient client() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.min(timeoutMs, 10000)))
                    .build();
        }
        return httpClient;
    }

    /** key 是否就绪（上层可据此提前降级、不发请求）。 */
    public boolean isReady() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /**
     * 调用 chat/completions，强制 JSON 输出，返回解析后的 {@link JSONObject}。
     *
     * @param system 系统提示词
     * @param user   用户内容（卖方上下文 + 开票信息 + 偏好）
     * @return 模型返回 content 解析后的 JSON 对象
     * @throws QwenException key 空 / 超时 / 非 2xx / 非 JSON
     */
    public JSONObject chatJson(String system, String user) {
        if (!isReady()) {
            throw new QwenException("Qwen api-key 未配置（ai.qwen.api-key / MIDSCENE_MODEL_API_KEY 为空）或已禁用");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        Map<String, Object> rf = new LinkedHashMap<>();
        rf.put("type", "json_object");
        body.put("response_format", rf);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)
        ));
        return sendWithRetry(JSON.toJSONString(body), timeoutMs);
    }

    /**
     * FN-AI-T3（187）多模态调用：vision 模型（qwen-vl-plus）读图片/扫描件，返回<b>同一 schema</b> 的 JSON。
     * user 消息 content 为数组 [{type:text}, {type:image_url, image_url:{url:"data:...;base64,"}}…]。
     * <p>不强制 {@code response_format=json_object}（部分 VL 模型不支持），靠系统提示词约束 + 兜底剥围栏解析。
     *
     * @param system        系统提示词（与文本路径同一份，不变）
     * @param userText      文本部分（卖方上下文 + 偏好 + 用户文字）
     * @param imagesBase64  图片 base64 列表（可带或不带 {@code data:} 前缀）
     */
    public JSONObject chatJsonVision(String system, String userText, List<String> imagesBase64) {
        if (!isReady()) {
            throw new QwenException("Qwen api-key 未配置（ai.qwen.api-key / MIDSCENE_MODEL_API_KEY 为空）或已禁用");
        }
        if (imagesBase64 == null || imagesBase64.isEmpty()) {
            throw new QwenException("vision 调用未带任何图片");
        }
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", userText == null ? "" : userText));
        for (String img : imagesBase64) {
            String dataUrl = toDataUrl(img);
            if (dataUrl == null) continue;
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        if (content.size() == 1) {
            throw new QwenException("vision 调用无有效图片");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", visionModel);
        body.put("temperature", temperature);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", content)
        ));
        return sendWithRetry(JSON.toJSONString(body), visionTimeoutMs);
    }

    private JSONObject sendWithRetry(String payload, long timeout) {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        QwenException last = null;
        // 首发 + 轻重试 1 次（共 2 次）
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return callOnce(url, payload, timeout);
            } catch (QwenException e) {
                last = e;
                log.warn("[FN-AI] Qwen 调用失败（第 {}/2 次）：{}", attempt, e.getMessage());
            }
        }
        throw last;
    }

    private JSONObject callOnce(String url, String payload, long timeout) {
        HttpResponse<String> resp;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            resp = client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 超时 / 网络错误 → 可识别异常
            throw new QwenException("Qwen 请求失败：" + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String snippet = resp.body() == null ? "" : resp.body().substring(0, Math.min(300, resp.body().length()));
            throw new QwenException("Qwen HTTP " + resp.statusCode() + "：" + snippet);
        }
        return extractContent(resp.body());
    }

    /** 归一图片为 data URL；已带 {@code data:} 前缀原样用，否则按 magic bytes 探测 mime 包成 data URL。 */
    static String toDataUrl(String base64OrDataUrl) {
        if (base64OrDataUrl == null) return null;
        String s = base64OrDataUrl.trim();
        if (s.isEmpty()) return null;
        if (s.startsWith("data:")) return s;
        String mime = detectImageMime(s);
        return "data:" + mime + ";base64," + s;
    }

    /** 由 base64 头部 magic bytes 粗判图片类型（PNG/JPEG/GIF/WEBP），默认 png。 */
    static String detectImageMime(String base64) {
        String b = base64;
        if (b.startsWith("/9j/")) return "image/jpeg";   // FF D8 FF
        if (b.startsWith("iVBOR")) return "image/png";    // 89 50 4E 47
        if (b.startsWith("R0lGOD")) return "image/gif";   // GIF8
        if (b.startsWith("UklGR")) return "image/webp";   // RIFF
        return "image/png";
    }

    /** 从 OpenAI 兼容响应抽取 {@code choices[0].message.content} 并解析为 JSON 对象。 */
    static JSONObject extractContent(String responseBody) {
        JSONObject root;
        try {
            root = JSON.parseObject(responseBody);
        } catch (JSONException e) {
            throw new QwenException("Qwen 响应非 JSON：" + e.getMessage(), e);
        }
        JSONArray choices = root == null ? null : root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new QwenException("Qwen 响应缺少 choices");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        if (content == null || content.isBlank()) {
            throw new QwenException("Qwen 响应 content 为空");
        }
        // response_format=json_object 下应为纯 JSON；仍兜底剥离可能的 ```json 围栏
        String cleaned = stripCodeFence(content);
        try {
            return JSON.parseObject(cleaned);
        } catch (JSONException e) {
            throw new QwenException("Qwen content 非 JSON：" + cleaned.substring(0, Math.min(200, cleaned.length())), e);
        }
    }

    static String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.trim();
    }
}
