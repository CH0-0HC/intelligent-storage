package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.Product;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {
    
    List<Product> selectHotProducts(@Param("limit") Integer limit);
    
    List<Product> selectRecommendProducts(@Param("limit") Integer limit);
    
    // 【已删除 selectProductsByCategory】
    // 该方法在接口与 ProductMapper.xml 里都有实现，但全项目无任何调用方；
    // 按分类取商品实际走的是 ProductServiceImpl.getProductsByCategory() 的
    // LambdaQueryWrapper（日志中 SQL 呈占位符形态，可印证）。
    // 属设计稿与实现脱节的残留，删除以免误导。
    
    // 【租户隔离（2026-09-22 补全）】热销榜原先只有 limit 参数，取的是全平台。
    // 加入可空 merchantId 后，商家看到的「自家 TOP N」与 /ai/report 的其余口径一致；
    // merchantId 为 null 时不拼条件（管理员 / 未登录的公开路径），行为与旧版完全相同。
    // 调用方仅 BusinessReportGenerator 一处，无兼容性负担。
    List<Product> selectTopProducts(@Param("limit") Integer limit, @Param("merchantId") Long merchantId);

    // ===== 原子库存同步（与 InventoryMapper 的原子操作配套）=====

    /**
     * 原子增加 product.stock，返回受影响行数。
     */
    @Update("UPDATE product SET stock = stock + #{quantity}, update_time = NOW() " +
            "WHERE id = #{productId} AND deleted = 0")
    int increaseStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 原子扣减 product.stock：仅当 stock >= quantity 时成立，返回受影响行数。
     */
    @Update("UPDATE product SET stock = stock - #{quantity}, update_time = NOW() " +
            "WHERE id = #{productId} AND deleted = 0 AND stock >= #{quantity}")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    // ===== 销量计数（2026-10-10 新增）=====
    // 【为什么原来没有】product.sales 列与 init.sql 的种子都在（默认 0），
    // 商品管理「销量」列、商品详情「销量：」、热门榜的 `ORDER BY sales DESC`
    // 三处都在读它，但**全项目没有任何一处写它**（grep setSales 为 0 命中）——
    // 结果所有商品销量恒为 0，热门榜的排序依据全是 0 等于没有排序。
    // 现在把它绑定到**与库存扣减完全对称**的时机上：扣库存时 +，回补库存时 -，
    // 这样销量与库存永远同向、同次、同事务变化，不会出现「库存回补了销量还挂着」。

    /**
     * 原子增加 product.sales（售出计数），返回受影响行数。
     *
     * <p>COALESCE 是为了兼容历史上 sales 为 NULL 的行（列定义是 `INT DEFAULT 0`，
     * 但早期数据可能没走默认值）。
     */
    @Update("UPDATE product SET sales = COALESCE(sales, 0) + #{quantity}, update_time = NOW() " +
            "WHERE id = #{productId} AND deleted = 0")
    int increaseSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);

    /**
     * 原子扣减 product.sales（订单取消时与库存回补对称回退），返回受影响行数。
     *
     * <p>用 GREATEST(..., 0) 兜底：并发或历史脏数据下不应把销量打成负数，
     * 负数会让「销量 TOP10」这类榜单出现反直觉的排序。
     */
    @Update("UPDATE product SET sales = GREATEST(COALESCE(sales, 0) - #{quantity}, 0), " +
            "update_time = NOW() WHERE id = #{productId} AND deleted = 0")
    int deductSales(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}
