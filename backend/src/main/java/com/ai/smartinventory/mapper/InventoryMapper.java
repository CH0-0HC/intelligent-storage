package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.Inventory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {
    
    // ==================== 租户隔离（2026-09-22 补全） ====================
    // 三条查询都 LEFT JOIN product，因此可直接用 p.merchant_id 收敛数据范围。
    // merchantId 为 null 表示「不限制」（管理员 / 未登录的公开路径），
    // 与 common/MerchantContext.currentMerchantId() 的约定一致。
    // 背景：此前这三条查询完全没有商户条件，商家能看到并操作**全平台**库存与流水。

    List<Inventory> selectWarningInventories(@Param("merchantId") Long merchantId);

    List<Inventory> selectInventoryPageWithProduct(@Param("merchantId") Long merchantId, @Param("keyword") String keyword, @Param("offset") Integer offset, @Param("size") Integer size);
    
    Long selectInventoryPageCount(@Param("merchantId") Long merchantId, @Param("keyword") String keyword);

    // 【已删除】selectInventoryWithProduct(limit)：无分页的按库存升序查询。
    // 经全库检索确认 src/main 下零调用方（死代码），已连同 XML 语句一并删除。
    // 若将来需要「库存最低的 N 件」，请复用 selectInventoryPageWithProduct 并带 merchantId。

    // ==================== 原子库存操作（防超卖核心） ====================
    // 【修复说明】原实现为「先 getOne 查库存 → Java 里比对 → updateById 写回」，
    // 在并发下两个请求可同时读到同一库存值并各自扣减成功，造成超卖 / 库存被写负。
    // 现改为「条件更新 + 受影响行数判断」：把库存判断下沉到数据库的 WHERE 条件里，
    // 由 InnoDB 的行锁保证同一行的并发更新串行化。返回 0 表示条件不满足（库存不足），
    // 调用方据此抛业务异常并回滚整个下单事务。

    /**
     * 原子扣减库存：仅当 quantity >= 扣减量 且未删除时才更新，返回受影响行数。
     */
    @Update("UPDATE inventory SET quantity = quantity - #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId} AND deleted = 0 AND quantity >= #{quantity}")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 原子增加库存（入库 / 取消订单回滚），返回受影响行数。
     * 注意：quantity 变化同时也刷新 update_time，保证数据新鲜度可观测。
     */
    @Update("UPDATE inventory SET quantity = quantity + #{quantity}, " +
            "update_time = NOW() " +
            "WHERE product_id = #{productId} AND deleted = 0")
    int increaseStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 原子设置库存（盘点场景，直接覆盖为指定值而不是累加），返回受影响行数。
     */
    @Update("UPDATE inventory SET quantity = #{quantity}, update_time = NOW() " +
            "WHERE product_id = #{productId} AND deleted = 0")
    int setStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
