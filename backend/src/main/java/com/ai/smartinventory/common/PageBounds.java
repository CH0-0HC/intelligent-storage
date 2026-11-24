package com.ai.smartinventory.common;

/**
 * 分页参数边界（【P1-3】）。
 *
 * <p>背景：分页请求的 {@code page} / {@code size} 来自客户端且此前无任何上限，
 * 传 {@code size=1000000} 会导致服务端一次性捞出全表（DoS / 内存/带宽打爆）。
 *
 * <p>本类提供统一夹取，作为「边界层」的显式保证；与此同时
 * {@code MybatisPlusConfig} 里的 {@code PaginationInnerInterceptor.setMaxLimit(500)}
 * 是「SQL 层」的兜底——两道防线并存，避免任何一条路径被绕过：
 * <ul>
 *   <li>SQL 层拦截器：所有经 MyBatis-Plus {@code page()} 的查询都会被改写 LIMIT；</li>
 *   <li>边界层夹取：对「不查库、直接构造空页返回」的分支同样生效，
 *       否则响应里的 {@code size} 会回吐未经限制的原始值，与真实契约不一致。</li>
 * </ul>
 *
 * <p>为什么不用 {@code @Max} 注解：分页入口分散（{@code PageQueryDTO}、多个裸
 * {@code @RequestParam Integer size}、service 内部 {@code new Page<>(p,s)}），
 * 逐处加注解必然漏；集中在工具方法里是唯一不会腐化的做法。
 */
public final class PageBounds {

    /** 单页最大条数。与 {@code MybatisPlusConfig} 的 maxLimit 必须保持一致。 */
    public static final int MAX_SIZE = 500;

    /** 缺省单页条数。 */
    public static final int DEFAULT_SIZE = 10;

    private PageBounds() {
    }

    /**
     * 夹取页码：小于 1 一律归 1。
     */
    public static int page(Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    /**
     * 夹取每页条数：{@code null} / 小于 1 归默认值（10），超过上限归上限（500）。
     */
    public static int size(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
