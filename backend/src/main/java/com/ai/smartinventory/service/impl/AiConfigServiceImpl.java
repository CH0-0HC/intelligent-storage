package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.ai.AiSettings;
import com.ai.smartinventory.ai.LocalAIModelClient;
import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.dto.AiConfigDTO;
import com.ai.smartinventory.entity.AiConfig;
import com.ai.smartinventory.mapper.AiConfigMapper;
import com.ai.smartinventory.service.AiConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 配置服务实现。
 *
 * 【本次修复的三类问题与之的关系】
 * 1. 「页面始终显示 AI 未连接」：根因是 /ai/status 的返回结构里没有前端
 *    读取的 connected 字段（前端读 status.connected → undefined → 恒 false）。
 *    本服务提供 describeCurrent()，同时暴露 effective / connected，
 *    让「配置视图」与「健康状态」在同一份响应里，前端无需两处推断。
 * 2. 「缺少配置入口」：本服务提供读取/保存/重置/测试四类能力。
 * 3. 「输入框被禁用」：输入框原本 disabled 绑定 status.connected，
 *    状态修好后自然可用；同时前端不再用 connected 阻断输入。
 */
@Slf4j
@Service
public class AiConfigServiceImpl implements AiConfigService {

    @Resource
    private AiConfigMapper aiConfigMapper;

    @Resource
    private LocalAIModelClient localClient;

    /** application.yml 内置默认值（ai.local.*），作为最后一层兜底 */
    @Value("${ai.local.base-url:http://localhost:11434}")
    private String defaultBaseUrl;

    @Value("${ai.local.model:qwen3.5:0.8b}")
    private String defaultModel;

    @Value("${ai.local.num-ctx:4096}")
    private Integer defaultNumCtx;

    @Value("${ai.local.num-predict:1024}")
    private Integer defaultNumPredict;

    @Value("${ai.local.temperature:0.7}")
    private Double defaultTemperature;

    @Value("${ai.local.timeout-seconds:120}")
    private Integer defaultTimeoutSeconds;

    @Override
    public AiSettings resolveDefaults() {
        return AiSettings.of(defaultBaseUrl, defaultModel, defaultNumCtx, defaultNumPredict,
                defaultTemperature, defaultTimeoutSeconds, 1);
    }

    @Override
    public AiSettings resolveCurrent() {
        // 1) 商户自有配置
        Long key = currentConfigKey();
        if (key != null) {
            AiConfig own = findByMerchantId(key);
            if (isUsableRow(own)) {
                return toSettings(own);
            }
            // 商户行存在但被显式停用（enabled=0）：尊重其选择，不再回落，
            // 否则「我停用了本地 AI」会被系统默默改回启用。
            if (own != null && own.getEnabled() != null && own.getEnabled() == 0) {
                return toSettings(own);
            }
            // 2) 全局默认行（仅当商户没有自己的配置时）
            if (key != GLOBAL_MERCHANT_ID) {
                AiConfig global = findByMerchantId(GLOBAL_MERCHANT_ID);
                if (isUsableRow(global)) {
                    return toSettings(global);
                }
            }
        }
        // 3) 内置默认值
        return resolveDefaults();
    }

    @Override
    public String currentScope() {
        Long key = currentConfigKey();
        if (key != null) {
            AiConfig own = findByMerchantId(key);
            if (isUsableRow(own) || (own != null && isDisabled(own))) {
                return "merchant";
            }
            if (key != GLOBAL_MERCHANT_ID && isUsableRow(findByMerchantId(GLOBAL_MERCHANT_ID))) {
                return "global";
            }
        }
        return "default";
    }

