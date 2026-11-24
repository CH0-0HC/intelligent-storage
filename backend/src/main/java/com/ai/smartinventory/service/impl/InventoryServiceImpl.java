package com.ai.smartinventory.service.impl;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.MerchantContext;
import com.ai.smartinventory.common.PageBounds;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.dto.PageQueryDTO;
import com.ai.smartinventory.entity.Inventory;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.mapper.InventoryMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.InventoryLogService;
import com.ai.smartinventory.service.InventoryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class InventoryServiceImpl extends ServiceImpl<InventoryMapper, Inventory> implements InventoryService {
    
    @Resource
    private InventoryLogService inventoryLogService;
    
    @Resource
    private ProductMapper productMapper;
    
    // 【已删除】原先还注入过 ProductService，但全类无任何调用（死依赖），已移除；
    // 商品读写统一走下面的 ProductMapper，少一条无用的 Spring 依赖。
    // ⚠️ 删这类字段时必须**连字段声明一起删**：只删 import 会留下
    //    「找不到符号: 类 ProductService」的编译错误（本行曾因此把构建打挂）。

    /**
     * 库存分页。
     *
     * 【租户隔离（2026-09-22 补全）】商户范围在这里实时解析后下推给 SQL，
     * 而不是改方法签名 —— 保持 InventoryService 接口不变，避免波及调用方与既有单测。
     * 管理员 / 未登录（公开路径）拿到 null，SQL 不拼商户条件 = 全局视图。
     */
    @Override
    public PageResult<Inventory> getInventoryPage(PageQueryDTO params) {
        // 【M-3 修复】此前直接用 params.getSize()/getPage()，未走 PageBounds。
        // 其余列表（商品/订单/售后/工单/日志）均已夹取，唯独库存分页漏了，
        // 且它走自定义 LIMIT SQL（非 IPage），MybatisPlusConfig 的 maxLimit 也管不到 ⇒
        // ?size=1000000 可一次性打爆内存。现统一夹取。
        int page = PageBounds.page(params.getPage());
        int size = PageBounds.size(params.getSize());
        String keyword = params.getKeyword() != null ? params.getKeyword() : "";

        Long merchantId = MerchantContext.currentMerchantId();

        int offset = (page - 1) * size;
        Long total = baseMapper.selectInventoryPageCount(merchantId, keyword);
        List<Inventory> records = baseMapper.selectInventoryPageWithProduct(merchantId, keyword, offset, size);

        return new PageResult<>(total, records, (long) page, (long) size);
    }

    /**
     * 库存预警列表。
     *
     * 【租户隔离（2026-09-22 补全）】商家只看到自己名下商品的预警。
     *
     * 注：AiController.getDashboardStats 此前是在 controller 里再按 ownedProductIds
     * 二次过滤（因为本方法当时返回全量）。现在本方法已自带隔离，那层过滤对商家而言
     * 成为冗余（结果不变），对管理员则仍需保留全平台语义 —— 因此未动该处代码。
     */
    @Override
    public List<Inventory> getWarningInventories() {
        return baseMapper.selectWarningInventories(MerchantContext.currentMerchantId());
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean addStock(Long productId, Integer quantity, String type, Long orderId) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(400, "入库数量必须大于 0");
        }

        Inventory inventory = this.getOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getProductId, productId));
        
        if (inventory == null) {
            // 首次入库：直接以本次数量建账
            inventory = new Inventory();
            inventory.setProductId(productId);
            inventory.setQuantity(quantity);
            inventory.setWarningQuantity(10);
            inventory.setLastInTime(LocalDateTime.now());
            this.save(inventory);
            // 并发下可能两个线程同时创建，靠 inventory 表的 uk_product_id 唯一键兜底
        } else {
            // 【修复】改用原子 UPDATE，避免与并发扣减互相覆盖（丢失更新）
            int affected = baseMapper.increaseStock(productId, quantity);
            if (affected == 0) {
                throw new BusinessException(400, "入库失败：库存记录状态异常，请刷新后重试");
            }
            this.update(new LambdaUpdateWrapper<Inventory>()
                    .eq(Inventory::getProductId, productId)
                    .set(Inventory::getLastInTime, LocalDateTime.now()));

            // 【修复·P0-2】与 reduceStock 同理：流水区间必须取自入库真实生效后的值，
            // 不能用入库前读到的旧快照（并发下会与其他入库/出库的区间重叠、错账）。
            Inventory after = this.getOne(new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getProductId, productId));
            int trueAfter = after != null && after.getQuantity() != null ? after.getQuantity() : 0;
            int trueBefore = trueAfter - quantity;

            InventoryLog log = new InventoryLog();
            log.setProductId(productId);
            log.setType(0);
            log.setQuantity(quantity);
            log.setBeforeQuantity(trueBefore);
            log.setAfterQuantity(trueAfter);
            log.setOrderType(type);
            log.setOrderId(orderId);
            inventoryLogService.save(log);
        }
        
        syncProductStock(productId);
        
        return true;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reduceStock(Long productId, Integer quantity, String type, Long orderId) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(400, "扣减数量必须大于 0");
        }

        // 【核心修复】原实现：getOne 读库存 → Java 比对 → updateById 写回。
        // 并发下两个请求可同时通过比对，各自扣减后互相覆盖，导致超卖。
        // 现改为数据库层条件更新：WHERE quantity >= #{quantity}，由行锁串行化，
        // 受影响行数为 0 即代表库存不足或记录不存在，直接抛异常回滚整个事务。
        Inventory inventory = this.getOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getProductId, productId));

        if (inventory == null) {
            throw new BusinessException(400, "商品库存记录不存在，无法下单");
        }

        int affected = baseMapper.deductStock(productId, quantity);
        if (affected == 0) {
            // 条件不满足：库存不足（或并发下已被他人扣走）
            int snap = inventory.getQuantity() != null ? inventory.getQuantity() : 0;
            throw new BusinessException(400,
                    "库存不足，当前仅剩 " + Math.max(snap, 0) + " 件");
        }

        this.update(new LambdaUpdateWrapper<Inventory>()
                .eq(Inventory::getProductId, productId)
                .set(Inventory::getLastOutTime, LocalDateTime.now()));

        // 【修复·P0-2】流水里的 before/after 必须取「扣减真实生效之后」的值。
        // 原实现用上面 getOne 的旧快照算 beforeQuantity / afterQuantity ——
        // 条件 UPDATE 是原子且带行锁的，但旧快照是 UPDATE 之前读的：
        // 并发两个请求都读到 100，各扣 1 后，两条流水都记「100 → 99」，
        // 而真实库存已是 98 —— 流水与库存永久对不上账，且区间重叠。
        // 现在同一事务内回读最新值（当前事务持有该行写锁，读到的是自己的结果）。
        Inventory after = this.getOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getProductId, productId));
        int trueAfter = after != null && after.getQuantity() != null ? after.getQuantity() : 0;
        int trueBefore = trueAfter + quantity;

        InventoryLog log = new InventoryLog();
        log.setProductId(productId);
        log.setType(1);
        log.setQuantity(quantity);
        log.setBeforeQuantity(trueBefore);
        log.setAfterQuantity(trueAfter);
        log.setOrderType(type);
        log.setOrderId(orderId);
        inventoryLogService.save(log);
        
        syncProductStock(productId);
        
        return true;
    }

    /**
     * 将 inventory 表的权威库存同步回 product.stock，保证两个字段最终一致。
     * 采用「以 inventory 为准直接覆盖」的原子 UPDATE，不参与累加，避免双重计数。
     */
    private void syncProductStock(Long productId) {
        Inventory latest = this.getOne(new LambdaQueryWrapper<Inventory>()
                .eq(Inventory::getProductId, productId));
        if (latest == null || latest.getQuantity() == null) {
            return;
        }
        Product product = productMapper.selectById(productId);
        if (product != null && !latest.getQuantity().equals(product.getStock())) {
            product.setStock(latest.getQuantity());
            productMapper.updateById(product);
        }
    }
    
    @Override
    @Transactional
    public SyncStockResult syncStock() {
        // 【租户隔离（2026-09-22 补全）】原实现遍历**全平台**商品并回写 product.stock，
        // 商家调用它等于对别人的商品做了一次盘点回写。现商家只同步自己名下商品，
        // 管理员（merchantId == null）仍做全量同步。
        Long merchantId = MerchantContext.currentMerchantId();
        LambdaQueryWrapper<Product> productWrapper = new LambdaQueryWrapper<>();
        if (merchantId != null) {
            productWrapper.eq(Product::getMerchantId, merchantId);
        }
        List<Product> products = productMapper.selectList(productWrapper);
        
        int updatedCount = 0;
        int createdCount = 0;
        List<SyncDetail> syncDetails = new ArrayList<>();
        
        for (Product product : products) {
            Inventory inventory = this.getOne(
                new LambdaQueryWrapper<Inventory>()
                    .eq(Inventory::getProductId, product.getId())
            );
            
            SyncDetail detail;
            
            if (inventory != null) {
                if (inventory.getQuantity() != null && !inventory.getQuantity().equals(product.getStock())) {
                    product.setStock(inventory.getQuantity());
                    productMapper.updateById(product);
                    detail = new SyncDetail(
                        product.getId(),
                        product.getName(),
                        product.getStock(),
                        inventory.getQuantity(),
                        "updated",
                        inventory.getQuantity()
                    );
                    updatedCount++;
                } else {
                    detail = new SyncDetail(
                        product.getId(),
                        product.getName(),
                        product.getStock(),
                        inventory.getQuantity(),
                        "no_change",
                        null
                    );
                }
            } else {
                inventory = new Inventory();
                inventory.setProductId(product.getId());
                inventory.setQuantity(product.getStock() != null ? product.getStock() : 0);
                inventory.setWarningQuantity(10);
                this.save(inventory);
                detail = new SyncDetail(
                    product.getId(),
                    product.getName(),
                    product.getStock(),
                    product.getStock(),
                    "created",
                    null
                );
                createdCount++;
            }
            
            syncDetails.add(detail);
        }
        
        return new SyncStockResult("success", products.size(), updatedCount, createdCount, syncDetails);
    }
}