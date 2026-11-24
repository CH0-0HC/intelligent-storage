package com.ai.smartinventory.service;

import com.ai.smartinventory.entity.PurchaseOrder;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.Map;

public interface PurchaseOrderService extends IService<PurchaseOrder> {
    
    Object getPurchaseOrderPage(Map<String, Object> params);
    
    PurchaseOrder getOrderByNo(String orderNo);
}