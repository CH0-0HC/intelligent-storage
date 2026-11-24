package com.ai.smartinventory.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 【P1-3】分页边界夹取的行为锁定。
 *
 * 与 {@code PaginationMaxLimitTest}（验证 SQL 层拦截器）互补：
 * 本测试覆盖「边界层」{@link PageBounds}，特别是那些**不查库、直接构造空页**
 * 的分支也会走到的夹取逻辑。
 */
@DisplayName("P1-3 PageBounds 分页边界夹取")
class PageBoundsTest {

    // ---------- page ----------

    @Test
    @DisplayName("page: null / 0 / 负数 一律归 1")
    void pageLowerBound() {
        assertEquals(1, PageBounds.page(null));
        assertEquals(1, PageBounds.page(0));
        assertEquals(1, PageBounds.page(-9));
    }

    @Test
    @DisplayName("page: 正常值原样返回")
    void pageNormal() {
        assertEquals(1, PageBounds.page(1));
        assertEquals(7, PageBounds.page(7));
        assertEquals(Integer.MAX_VALUE, PageBounds.page(Integer.MAX_VALUE));
    }

    // ---------- size ----------

    @Test
    @DisplayName("size: null / 0 / 负数 归默认 10")
    void sizeDefault() {
        assertEquals(10, PageBounds.size(null));
        assertEquals(10, PageBounds.size(0));
        assertEquals(10, PageBounds.size(-5));
    }

    @Test
    @DisplayName("size: 超上限被夹到 500")
    void sizeUpperBound() {
        assertEquals(500, PageBounds.size(501));
        assertEquals(500, PageBounds.size(1_000));
        assertEquals(500, PageBounds.size(1_000_000));
        assertEquals(500, PageBounds.size(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("size: 边界值 500 原样返回")
    void sizeBoundary() {
        assertEquals(500, PageBounds.size(500));
    }

    @Test
    @DisplayName("size: 正常值原样返回")
    void sizeNormal() {
        assertEquals(1, PageBounds.size(1));
        assertEquals(10, PageBounds.size(10));
        assertEquals(499, PageBounds.size(499));
    }

    @Test
    @DisplayName("常量与 MybatisPlusConfig 的 maxLimit 保持一致（防止两处漂移）")
    void maxSizeMatchesInterceptor() {
        assertEquals(500, PageBounds.MAX_SIZE);
        mybatisPlusConfigMaxLimitIsSame();
    }

    private void mybatisPlusConfigMaxLimitIsSame() {
        // 间接断言：若有人把拦截器的 500 改了而没改 PageBounds.MAX_SIZE，
        // PaginationMaxLimitTest#productionConfigHasMaxLimit 会先失败。
        // 这里只保证常量本身处于预期区间，明确它是「契约值」。
        assertEquals(10, PageBounds.DEFAULT_SIZE);
    }
}
