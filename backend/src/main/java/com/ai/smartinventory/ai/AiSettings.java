package com.ai.smartinventory.ai;

/**
 * 一次 AI 调用所需的「运行时参数快照」。
 *
 * 【为什么需要这个类】
 * 原实现把 Ollama 地址、模型名、num_ctx 等全部以 @Value 形式写死在
 * LocalAIModelClient 的单例字段里 —— 单例 + 静态配置，意味着：
 *   1. 商家在界面上改了地址/模型，只有重启 JVM 才可能生效；
 *   2. 多商家无法各用各的模型（同一个单例，只有一份配置）。
 * 现把「参数」从「客户端」里抽出来：客户端无状态化（只负责发请求），
 * 参数由 AiConfigService 按当前登录商户实时解析，逐次传入。
 *
 * 本类是不可变值对象，构造后不再修改，可安全跨线程传递。
 */
public final class AiSettings {

    private final String baseUrl;
    private final String model;
    private final int numCtx;
    private final int numPredict;
    private final double temperature;
    private final int timeoutSeconds;
    private final boolean enabled;

    private AiSettings(String baseUrl, String model, int numCtx, int numPredict,
                       double temperature, int timeoutSeconds, boolean enabled) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.numCtx = numCtx;
        this.numPredict = numPredict;
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
        this.enabled = enabled;
    }

    public static AiSettings of(String baseUrl, String model, Integer numCtx, Integer numPredict,
                                Double temperature, Integer timeoutSeconds, Integer enabled) {
        return new AiSettings(
                baseUrl,
                model,
                numCtx == null || numCtx <= 0 ? 4096 : numCtx,
                numPredict == null || numPredict <= 0 ? 1024 : numPredict,
                temperature == null ? 0.7 : temperature,
                timeoutSeconds == null || timeoutSeconds <= 0 ? 120 : timeoutSeconds,
                enabled == null || enabled != 0);
    }

    public AiSettings withModel(String newModel) {
        return new AiSettings(baseUrl, newModel, numCtx, numPredict, temperature, timeoutSeconds, enabled);
    }

    /**
     * 归一化后的服务地址：去掉首尾空白与结尾多余的斜杠，
     * 避免拼出 "http://host:11434//api/chat" 这种双斜杠 URL。
     */
    public String normalizedBaseUrl() {
        String url = baseUrl == null ? "" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** 归一化后的模型名，为空时回落到 qwen3.5:0.8b */
    public String normalizedModel() {
        return model == null || model.isBlank() ? "qwen3.5:0.8b" : model.trim();
    }

    /** 地址是否形如 http(s)://host[:port] —— 保存前必须通过校验，否则存进去也是必然连不上 */
    public boolean hasValidBaseUrl() {
        String url = normalizedBaseUrl();
        return url.startsWith("http://") || url.startsWith("https://");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModel() {
        return model;
    }

    public int getNumCtx() {
        return numCtx;
    }

    public int getNumPredict() {
        return numPredict;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return "AiSettings{" + normalizedBaseUrl() + " / " + normalizedModel()
                + " / num_ctx=" + numCtx + " / enabled=" + enabled + "}";
    }
}
