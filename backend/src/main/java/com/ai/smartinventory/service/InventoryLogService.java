package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.InventoryLog;
import com.baomidou.mybatisplus.extension.service.IService;

public interface InventoryLogService extends IService<InventoryLog> {
    
    PageResult<InventoryLog> getLogPage(PageQueryDTO params);
}