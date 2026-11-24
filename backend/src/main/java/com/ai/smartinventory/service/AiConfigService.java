package com.ai.smartinventory.service;

import com.ai.smartinventory.ai.AiSettings;
import com.ai.smartinventory.dto.AiConfigDTO;

import java.util.List;
import java.util.Map;

/**
 * AI 连接配置服务 —— 商家自助配置 Ollama 地址 / 模型等参数。
 *
 * 配置解析优先级（从高到低）：
 *   1. 当前登录商户自己的 ai_config 行（merchant_id = 该商家的用户 ID）；
 *   2. 全局默认行（merchant_id = 0，管理员维护）；
 *   3. application.yml 中的 ai.local.* 内置默认值。
 *
 * 保存后立即生效（下次 AI 调用即读取新值），无需重启后端 ——
 * 这是本服务相对「@Value 静态字段」方案的核心价值。
 */
public interface AiConfigService {

    /** 全局默认配置行的 merchant_id 取值（管理员维护的那一行） */
    long GLOBAL_MERCHANT_ID = 0L;

    /** application.yml 中的内置默认值 */
    AiSettings resolveDefaults();

    /** 当前请求上下文下真正生效的配置（商户行 → 全局行 → 内置默认） */
    AiSettings resolveCurrent();

    /** 当前配置的来源描述：merchant / global / default */
    String currentScope();

    /**
     * 给前端设置弹窗用的完整视图：当前生效值 + 内置默认值 + 来源 +
     * 可选模型列表 + 连通性探测结果。
     */
    Map<String, Object> describeCurrent();

    /** 保存（新增或更新）当前账号的配置，返回保存后的视图 */
    Map<String, Object> saveCurrent(AiConfigDTO dto);

    /** 清除当前账号的配置，回落到上一层。返回清除后的视图 */
    Map<String, Object> resetCurrent();

    /**
     * 用「尚未保存」的参数探测连通性，供设置弹窗的「测试连接」按钮使用。
     *
     * @param baseUrl  待测地址，为空则用当前生效地址
     * @param model    待测模型，为空则不校验模型是否存在
     */
    Map<String, Object> testConnection(String baseUrl, String model);

    /** 拉取指定地址下的模型列表（供模型下拉框），地址为空则用当前生效地址 */
    List<String> listModels(String baseUrl);

    /** 当前账号是否有权维护配置（仅 admin / merchant） */
    void assertCanManageConfig();
}
