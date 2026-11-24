package com.ai.smartinventory.common;

import java.math.BigDecimal;

/**
 * 金额边界（【P1-2】）。
 *
 * <p>背景：商品/采购等写接口此前对金额只做 {@code != null} 判空，
 * 负数、超大值、以及「小数位超过列精度」的值都能落库：
 * <ul>
 *   <li>负价格：商品原价 -1 元，配合「7天无理由」等规则可产生异常结算；</li>
 *   <li>超大值：{@code product.price} 是 {@code decimal(10,2)}，传 1e12 会在
 *       MySQL 严格模式下报错、非严格模式下被静默截断为 99999999.99；</li>
 *   <li>小数位：传 1.234 被静默四舍五入，账目与录入不一致。</li>
 * </ul>
 *
 * <p>本类提供统一的「单笔金额」合法性判定。注意：这里只管**格式与范围**，
 * 不掺业务语义（如「退款不得超过实付」由 {@code AfterSaleRule} 负责）。
 *
 * <h3>【上限为什么不止一个（2026-09-28 修复「采购总额被限到 1 亿」）】</h3>
 * 上限必须与**目标列的精度**一一对应，不能用一个常量包打天下。
 * 此前的缺陷正是把「单价上限」当成了「所有金额的上限」：
 * <pre>
 *   purchase_order.total_amount  DECIMAL(12,2)  → 真实上限 99,999,999,999.99
 *   product.price / cost         DECIMAL(10,2)  → 真实上限      99,999,999.99
 * </pre>
 * {@code PurchaseOrderController} 用的是 {@code validateNonNegative(...)}
 * （即 decimal(10,2) 口径），于是商家录入 1 亿以上的采购总额会被
 * 「采购总额不能超过 99999999.99」直接拒绝 —— 而数据库列本身**装得下**，
 * 属于典型的「校验比 schema 更严」的假上限。
 *
 * <p>据此拆成两档：
 * <ul>
 *   <li>{@link #MAX_AMOUNT}（decimal(10,2)）：单价 / 成本这类**单件金额**，
 *       沿用 {@code validateNonNegative} / {@code validateNonNegativeIfPresent}；</li>
 *   <li>{@link #MAX_TOTAL_AMOUNT}（decimal(12,2)）：采购总额这类**汇总金额**，
 *       走 {@code validateTotalAmount} / {@code validateTotalAmountIfPresent}。</li>
 * </ul>
 * 新增档位时请**先看 schema**（口径见 {@code db/init.sql} 注释），
 * 不要凭感觉改 {@code MAX_AMOUNT} —— 那会连带放宽商品单价，反而打开
 * 「单价 1e12 落库被截断」的旧口子（P1-2 的原始缺陷）。
 */
public final class MoneyBounds {

    /** 单件金额上限，与 {@code decimal(10,2)} 一致：99,999,999.99（商品价格 / 成本 / 单价）。 */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    /** 汇总金额上限，与 {@code decimal(12,2)} 一致：99,999,999,999.99（采购总额等）。 */
    public static final BigDecimal MAX_TOTAL_AMOUNT = new BigDecimal("99999999999.99");

    /** 允许的最大小数位（对齐 decimal(*,2)）。 */
    public static final int SCALE = 2;

    private MoneyBounds() {
    }

    /**
     * 校验「单价 / 成本」这类非负金额。
     *
     * @return 合法返回 {@code null}；不合法返回可直接回吐给前端的错误文案。
     */
    public static String validateNonNegative(BigDecimal value, String label) {
        if (value == null) {
            return "请填写" + label;
        }
        return validateNonNegativeIfPresent(value, label);
    }

    /**
     * 校验「可选金额」：为 {@code null} 时放行（表示不填 / 不修改该字段），
     * 非 null 时才做负数 / 精度 / 上限校验。
     *
     * <p>用于 {@code product.cost} 这类库中可空、业务上允许不填的列。
     */
    public static String validateNonNegativeIfPresent(BigDecimal value, String label) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            return label + "不能为负数";
        }
        if (value.scale() > SCALE) {
            return label + "最多保留 " + SCALE + " 位小数";
        }
        if (value.compareTo(MAX_AMOUNT) > 0) {
            return label + "不能超过 " + MAX_AMOUNT.toPlainString();
        }
        return null;
    }

    // ==================== 汇总金额（decimal(12,2)） ====================

    /**
     * 校验「采购总额 / 订单总额」这类**汇总金额**（目标列 {@code decimal(12,2)}）。
     *
     * <p>与 {@link #validateNonNegative(BigDecimal, String)} 的唯一差别是上限：
     * 这里放到 99,999,999,999.99，而不是单价的 99,999,999.99。
     *
     * @return 合法返回 {@code null}；不合法返回可直接回吐给前端的错误文案。
     */
    public static String validateTotalAmount(BigDecimal value, String label) {
        if (value == null) {
            return "请填写" + label;
        }
        return validateTotalAmountIfPresent(value, label);
    }

    /**
     * 校验「可选汇总金额」：为 {@code null} 时放行（表示不填 / 不修改该字段）。
     *
     * <p>采购单更新场景依赖这一语义 —— 请求体不带 totalAmount 时表示
     * 「本次不改金额」，不应被当成校验失败。
     */
    public static String validateTotalAmountIfPresent(BigDecimal value, String label) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            return label + "不能为负数";
        }
        if (value.scale() > SCALE) {
            return label + "最多保留 " + SCALE + " 位小数";
        }
        if (value.compareTo(MAX_TOTAL_AMOUNT) > 0) {
            return label + "不能超过 " + MAX_TOTAL_AMOUNT.toPlainString();
        }
        return null;
    }

    /**
     * 校验「库存 / 数量」这类非负整数。
     *
     * @return 合法返回 {@code null}；不合法返回错误文案。
     */
    public static String validateNonNegative(Integer value, String label) {
        if (value == null) {
            return null; // 允许不传（沿用默认值 / 保持原值）
        }
        if (value < 0) {
            return label + "不能为负数";
        }
        return null;
    }
}
