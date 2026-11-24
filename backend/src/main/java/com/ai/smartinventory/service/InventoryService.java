package com.ai.smartinventory.service;

import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Inventory;
import com.baomidou.mybatisplus.extension.service.IService;
import java.util.List;

public interface InventoryService extends IService<Inventory> {
    
    PageResult<Inventory> getInventoryPage(PageQueryDTO params);
    
    List<Inventory> getWarningInventories();
    
    boolean addStock(Long productId, Integer quantity, String type, Long orderId);
    
    boolean reduceStock(Long productId, Integer quantity, String type, Long orderId);
    
    SyncStockResult syncStock();
    
    record SyncStockResult(
        String status,
        int totalProducts,
        int updatedCount,
        int createdCount,
        List<SyncDetail> details
    ) {}
    
    record SyncDetail(
        Long productId,
        String productName,
        Integer productStock,
        Integer inventoryQuantity,
        String action,
        Integer newStock
    ) {}
}