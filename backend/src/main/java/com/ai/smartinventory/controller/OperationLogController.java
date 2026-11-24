package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.entity.OperationLog;
import com.ai.smartinventory.service.OperationLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

/**
 * 操作日志控制器
 */
@Slf4j
@SaCheckRole("admin")
@RestController
@RequestMapping("/log")
@Tag(name = "操作日志管理", description = "系统操作日志查询接口")
public class OperationLogController {

    @Resource
    private OperationLogService operationLogService;

    @Operation(summary = "查询操作日志列表", description = "分页查询系统操作日志，支持按用户名筛选")
    @GetMapping("/list")
    public Result<PageResult<OperationLog>> getLogList(
            @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页条数", example = "10") @RequestParam(defaultValue = "10") Integer size,
            @Parameter(description = "用户名（可选筛选）") @RequestParam(required = false) String username) {

        PageResult<OperationLog> result = operationLogService.getLogPage(page, size, username);
        return Result.success(result);
    }
}