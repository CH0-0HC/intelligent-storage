package com.ai.smartinventory.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 连接配置实体（对应 ai_config 表）。
 *
 * 设计要点：
 * 1. merchantId = 0 的行表示「全局默认配置」，由管理员维护；
 *    商家（merchantId = 自己的用户 ID）保存的行是「商户级覆盖」。
 *    解析顺序：商户行 → 全局行 → application.yml 内置默认值。
 * 2. 本表**不使用逻辑删除**：merchant_id 上有唯一索引，
 *    逻辑删除会让「删除后再插入同 merchant_id」触发唯一键冲突。
 *    「恢复默认」实现为物理删除该行，回落到上层配置。
 * 3. AiSettings / AiRuntimeConfig 负责承载运行时快照，本实体只负责持久化。
 */
@Data
@TableName("ai_config")
public class AiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商家 ID；0 表示全局默认配置 */
    private Long merchantId;

    /** Ollama 服务地址，如 http://localhost:11434 */
    private String baseUrl;

    /** 模型名称，如 qwen3.5:0.8b */
    private String model;

    /** 上下文窗口长度 */
    private Integer numCtx;

    /** 单次生成最大 token 数 */
    private Integer numPredict;

    /** 采样温度 */
    private Double temperature;

    /** 单次请求超时（秒），CPU 回退时需给足 */
    private Integer timeoutSeconds;

    /** 是否启用本地 AI：1-启用, 0-停用（停用后走云端或返回降级提示） */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
