package com.ai.smartinventory.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【P1-3 回归】分页 size 上限保护。
 *
 * 背景：分页请求的 size 此前无任何上限，客户端传 size=1000000 会让服务端一次性
 * 捞出全表（DoS / 内存打爆）。由于分页入口分散（PageQueryDTO、多个裸
 * {@code @RequestParam Integer size}、service 内部 {@code new Page<>(page,size)}），
 * 逐处加 @Max 必漏，故改为在 {@link PaginationInnerInterceptor}（所有分页查询的
 * 唯一必经之路）上统一 setMaxLimit(500)。
 *
 * 本测试锁定两件事：
 * 1. 生产配置确实装配了带 maxLimit 的分页拦截器（防止有人日后回退成裸构造）；
 * 2. 夹取行为本身正确：超上限被压到 500、负数被压到 500、正常值与非正数不受影响。
 *
 * 注：{@code handlerLimit} 是 protected，MyBatis-Plus 未暴露 public 入口，
 * 故用子类暴露以便在无数据库环境下纯单测（不必起 Spring 上下文）。
 */
@DisplayName("P1-3 分页size上限保护")
class PaginationMaxLimitTest {

    /** 暴露 protected 的 handlerLimit，用于脱离 MyBatis 执行链直接验证夹取逻辑。 */
    private static class ExposedInterceptor extends PaginationInnerInterceptor {
        ExposedInterceptor(DbType dbType, Long maxLimit) {
            super(dbType);
            setMaxLimit(maxLimit);
        }

        void applyLimit(Page<?> page) {
            handlerLimit(page, getMaxLimit());
        }
    }

    @Test
    @DisplayName("生产配置装配了带 maxLimit 的分页拦截器")
    void productionConfigHasMaxLimit() {
        MybatisPlusInterceptor interceptor = new MybatisPlusConfig().mybatisPlusInterceptor();
        List<InnerInterceptor> inners = interceptor.getInterceptors();
        assertNotNull(inners, "拦截器列表不应为空");
        assertEquals(1, inners.size(), "应注册且仅注册 1 个内部拦截器");

        Object first = inners.get(0);
        assertTrue(first instanceof PaginationInnerInterceptor, "应注册 PaginationInnerInterceptor");
        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) first;
        assertNotNull(pagination.getMaxLimit(), "分页拦截器必须设置 maxLimit（P1-3 修复点）");
        assertEquals(500L, pagination.getMaxLimit(), "上限应为 500 条/页");
    }

    @Test
    @DisplayName("size 超上限被夹取到 500")
    void oversizedSizeIsClamped() {
        ExposedInterceptor it = new ExposedInterceptor(DbType.MYSQL, 500L);
        Page<?> page = new Page<>(1, 1_000_000);
        it.applyLimit(page);
        assertEquals(500L, page.getSize(), "百万级 size 必须被压到 500");
    }

    @Test
    @DisplayName("负数 size 被夹取到 500（防 offset 计算翻转）")
    void negativeSizeIsClamped() {
        ExposedInterceptor it = new ExposedInterceptor(DbType.MYSQL, 500L);
        Page<?> page = new Page<>(1, -5);
        it.applyLimit(page);
        assertEquals(500L, page.getSize(), "负数 size 必须被压到 500");
    }

    @Test
    @DisplayName("正常 size 不受影响")
    void normalSizeUntouched() {
        ExposedInterceptor it = new ExposedInterceptor(DbType.MYSQL, 500L);
        Page<?> page = new Page<>(1, 20);
        it.applyLimit(page);
        assertEquals(20L, page.getSize(), "20 在限内应保持原值");
    }

    @Test
    @DisplayName("恰好等于上限的 size 不受影响（边界）")
    void boundarySizeUntouched() {
        ExposedInterceptor it = new ExposedInterceptor(DbType.MYSQL, 500L);
        Page<?> page = new Page<>(1, 500);
        it.applyLimit(page);
        assertEquals(500L, page.getSize(), "边界值 500 应保持原值");
    }

    @Test
    @DisplayName("未配置 maxLimit 时不夹取（回归保护：显式依赖配置生效）")
    void noMaxLimitMeansNoClamp() {
        ExposedInterceptor it = new ExposedInterceptor(DbType.MYSQL, null);
        Page<?> page = new Page<>(1, 1_000_000);
        it.applyLimit(page);
        assertEquals(1_000_000L, page.getSize(),
                "maxLimit 为 null 时拦截器不夹取 —— 正说明生产必须显式 setMaxLimit");
    }
}
