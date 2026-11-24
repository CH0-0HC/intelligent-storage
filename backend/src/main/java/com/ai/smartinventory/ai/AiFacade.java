package com.ai.smartinventory.ai;

import com.ai.smartinventory.service.AiConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力统一门面（Facade）
 *
 * 【修复说明】原架构下存在两个互不相干的 AI 客户端：
 *   - LocalAIModelClient：连接本地 Ollama，仅在 AiController 的 chat/status 中被使用；
 *   - AiModelClient：连接阿里云 DashScope，但 application.yml 里 api.key 是占位符
 *     "your-aliyun-api-key"。
 * 而三个核心分析器（库存预警 / 销量预测 / 经营报告）注入的恰恰是后者，
 * 于是「AI 智能建议」永远是兜底话术「AI服务暂时不可用，请稍后再试。」——
 * 整条 AI 功能链实际处于不可用状态。
 *
 * 本门面把选路逻辑收敛到一处：
 *   1. 默认走本地 Ollama（本项目已部署，且不产生费用）；
 *   2. 本地不可用时，若配置了有效的云端 key，则自动降级到云端；
 *   3. 都不可用则返回明确的可读提示，方便前端区分「功能故障」与「无数据」。
 * 通过 ai.provider 配置项可强制指定 provider。
 *
 * 【2026-09-21 补充】运行时参数改由 AiConfigService 按当前登录商户解析
 * （商户可在界面自助配置地址/模型，保存即生效），不再读取静态 @Value 字段。
 * status() 同时输出前端直接消费的 connected 布尔值 —— 此前前端读的
 * status.connected 在响应里根本不存在，导致页面恒显示「AI 未连接」。
 */
@Slf4j
@Component
public class AiFacade {

    @Resource
    private LocalAIModelClient localClient;

    @Resource
    private AiModelClient cloudClient;

    @Resource
    private AiConfigService aiConfigService;

    /** 可选值：auto（默认，本地优先）/ local / cloud */
    @Value("${ai.provider:auto}")
    private String provider;

    @Value("${aliyun.api.key:}")
    private String cloudApiKey;

    /** 云端文本模型名 */
    @Value("${aliyun.api.model:qwen-turbo}")
    private String cloudModel;

    private static final String FALLBACK_TEXT = "AI服务暂时不可用，请稍后再试。";

    /**
     * 生成一段文本建议（库存预警建议、销量预测解读、经营报告、AI 助手对话等）。
     */
    public String generateText(String prompt) {
        AiSettings settings = aiConfigService.resolveCurrent();

        if (useLocal() && settings.isEnabled()) {
            String result = localClient.chat(prompt, settings);
            if (!localClient.isFallbackText(result)) {
                return result;
            }
            log.warn("本地 AI 不可用，尝试降级到云端。本地返回: {}", result);
            if (cloudUsable()) {
                return cloudClient.generateText(prompt, cloudModel);
            }
            return result;
        }

        if (cloudUsable()) {
            return cloudClient.generateText(prompt, cloudModel);
        }

        if (useLocal() && settings.isEnabled()) {
            return localClient.chat(prompt, settings);
        }
        return FALLBACK_TEXT;
    }

    /**
     * 生成结构化 JSON。本地优先走 LocalAIModelClient 的「严格解析 + 自纠重试」，
     * 云端走 AiModelClient 的原有实现。
     */
    public Map<String, Object> generateJson(String prompt, String schema) {
        AiSettings settings = aiConfigService.resolveCurrent();

        if (useLocal() && settings.isEnabled()) {
            Map<String, Object> localResult = localClient.generateJson(prompt, settings);
            if (localResult != null && !localResult.isEmpty()) {
                return localResult;
            }
            log.warn("本地 AI JSON 生成失败，尝试降级到云端");
            if (cloudUsable()) {
                return cloudClient.generateJson(prompt, schema);
            }
            return localResult == null ? Map.of() : localResult;
        }

        if (cloudUsable()) {
            return cloudClient.generateJson(prompt, schema);
        }
        if (useLocal() && settings.isEnabled()) {
            return localClient.generateJson(prompt, settings);
        }
        return Map.of();
    }

    /**
     * 当前 AI 链路的健康状态，供 /ai/status 与排障使用。
     *
     * 【字段契约】connected 是前端直接消费的布尔值，必须在响应里存在且
     * 语义稳定，不要只给 effective 让前端自行推断（历史故障点）。
     */
    public Map<String, Object> status() {
        AiSettings settings = aiConfigService.resolveCurrent();
        String scope = aiConfigService.currentScope();

        Map<String, Object> probe = localClient.probe(settings);
        boolean localOk = Boolean.TRUE.equals(probe.get("ok"));
        boolean modelOk = Boolean.TRUE.equals(probe.get("modelAvailable"));
        boolean cloudOk = cloudUsable();

        String effective;
        if (useLocal() && settings.isEnabled() && modelOk) {
            effective = "local";
        } else if (cloudOk) {
            effective = "cloud";
        } else if (useLocal() && settings.isEnabled() && localOk) {
            // 服务在但目标模型没拉下来
            effective = "local-model-missing";
        } else if (!settings.isEnabled()) {
            effective = "disabled";
        } else {
            effective = "none";
        }
        boolean connected = "local".equals(effective) || "cloud".equals(effective);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", provider);
        map.put("effective", effective);
        map.put("connected", connected);
        map.put("localConfigured", localOk);
        map.put("localModelAvailable", modelOk);
        map.put("localBaseUrl", settings.normalizedBaseUrl());
        map.put("localModel", settings.normalizedModel());
        map.put("localEnabled", settings.isEnabled());
        map.put("numCtx", settings.getNumCtx());
        map.put("configScope", scope);
        map.put("cloudConfigured", cloudOk);
        map.put("availableModels", probe.get("models"));
        map.put("message", describeEffective(effective, settings, probe));
        return map;
    }

    /** 一句话说清当前状态，直接展示在页面标签的提示里 */
    private String describeEffective(String effective, AiSettings settings, Map<String, Object> probe) {
        switch (effective) {
            case "local":
                return "本地 AI 已连接（" + settings.normalizedModel() + "）";
            case "cloud":
                return "本地 AI 不可用，已降级到云端模型 " + cloudModel;
            case "local-model-missing":
                return "已连接 " + settings.normalizedBaseUrl() + "，但未找到模型 "
                        + settings.normalizedModel() + "，请在算力服务器执行 ollama pull";
            case "disabled":
                return "本地 AI 已被手动停用，如需使用请在「AI 设置」中启用";
            default:
                Object message = probe.get("message");
                return message == null
                        ? "未检测到可用的 AI 服务，请在「AI 设置」中填写 Ollama 服务地址"
                        : message.toString();
        }
    }

    private boolean useLocal() {
        if ("cloud".equalsIgnoreCase(provider)) {
            return false;
        }
        // auto 与 local 都优先尝试本地
        return true;
    }

    /**
     * 云端是否真正可用。原配置里 key 是占位符 "your-aliyun-api-key"，
     * 必须显式识别出来，否则会拿一个必然 401 的 key 去请求，白白拖慢响应。
     */
    private boolean cloudUsable() {
        return cloudApiKey != null
                && !cloudApiKey.isBlank()
                && !cloudApiKey.startsWith("your-");
    }
}