    @Override
    public Map<String, Object> describeCurrent() {
        AiSettings settings = resolveCurrent();
        String scope = currentScope();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseUrl", settings.normalizedBaseUrl());
        result.put("model", settings.normalizedModel());
        result.put("numCtx", settings.getNumCtx());
        result.put("numPredict", settings.getNumPredict());
        result.put("temperature", settings.getTemperature());
        result.put("timeoutSeconds", settings.getTimeoutSeconds());
        result.put("enabled", settings.isEnabled() ? 1 : 0);
        result.put("scope", scope);
        result.put("scopeLabel", scopeLabel(scope));
        // customized=true 表示当前账号自己存过一份配置（可「恢复默认」）
        result.put("customized", "merchant".equals(scope) && currentConfigKey() != null
                && findByMerchantId(currentConfigKey()) != null);

        AiSettings defaults = resolveDefaults();
        Map<String, Object> defaultMap = new LinkedHashMap<>();
        defaultMap.put("baseUrl", defaults.normalizedBaseUrl());
        defaultMap.put("model", defaults.normalizedModel());
        defaultMap.put("numCtx", defaults.getNumCtx());
        defaultMap.put("numPredict", defaults.getNumPredict());
        defaultMap.put("temperature", defaults.getTemperature());
        defaultMap.put("timeoutSeconds", defaults.getTimeoutSeconds());
        result.put("defaults", defaultMap);

        // 连通性探测：把「保存后到底通不通」直接摆在前端，省去猜
        Map<String, Object> probe = localClient.probe(settings);
        result.put("connected", Boolean.TRUE.equals(probe.get("ok")));
        result.put("availableModels", probe.get("models"));
        result.put("message", probe.get("message"));
        return result;
    }

    @Override
    public Map<String, Object> saveCurrent(AiConfigDTO dto) {
        assertCanManageConfig();
        Long key = requireConfigKey();
        if (dto == null) {
            throw new BusinessException(400, "配置内容不能为空");
        }

        // —— 校验：宁可在这里拦住，也不要让一个必然连不上的地址落库 ——
        String baseUrl = dto.getBaseUrl() == null ? null : dto.getBaseUrl().trim();
        if (baseUrl == null || baseUrl.isEmpty()) {
            throw new BusinessException(400, "请填写 Ollama 服务地址");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BusinessException(400, "服务地址必须以 http:// 或 https:// 开头");
        }
        if (baseUrl.length() > 255) {
            throw new BusinessException(400, "服务地址过长（最多 255 字符）");
        }

        String model = dto.getModel() == null ? null : dto.getModel().trim();
        if (model == null || model.isEmpty()) {
            throw new BusinessException(400, "请填写或选择模型名称");
        }
        if (model.length() > 100) {
            throw new BusinessException(400, "模型名称过长（最多 100 字符）");
        }

        Integer numCtx = dto.getNumCtx();
        if (numCtx != null && (numCtx < 512 || numCtx > 131072)) {
            throw new BusinessException(400, "上下文长度需在 512 ~ 131072 之间（8G 显存建议 4096~8192）");
        }
        Integer numPredict = dto.getNumPredict();
        if (numPredict != null && (numPredict < 64 || numPredict > 8192)) {
            throw new BusinessException(400, "最大生成长度需在 64 ~ 8192 之间");
        }
        Double temperature = dto.getTemperature();
        if (temperature != null && (temperature < 0 || temperature > 2)) {
            throw new BusinessException(400, "温度需在 0 ~ 2 之间");
        }
        Integer timeoutSeconds = dto.getTimeoutSeconds();
        if (timeoutSeconds != null && (timeoutSeconds < 5 || timeoutSeconds > 600)) {
            throw new BusinessException(400, "超时时间需在 5 ~ 600 秒之间");
        }

        AiConfig existing = findByMerchantId(key);
        AiSettings current = resolveCurrent();
        AiConfig row = existing == null ? new AiConfig() : existing;
        row.setMerchantId(key);
        row.setBaseUrl(baseUrl);
        row.setModel(model);
        row.setNumCtx(numCtx == null ? current.getNumCtx() : numCtx);
        row.setNumPredict(numPredict == null ? current.getNumPredict() : numPredict);
        row.setTemperature(temperature == null ? current.getTemperature() : temperature);
        row.setTimeoutSeconds(timeoutSeconds == null ? current.getTimeoutSeconds() : timeoutSeconds);
        row.setEnabled(dto.getEnabled() == null ? 1 : (dto.getEnabled() == 0 ? 0 : 1));

        if (existing == null) {
            aiConfigMapper.insert(row);
            log.info("AI 配置已新增 - merchantId={}, url={}, model={}", key, baseUrl, model);
        } else {
            aiConfigMapper.updateById(row);
            log.info("AI 配置已更新 - merchantId={}, url={}, model={}", key, baseUrl, model);
        }
        return describeCurrent();
    }

