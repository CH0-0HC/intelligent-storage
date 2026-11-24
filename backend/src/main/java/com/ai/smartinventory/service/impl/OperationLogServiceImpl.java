package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.entity.OperationLog;
import com.ai.smartinventory.mapper.OperationLogMapper;
import com.ai.smartinventory.service.OperationLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作日志服务实现
 */
@Slf4j
@Service
public class OperationLogServiceImpl extends ServiceImpl<OperationLogMapper, OperationLog>
        implements OperationLogService {

    @Override
    public PageResult<OperationLog> getLogPage(Integer page, Integer size, String username) {
        // 【P1-3】统一夹取页码与 size 上限
        Page<OperationLog> pageParam = new Page<>(PageBounds.page(page), PageBounds.size(size));
        LambdaQueryWrapper<OperationLog> wrapper = new LambdaQueryWrapper<>();

        if (username != null && !username.isEmpty()) {
            wrapper.like(OperationLog::getUsername, username);
        }

        wrapper.orderByDesc(OperationLog::getCreateTime);
        Page<OperationLog> result = this.page(pageParam, wrapper);
        return new PageResult<>(result.getTotal(), result.getRecords(),
                result.getCurrent(), result.getSize());
    }

    @Override
    public void recordLog(Long userId, String username, String operation, String method,
                          String params, String ip, Integer status, Long executeTime, String errorMsg) {
        // 旧签名：委托到扩展版，result / userAgent 留空（保持既有调用点行为不变）
        recordLog(userId, username, operation, method, params, ip, status, executeTime,
                errorMsg, null, null);
    }

    @Override
    public void recordLog(Long userId, String username, String operation, String method,
                          String params, String ip, Integer status, Long executeTime, String errorMsg,
                          String result, String userAgent) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setOperation(operation);
        log.setMethod(method);
        log.setParams(params);
        // 【P1-5】补上此前从未写入的两列：
        // result —— 业务返回体摘要（如 {"code":200,"message":"操作成功"}），
        //           用于事后确认「这次请求到底成功没有、返回了什么」；
        // userAgent —— 请求方客户端标识，排查「是谁/什么端发起」时必需。
        log.setResult(result);
        log.setUserAgent(userAgent);
        log.setIp(ip);
        log.setStatus(status != null ? status : 1);
        log.setExecuteTime(executeTime);
        log.setErrorMsg(errorMsg);
        this.save(log);
    }
}