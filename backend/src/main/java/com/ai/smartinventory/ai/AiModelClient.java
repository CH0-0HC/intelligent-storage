package com.ai.smartinventory.ai;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Slf4j
@Component
public class AiModelClient {
    
    @Value("${aliyun.api.key:}")
    private String apiKey;
    
    @Value("${aliyun.api.secret:}")
    private String apiSecret;
    
    private static final String DASHSCOPE_API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    public String generateText(String prompt) {
        return generateText(prompt, "qwen-turbo");
    }
    
    public String generateText(String prompt, String model) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);
            requestBody.put("messages", messages);
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(DASHSCOPE_API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                Map<String, Object> responseMap = parseJson(response.body());
                List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> firstChoice = choices.get(0);
                    Map<String, Object> messageResult = (Map<String, Object>) firstChoice.get("message");
                    return (String) messageResult.get("content");
                }
            }
            
            log.error("AI API调用失败: {}, {}", response.statusCode(), response.body());
            return "AI服务暂时不可用，请稍后再试。";
            
        } catch (Exception e) {
            log.error("AI API调用异常", e);
            return "AI服务暂时不可用，请稍后再试。";
        }
    }
    
    public Map<String, Object> generateJson(String prompt, String schema) {
        try {
            String fullPrompt = prompt + "\n\n请严格按照以下JSON格式返回，不要包含其他内容：\n" + schema;
            String result = generateText(fullPrompt);
            
            String jsonStr = extractJson(result);
            return parseJson(jsonStr);
        } catch (Exception e) {
            log.error("AI JSON生成失败", e);
            return new HashMap<>();
        }
    }
    
    private String extractJson(String text) {
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
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
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(json);
            return new HashMap<>(obj);
        } catch (Exception e) {
            log.error("JSON解析失败", e);
            return new HashMap<>();
        }
    }
    
    @Data
    public static class AiResponse {
        private String content;
        private String model;
        private int tokens;
        private boolean success;
        private String error;
    }
}
