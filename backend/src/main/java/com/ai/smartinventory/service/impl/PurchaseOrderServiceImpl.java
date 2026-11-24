package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.entity.PurchaseOrder;
import com.ai.smartinventory.mapper.PurchaseOrderMapper;
import com.ai.smartinventory.service.PurchaseOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class PurchaseOrderServiceImpl extends ServiceImpl<PurchaseOrderMapper, PurchaseOrder> implements PurchaseOrderService {

    @Override
    public Object getPurchaseOrderPage(Map<String, Object> params) {
        int pageNum = params.get("pageNum") != null ? Integer.parseInt(params.get("pageNum").toString()) : 1;
        int pageSize = params.get("pageSize") != null ? Integer.parseInt(params.get("pageSize").toString()) : 10;
        
        // 【P1-3】统一夹取页码与 size 上限
        Page<PurchaseOrder> page = new Page<>(PageBounds.page(pageNum), PageBounds.size(pageSize));
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();

        // 【多租户隔离】与 ProductServiceImpl.getProductPage 同一约定：
        // 商家只能看到自己名下的采购单；管理员/普通用户 currentMerchantId() 返回 null，
        // 视为「全局视图」，不加 merchant_id 条件。
        // 修复背景：此前完全不按 merchant_id 过滤，商家打开采购管理会看到
        // 全平台所有商家的采购单（与第三轮修掉的 /ai/dashboard 泄露同型）。
        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null) {
            wrapper.eq(PurchaseOrder::getMerchantId, merchantId);
        }

        if (params.get("orderNo") != null && !params.get("orderNo").toString().isEmpty()) {
            wrapper.like(PurchaseOrder::getOrderNo, params.get("orderNo").toString());
        }
        if (params.get("supplierName") != null && !params.get("supplierName").toString().isEmpty()) {
            wrapper.like(PurchaseOrder::getSupplierName, params.get("supplierName").toString());
        }
        if (params.get("status") != null) {
            wrapper.eq(PurchaseOrder::getStatus, params.get("status"));
        }
        
        wrapper.orderByDesc(PurchaseOrder::getCreateTime);
        
        IPage<PurchaseOrder> result = baseMapper.selectPage(page, wrapper);
        return new PageResult<>(result.getTotal(), result.getRecords());
    }

    @Override
    public PurchaseOrder getOrderByNo(String orderNo) {
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PurchaseOrder::getOrderNo, orderNo);
        return baseMapper.selectOne(wrapper);
    }
}