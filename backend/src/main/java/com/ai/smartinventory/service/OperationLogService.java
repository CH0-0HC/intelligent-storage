package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.entity.OperationLog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 操作日志服务接口
 */
public interface OperationLogService extends IService<OperationLog> {

    /**
     * 分页查询操作日志
     */
    PageResult<OperationLog> getLogPage(Integer page, Integer size, String username);

    /**
     * 记录操作日志
     */
    void recordLog(Long userId, String username, String operation, String method,
                   String params, String ip, Integer status, Long executeTime, String errorMsg);

    /**
     * 记录操作日志（【P1-5】扩展版）。
     *
     * <p>相较旧签名补上 {@code result}（业务返回体摘要）与 {@code userAgent}。
     * 实体里这两列一直存在却从未被写入，导致审计排查时看不到「请求方是什么客户端」
     * 与「业务实际返回了什么」。保留旧签名作为默认委托，避免破坏既有调用点。
     */
    void recordLog(Long userId, String username, String operation, String method,
                   String params, String ip, Integer status, Long executeTime, String errorMsg,
                   String result, String userAgent);
}