package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.mapper.InventoryLogMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.InventoryLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import jakarta.annotation.Resource;

import java.util.List;

@Service
public class InventoryLogServiceImpl extends ServiceImpl<InventoryLogMapper, InventoryLog> implements InventoryLogService {

    @Resource
    private ProductMapper productMapper;

    /**
     * 出入库流水分页。
     *
     * 【租户隔离（2026-09-22 补全）】
     * 原实现是一句裸分页 `this.page(pageParam, wrapper)`，只带 orderBy ——
     * 任何商家都能看到**全平台**的出入库流水（含其他商户的进货价与数量）。
     *
     * `inventory_log` 表本身没有 merchant_id 列，归属只能从「商品」侧推导：
     * 先取当前商家名下的 productId 集合，再用 IN 收敛。
     *
     * ⚠️ 集合为空时必须**提前返回空页**，不能直接交给 in()：
     *    MyBatis-Plus 的 in() 收到空集合会拼出 `IN ()`，在 MySQL 里是语法错误。
     *
     * 管理员（merchantId == null）保持全局视图，不加任何过滤。
     */
    @Override
    public PageResult<InventoryLog> getLogPage(PageQueryDTO params) {
        Integer page = params.getPage() != null ? params.getPage() : 1;
        Integer size = params.getSize() != null ? params.getSize() : 10;

        LambdaQueryWrapper<InventoryLog> wrapper = new LambdaQueryWrapper<>();

        Long merchantId = MerchantContext.currentMerchantId();
        if (merchantId != null) {
            List<Long> ownedProductIds = productMapper.selectList(
                            new LambdaQueryWrapper<Product>()
                                    .select(Product::getId)
                                    .eq(Product::getMerchantId, merchantId))
                    .stream()
                    .map(Product::getId)
                    .toList();
            if (ownedProductIds.isEmpty()) {
                // 该商家名下无商品 -> 必然无流水，直接返回空页（避免 IN () 语法错误）
                return new PageResult<>(0L, List.of(), page.longValue(), size.longValue());
            }
            wrapper.in(InventoryLog::getProductId, ownedProductIds);
        }

        wrapper.orderByDesc(InventoryLog::getCreateTime);

        // 【P1-3】统一夹取页码与 size 上限
        Page<InventoryLog> pageParam = new Page<>(PageBounds.page(page), PageBounds.size(size));
        Page<InventoryLog> result = this.page(pageParam, wrapper);
        return new PageResult<>(result.getTotal(), result.getRecords(), result.getCurrent(), result.getSize());
    }
}
