package com.ai.smartinventory.dto;

import lombok.Data;

/**
 * AI 连接配置的提交载体（前端设置弹窗 → 后端保存接口）。
 * 所有字段都可空：空值表示「沿用上一层配置或内置默认」，不做强制覆盖。
 */
@Data
public class AiConfigDTO {

    /** Ollama 服务地址，如 http://localhost:11434 */
    private String baseUrl;

    /** 模型名称，如 qwen3.5:0.8b */
    private String model;

    /** 上下文窗口长度；过大（如 256K）会超出显存导致 CPU 混合推理 */
    private Integer numCtx;

    /** 单次生成最大 token 数 */
    private Integer numPredict;

    /** 采样温度 */
    private Double temperature;

    /** 请求超时（秒） */
    private Integer timeoutSeconds;

    /** 是否启用本地 AI */
    private Integer enabled;
}