    @Override
    public Map<String, Object> resetCurrent() {
        assertCanManageConfig();
        Long key = requireConfigKey();
        AiConfig existing = findByMerchantId(key);
        if (existing != null) {
            aiConfigMapper.deleteById(existing.getId());
            log.info("AI 配置已清除，回落到上一层 - merchantId={}", key);
        }
        return describeCurrent();
    }

    @Override
    public Map<String, Object> testConnection(String baseUrl, String model) {
        assertCanManageConfig();
        AiSettings current = resolveCurrent();
        AiSettings probeSettings = current;
        if (baseUrl != null && !baseUrl.isBlank()) {
            if (!baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://")) {
                throw new BusinessException(400, "服务地址必须以 http:// 或 https:// 开头");
            }
            probeSettings = AiSettings.of(baseUrl, current.getModel(), current.getNumCtx(),
                    current.getNumPredict(), current.getTemperature(), current.getTimeoutSeconds(), 1);
        }
        if (model != null && !model.isBlank()) {
            probeSettings = probeSettings.withModel(model.trim());
        }
        return localClient.probe(probeSettings);
    }

    @Override
    public List<String> listModels(String baseUrl) {
        assertCanManageConfig();
        AiSettings settings;
        if (baseUrl == null || baseUrl.isBlank()) {
            settings = resolveCurrent();
        } else {
            AiSettings current = resolveCurrent();
            settings = AiSettings.of(baseUrl, current.getModel(), current.getNumCtx(),
                    current.getNumPredict(), current.getTemperature(), current.getTimeoutSeconds(), 1);
        }
        return localClient.getAvailableModels(settings);
    }

    @Override
    public void assertCanManageConfig() {
        String role = MerchantContext.currentRole();
        if (!MerchantContext.ROLE_MERCHANT.equals(role) && !MerchantContext.ROLE_ADMIN.equals(role)) {
            throw new BusinessException(403, "仅商家或管理员可配置 AI 连接");
        }
    }

    // ================= 私有辅助 =================

    /**
     * 当前请求对应的配置行主键：
     *   商家 → 自己的 userId；管理员 → 全局行(0)；其余（未登录 / 普通用户）→ null。
     */
    private Long currentConfigKey() {
        String role = MerchantContext.currentRole();
        if (MerchantContext.ROLE_MERCHANT.equals(role)) {
            return MerchantContext.currentMerchantId();
        }
        if (MerchantContext.ROLE_ADMIN.equals(role)) {
            return GLOBAL_MERCHANT_ID;
        }
        return null;
    }

    /** 写操作专用：拿不到主键说明当前身份不允许写，直接拒绝而不是静默写全局行 */
    private Long requireConfigKey() {
        Long key = currentConfigKey();
        if (key == null) {
            throw new BusinessException(403, "当前账号未绑定商户，无法保存 AI 配置");
        }
        return key;
    }

    private AiConfig findByMerchantId(Long merchantId) {
        if (merchantId == null) {
            return null;
        }
        return aiConfigMapper.selectOne(new LambdaQueryWrapper<AiConfig>()
                .eq(AiConfig::getMerchantId, merchantId)
                .last("limit 1"));
    }

    private boolean isDisabled(AiConfig row) {
        return row != null && row.getEnabled() != null && row.getEnabled() == 0;
    }

    /**
     * 判断一行配置是否「可直接使用」：启用 + 地址合法 + 模型非空。
     * 任何一项不满足都视为无效，交给下一层兜底 ——
     * 这样即使有人在库里手写了一条脏数据，系统也不会整体不可用。
     */
    private boolean isUsableRow(AiConfig row) {
        if (row == null || isDisabled(row)) {
            return false;
        }
        String url = row.getBaseUrl();
        String model = row.getModel();
        return url != null && !url.isBlank()
                && (url.trim().startsWith("http://") || url.trim().startsWith("https://"))
                && model != null && !model.isBlank();
    }

    private AiSettings toSettings(AiConfig row) {
        return AiSettings.of(row.getBaseUrl(), row.getModel(), row.getNumCtx(), row.getNumPredict(),
                row.getTemperature(), row.getTimeoutSeconds(), row.getEnabled());
    }

    private String scopeLabel(String scope) {
        switch (scope) {
            case "merchant":
                return "商户自定义";
            case "global":
                return "全局默认配置";
            default:
                return "系统内置默认";
        }
    }
}
