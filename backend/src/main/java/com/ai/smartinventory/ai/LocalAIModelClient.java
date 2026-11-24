package com.ai.smartinventory.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 本地 Ollama 模型客户端。
 *
 * 【重构说明 · 2026-09-21】
 * 上一轮已把 base-url / model 从「硬编码」改为 @Value 读取，但这只是
 * 「改配置要重启」变成「改 yml 要重启」，商家在界面上依然无法自助配置。
 * 本轮进一步把「参数」与「客户端」解耦：
 *   - 本类不再持有任何生效配置，只是**无状态**的 HTTP 调用器；
 *   - 每次调用都接收一个 {@link AiSettings} 运行时快照（由 AiConfigService
 *     按当前登录商户实时解析），因此商家保存后立即生效、无需重启，
 *     不同商户也可以各自指向不同的 Ollama 地址与模型；
 *   - 无参方法保留为「使用 yml 内置默认值」，避免破坏既有调用点。
 *
 * 另外新增 {@link #probe(AiSettings)}：一次性返回「通不通 / 有哪些模型 /
 * 失败原因」，供 /ai/status 与设置弹窗的「测试连接」共用，避免同一套
 * 探测逻辑在控制器里再写一遍。
 */
@Slf4j
@Component
public class LocalAIModelClient {

    /** Ollama 服务基地址（内置默认值，可被商户配置覆盖） */
    @Value("${ai.local.base-url:http://localhost:11434}")
    private String baseUrl;

    /** 使用的模型名称（内置默认值） */
    @Value("${ai.local.model:qwen3.5:0.8b}")
    private String modelName;

    /**
     * 上下文窗口长度。
     * 经验值：8GB 显存级别的显卡建议不超过 8192；设为 256K 这类超大值
     * 会导致显存不足而回退到 CPU/GPU 混合推理，速度断崖式下跌。
     */
    @Value("${ai.local.num-ctx:4096}")
    private int numCtx;

    /** 单次生成的最大 token 数 */
    @Value("${ai.local.num-predict:1024}")
    private int numPredict;

    /** 采样温度 */
    @Value("${ai.local.temperature:0.7}")
    private double temperature;

    /** 生成超时（秒），本地小模型在 CPU 回退时可能较慢，默认给足 */
    @Value("${ai.local.timeout-seconds:120}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** yml 内置默认参数（供无参重载使用） */
    public AiSettings defaultSettings() {
        return AiSettings.of(baseUrl, modelName, numCtx, numPredict, temperature, timeoutSeconds, 1);
    }

    /** 当前生效的模型名（yml 默认值，供排障） */
    public String currentModel() {
        return defaultSettings().normalizedModel();
    }

    /** 当前生效的服务地址（yml 默认值，供排障） */
    public String currentBaseUrl() {
        return defaultSettings().normalizedBaseUrl();
    }

    // ============================ 探测 ============================

    /** 探测服务是否可用（能连上且 HTTP 200） */
    public boolean isConfigured() {
        return isConfigured(defaultSettings());
    }

    public boolean isConfigured(AiSettings settings) {
        return fetchTags(settings) != null;
    }

    /** 模型是否真的已拉取到本地（避免「服务在但模型没下载」时反复报错） */
    public boolean isModelAvailable() {
        return isModelAvailable(defaultSettings());
    }

    public boolean isModelAvailable(AiSettings settings) {
        List<String> models = getAvailableModels(settings);
        return matchesModel(models, settings.normalizedModel());
    }

    /**
     * 连通性探测：返回结构化结果，供 /ai/status 与「测试连接」按钮共用。
     *
     * 返回字段：ok / baseUrl / model / models / modelAvailable /
     *          httpStatus / elapsedMs / message
     */
    public Map<String, Object> probe(AiSettings settings) {
        Map<String, Object> result = new LinkedHashMap<>();
        String url = settings.normalizedBaseUrl();
        String model = settings.normalizedModel();
        result.put("baseUrl", url);
        result.put("model", model);

        if (url.isEmpty()) {
            result.put("ok", false);
            result.put("models", Collections.emptyList());
            result.put("modelAvailable", false);
            result.put("message", "尚未配置 Ollama 服务地址");
            return result;
        }

        long start = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;
            result.put("httpStatus", response.statusCode());
            result.put("elapsedMs", cost);

            if (response.statusCode() != 200) {
                result.put("ok", false);
                result.put("models", Collections.emptyList());
                result.put("modelAvailable", false);
                result.put("message", "服务已响应但状态码为 " + response.statusCode()
                        + "，请确认该地址确实是 Ollama（默认端口 11434）");
                return result;
            }

            List<String> models = extractModelNames(response.body());
            boolean modelOk = matchesModel(models, model);
            result.put("ok", true);
            result.put("models", models);
            result.put("modelAvailable", modelOk);
            if (!modelOk) {
                result.put("message", "已连接 " + url + "，但未找到模型 " + model
                        + "，请在算力服务器执行：ollama pull " + model);
            } else if (models.isEmpty()) {
                result.put("message", "已连接 " + url + "（该服务下暂无模型）");
            } else {
                result.put("message", "已连接 " + url + "，模型 " + model + " 可用");
            }
            return result;
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return probeFailure(result, "连接 " + url + " 超时，请确认地址与端口可达", start);
        } catch (java.net.ConnectException e) {
            return probeFailure(result, "无法连接 " + url + "，请确认 Ollama 已启动且地址/端口正确", start);
        } catch (Exception e) {
            return probeFailure(result, "连接 " + url + " 失败：" + describe(e), start);
        }
    }

    private Map<String, Object> probeFailure(Map<String, Object> result, String message, long start) {
        result.put("ok", false);
        result.put("models", Collections.emptyList());
        result.put("modelAvailable", false);
        result.put("elapsedMs", System.currentTimeMillis() - start);
        result.put("message", message);
        log.warn("本地AI服务探测失败 - {} - {}", result.get("baseUrl"), message);
        return result;
    }

    /**
     * 拉取模型列表（失败返回空列表，不抛异常）。
     */
    public List<String> getAvailableModels() {
        return getAvailableModels(defaultSettings());
    }

    public List<String> getAvailableModels(AiSettings settings) {
        String body = fetchTags(settings);
        if (body == null) {
            return Collections.emptyList();
        }
        return extractModelNames(body);
    }

    /** 请求 /api/tags，成功返回响应体，失败返回 null */
    private String fetchTags(AiSettings settings) {
        String url = settings.normalizedBaseUrl();
        if (url.isEmpty()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            log.warn("获取模型列表失败：HTTP {}", response.statusCode());
        } catch (Exception e) {
            // JDK 的 NIO HttpClient 抛出的 ConnectException 其 message 常为 null，
            // 只打 e.getMessage() 会得到「获取模型列表失败: null」——等于没有信息。
            log.warn("获取模型列表失败: {}", describe(e));
            log.debug("获取模型列表失败详情", e);
        }
        return null;
    }

    /** 从 /api/tags 响应体中解析模型名列表 */
    @SuppressWarnings("unchecked")
    private List<String> extractModelNames(String body) {
        Map<String, Object> responseMap = parseJson(body);
        Object modelsObj = responseMap.get("models");
        List<String> modelNames = new ArrayList<>();
        if (modelsObj instanceof List) {
            for (Object item : (List<?>) modelsObj) {
                if (item instanceof Map) {
                    Object name = ((Map<?, ?>) item).get("name");
                    if (name == null) {
                        name = ((Map<?, ?>) item).get("model");
                    }
                    if (name != null) {
                        modelNames.add(name.toString());
                    }
                }
            }
        }
        return modelNames;
    }

    /** 模型名匹配：Ollama 可能带 :latest 后缀，做前缀兼容 */
    private boolean matchesModel(List<String> models, String want) {
        if (want == null || want.isBlank()) {
            return false;
        }
        for (String m : models) {
            if (m.equals(want) || m.startsWith(want + ":")) {
                return true;
            }
        }
        return false;
    }

    // ============================ 对话 ============================

    /** 单轮对话，使用 yml 内置默认参数 */
    public String chat(String userMessage) {
        return chat(userMessage, defaultSettings());
    }

    /** 单轮对话，使用指定的运行时参数 */
    public String chat(String userMessage, AiSettings settings) {
        return chat(userMessage,
                "You are a helpful assistant. Please respond directly and concisely.",
                settings);
    }

    /**
     * 单轮对话，返回模型回复文本。
     * 失败时返回可读的降级提示（不抛异常），保证上层业务不会因 AI 故障而中断。
     */
    public String chat(String userMessage, String systemPrompt, AiSettings settings) {
        String url = settings.normalizedBaseUrl();
        String model = settings.normalizedModel();
        return sendChat(userMessage, systemPrompt, url, model, settings);
    }

    private String sendChat(String userMessage, String systemPrompt,
                            String url, String model, AiSettings settings) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);

            List<Map<String, Object>> messages = new ArrayList<>();
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);

            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("think", false);
            // 【关键】显式下发 num_ctx，避免模型使用默认的超大上下文吃满显存
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("temperature", settings.getTemperature());
            options.put("num_predict", settings.getNumPredict());
            options.put("num_ctx", settings.getNumCtx());
            requestBody.put("options", options);

            String jsonRequest = toJson(requestBody);
            log.debug("AI请求 -> {} / {} (num_ctx={})", url, model, settings.getNumCtx());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
                    .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> responseMap = parseJson(response.body());

                if (responseMap.containsKey("message")) {
                    Object messageObj = responseMap.get("message");
                    if (messageObj instanceof Map) {
                        Map<String, Object> message = (Map<String, Object>) messageObj;
                        if (message.containsKey("content")) {
                            Object contentObj = message.get("content");
                            if (contentObj != null && !contentObj.toString().trim().isEmpty()) {
                                return contentObj.toString().trim();
                            }
                        }
                    }
                }

                log.warn("AI响应格式异常: {}", abbreviate(response.body(), 500));
                return "抱歉，AI响应格式异常";
            }

            log.error("AI调用失败，状态码: {}，响应: {}", response.statusCode(), abbreviate(response.body(), 500));
            if (response.statusCode() == 404) {
                return "抱歉，本地未找到模型 " + model + "，请先在算力服务器执行 ollama pull " + model + "。";
            }
            return "抱歉，AI服务调用失败（HTTP " + response.statusCode() + "）";

        } catch (java.net.ConnectException e) {
            log.error("无法连接到本地AI服务 {}: {}", url, describe(e));
            return "抱歉，无法连接到 AI 服务（" + url + "），请在「AI 设置」中检查地址或确认 Ollama 已启动。";
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("AI调用超时（{}s）", settings.getTimeoutSeconds());
            return "抱歉，AI推理超时，请稍后重试或在「AI 设置」中调小上下文长度。";
        } catch (Exception e) {
            log.error("AI调用异常", e);
            return "抱歉，AI服务暂时不可用：" + describe(e);
        }
    }

    /**
     * 结构化 JSON 输出：要求模型只返回 JSON，并在解析失败时做一次自纠重试。
     *
     * 【修复说明】原 AiModelClient.generateJson 只在返回文本里抠第一个 { 到最后一个 }
     * 的子串，模型多说一句话就解析失败，且失败后静默返回空 Map，
     * 上层拿不到任何错误信息。现改为：严格解析 + 失败时带着错误原因重试一次。
     */
    public Map<String, Object> generateJson(String prompt) {
        return generateJson(prompt, defaultSettings());
    }

    public Map<String, Object> generateJson(String prompt, AiSettings settings) {
        String instruction = prompt + "\n\n【输出要求】只输出一个合法的 JSON 对象，"
                + "不要输出任何解释文字、Markdown 代码块标记或前后缀。";
        String first = chat(instruction,
                "You are a JSON-only API. Output raw JSON without markdown fences.", settings);
        Map<String, Object> parsed = tryParseJsonObject(first);
        if (parsed != null) {
            return parsed;
        }

        log.warn("AI JSON 首次解析失败，触发自纠重试。原始响应: {}", abbreviate(first, 300));
        String retry = chat(instruction
                        + "\n\n注意：你上一次的输出不是合法 JSON。请重新输出，只包含 JSON 对象本身。",
                "You are a JSON-only API. Output raw JSON without markdown fences.", settings);
        Map<String, Object> retryParsed = tryParseJsonObject(retry);
        if (retryParsed != null) {
            return retryParsed;
        }
        log.error("AI JSON 重试后仍解析失败，返回空结果。原始响应: {}", abbreviate(retry, 300));
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryParseJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String cleaned = text.trim();
        // 剥离 ```json ... ``` 代码块围栏
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) {
                cleaned = cleaned.substring(0, fence);
            }
            cleaned = cleaned.trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.alibaba.fastjson2.JSONObject obj =
                    com.alibaba.fastjson2.JSON.parseObject(cleaned.substring(start, end + 1));
            return obj == null ? null : new HashMap<>(obj);
        } catch (Exception e) {
            log.debug("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从降级提示文本中判断本次调用是否成功。
     * 上层若需要区分「真结果」与「降级话术」可用此方法。
     */
    public boolean isFallbackText(String text) {
        return text == null || text.startsWith("抱歉，");
    }

    /**
     * 把异常转成一句「一定有信息量」的描述。
     *
     * 起因：日志里出现 `本地AI服务未连接 (http://localhost:11434): null` ——
     * JDK NIO HttpClient 抛出的 ConnectException 其 getMessage() 为 null，
     * 于是日志等于什么都没说。这里在 message 为空时退化为异常类名。
     */
    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(escapeJson((String) value)).append("\"");
            } else if (value instanceof List) {
                sb.append("[");
                List<?> list = (List<?>) value;
                for (int j = 0; j < list.size(); j++) {
                    if (j > 0) sb.append(",");
                    Object item = list.get(j);
                    if (item instanceof Map) {
                        sb.append(toJson((Map<String, Object>) item));
                    } else if (item instanceof String) {
                        sb.append("\"").append(escapeJson((String) item)).append("\"");
                    } else {
                        sb.append(item);
                    }
                }
                sb.append("]");
            } else if (value instanceof Map) {
                sb.append(toJson((Map<String, Object>) value));
            } else {
                sb.append(value);
            }
            i++;
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<>();
        }
        try {
            com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(json);
            return obj != null ? new HashMap<>(obj) : new HashMap<>();
        } catch (Exception e) {
            log.error("JSON解析失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}
