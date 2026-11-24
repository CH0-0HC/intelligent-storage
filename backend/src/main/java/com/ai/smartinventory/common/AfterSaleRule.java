package com.ai.smartinventory.common;

import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.dto.AfterSaleItemApplyDTO;
import com.ai.smartinventory.entity.AfterSale;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.ServiceTicket;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 售后规则中心 —— 「确认收货后多久内、什么状态、能不能申请」的唯一判定出口。
 *
 * 【为什么要把规则收敛到一个类】
 * 入口展示条件（前端按钮是否出现 / 是否置灰）、提交接口的合法性校验、
 * 列表接口回填的状态提示，本质是**同一套规则**。若前端按一套口径算、
 * 后端按另一套口径校验，就会出现「按钮能点但提交被拒」这类最难解释的 bug。
 * 因此：后端判定 → 前端只消费 {@link AfterSaleEligibility}，不自行推导。
 *
 * 【口径来源：淘宝 / 京东】
 *   · 淘宝：交易成功（确认收货）后 15 天内可在「我的订单」申请售后；
 *     「7 天无理由退货」自签收之日起 7 天内有效。
 *   · 京东：「退换/售后」入口在已完成订单上长期可见，
 *     7 天无理由同样以签收时间为起点，超期后入口保留但不可提交。
 *   本项目采用同一口径：入口有效期 15 天，无理由类型收紧到 7 天。
 */
public final class AfterSaleRule {

    private AfterSaleRule() {
    }

    // ==================== 时效 ====================

    /** 售后入口有效期：确认收货后 15 天内可申请（淘宝口径） */
    public static final int WINDOW_DAYS = 15;

    /** 「7 天无理由」有效期：确认收货后 7 天内，且仅适用于仅退款 / 退货退款 */
    public static final int NO_REASON_DAYS = 7;

    // ---------- 商品级退货期限（2026-09-28 新增） ----------

    /**
     * 商品未配置退货期限时的兜底天数。
     *
     * <p>取值刻意等于改造前的全站口径 {@link #WINDOW_DAYS}=15：存量商品
     * （{@code return_window_days} 为 NULL）与老前端提交的请求，行为与改造前
     * **逐字一致**，不会因为这次改造突然收紧或放宽任何已有商品。
     */
    public static final int DEFAULT_RETURN_WINDOW_DAYS = 15;

    /** 退货期限最大值（对自定义天数做上界，防「99999 天」这类无意义配置） */
    public static final int MAX_RETURN_WINDOW_DAYS = 365;

    /** 商家可选的预设档位（前端下拉直接消费，避免两端各写一份枚举） */
    public static final int[] PRESET_RETURN_WINDOW_DAYS = {7, 15, 30};

    /**
     * 「不支持退货退款」的商品仍允许在期限内因**质量问题**等有理由原因申请，
     * 因此该配置只关闭「无理由」，不等于关闭整个售后域。
     */
    public static final int RETURN_WINDOW_NONE = 0;

    /**
     * 把商品上的 {@code return_window_days} 归一化为可用天数。
     *
     * @param configured 商品配置值（可为 null = 未配置；0 = 不支持退货；负数视为未配置）
     * @return 归一化后的天数（>= 1）；{@code 0} 表示不支持退货退款，原样返回 0
     */
    public static int normalizeReturnWindowDays(Integer configured) {
        if (configured == null || configured < 0) {
            return DEFAULT_RETURN_WINDOW_DAYS;
        }
        if (configured == RETURN_WINDOW_NONE) {
            return RETURN_WINDOW_NONE;
        }
        return Math.min(configured, MAX_RETURN_WINDOW_DAYS);
    }

    /** 商家配置的退货期限是否合法（供 ProductController 校验入参）。 */
    public static boolean isValidReturnWindowDays(Integer days) {
        if (days == null) {
            return false;
        }
        // 0 是合法的「不支持退货退款」；其余须落在 1..MAX
        return days == RETURN_WINDOW_NONE || (days >= 1 && days <= MAX_RETURN_WINDOW_DAYS);
    }

    /** 退货期限的展示文案，前后端共用同一口径。 */
    public static String returnWindowText(Integer configured) {
        int days = normalizeReturnWindowDays(configured);
        return days == RETURN_WINDOW_NONE ? "不支持退货退款" : "确认收货后 " + days + " 天内";
    }

    // ==================== 订单状态 ====================

    public static final int ORDER_STATUS_UNPAID = 0;
    public static final int ORDER_STATUS_PAID = 1;
    public static final int ORDER_STATUS_SHIPPED = 2;
    /** 已完成 = 用户已确认收货，是申请售后的**唯一**合法起点 */
    public static final int ORDER_STATUS_COMPLETED = 3;
    public static final int ORDER_STATUS_CANCELED = 4;

    // ==================== 售后单状态 ====================

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_AGREED = 1;
    public static final int STATUS_FINISHED = 2;
    public static final int STATUS_REJECTED = 3;
    public static final int STATUS_CANCELLED = 4;

    /** 进行中：占用「一单一售后」额度，不允许同订单再提新申请 */
    public static boolean isActive(int status) {
        return status == STATUS_PENDING || status == STATUS_AGREED;
    }

    // ==================== 售后类型 ====================

    public static final int TYPE_REFUND_ONLY = 1;
    public static final int TYPE_RETURN_REFUND = 2;
    public static final int TYPE_EXCHANGE = 3;

    public static boolean isValidType(Integer type) {
        return type != null && (type == TYPE_REFUND_ONLY || type == TYPE_RETURN_REFUND || type == TYPE_EXCHANGE);
    }

    // ==================== 原因 ====================

    public static final String REASON_NO_REASON = "no_reason";
    public static final String REASON_QUALITY = "quality";
    public static final String REASON_MISMATCH = "mismatch";
    public static final String REASON_DAMAGED = "damaged";
    public static final String REASON_LATE = "late";
    public static final String REASON_OTHER = "other";

    /** 原因编码 → 展示文案。有序 Map 保证前端下拉顺序稳定。 */
    private static final Map<String, String> REASONS = new LinkedHashMap<>();

    /**
     * 原因编码 → 英文文案（2026-10-10 新增）。
     *
     * <p><b>为什么在服务端做而不是前端 i18n：</b>本项目既有约定是「字典类文案由后端下发」
     * （见 KefuWorkbench.vue / ServiceTicketApply.vue 里的注释），前端刻意不硬编码第二套，
     * 以免两端各写一份然后漂移。英文只是同一份字典的另一种语言，不是"第二份内容"，
     * 所以仍然放在服务端，由 {@link #reasonCatalog(String)} 按请求语言挑选。
     *
     * <p>键必须与 {@link #REASONS} 完全一致 —— {@link #isValidReason(String)} 只看中文那份。
     */
    private static final Map<String, String> REASONS_EN = new LinkedHashMap<>();

    static {
        REASONS.put(REASON_NO_REASON, "7天无理由退货");
        REASONS.put(REASON_QUALITY, "质量问题");
        REASONS.put(REASON_MISMATCH, "与商品描述不符");
        REASONS.put(REASON_DAMAGED, "包装破损 / 少件 / 污渍");
        REASONS.put(REASON_LATE, "商家未按约定时间发货");
        REASONS.put(REASON_OTHER, "其他原因");

        REASONS_EN.put(REASON_NO_REASON, "7-day no-reason return");
        REASONS_EN.put(REASON_QUALITY, "Quality issue");
        REASONS_EN.put(REASON_MISMATCH, "Not as described");
        REASONS_EN.put(REASON_DAMAGED, "Damaged packaging / missing parts / stains");
        REASONS_EN.put(REASON_LATE, "Merchant did not ship on time");
        REASONS_EN.put(REASON_OTHER, "Other reasons");
    }

    /**
     * 中文字典（默认）。保留无参重载是为了不动任何既有调用方 ——
     * 申请时写库的 {@link #reasonText(String)} 快照、以及未传 lang 的旧调用都走这里。
     */
    public static Map<String, String> reasonCatalog() {
        return new LinkedHashMap<>(REASONS);
    }

    /**
     * 按请求语言取原因字典。
     *
     * @param lang {@code en}（含 {@code en-US} 等）返回英文，其余一律返回中文
     */
    public static Map<String, String> reasonCatalog(String lang) {
        return new LinkedHashMap<>(isEnglish(lang) ? REASONS_EN : REASONS);
    }

    /**
     * 是否按英文处理。判宽松（{@code en} / {@code EN-us} / {@code english} 都算），
     * 因为请求头与前端传参的形态不受我们控制，认不出就退回中文（默认语言）。
     *
     * <p>{@code ServiceTicketController} 取裁定标准原文时也要用同一个判定，
     * 故开放为 public，避免两处各写一份然后漂移。
     */
    public static boolean isEnglish(String lang) {
        if (lang == null || lang.isBlank()) {
            return false;
        }
        String l = lang.trim().toLowerCase();
        return l.equals("en") || l.startsWith("en-") || l.startsWith("en_") || l.equals("english");
    }

    public static boolean isValidReason(String code) {
        return code != null && REASONS.containsKey(code);
    }

    public static String reasonText(String code) {
        return REASONS.getOrDefault(code, "其他原因");
    }

    /** 「无理由」只对仅退款 / 退货退款开放；换货必须有明确理由 */
    public static boolean allowsNoReason(Integer type) {
        return type != null && (type == TYPE_REFUND_ONLY || type == TYPE_RETURN_REFUND);
    }

    // ==================== 判定结果码 ====================

    public static final String CODE_ELIGIBLE = "ELIGIBLE";
    public static final String CODE_UNPAID = "UNPAID";
    public static final String CODE_NOT_RECEIVED = "NOT_RECEIVED";
    public static final String CODE_CANCELED = "CANCELED";
    public static final String CODE_UNSUPPORTED = "UNSUPPORTED";
    public static final String CODE_PROCESSING = "PROCESSING";
    public static final String CODE_FINISHED = "FINISHED";
    public static final String CODE_EXPIRED = "EXPIRED";
    /** 【R15】订单状态=已完成但从未付款 —— 历史脏数据或绕过收货校验写入的数据 */
    public static final String CODE_NOT_PAID = "NOT_PAID";

    /**
     * 【2026-09-28】已超出商品退货期限，但**可申请客服介入**。
     *
     * <p>与 {@link #CODE_EXPIRED} 的区别是「还有没有路可走」：
     * <ul>
     *   <li>{@code EXPIRED} —— 过期且该商品不支持退货，或客服已驳回且无重提理由，
     *       本质上无路可走；</li>
     *   <li>{@code CS_REQUIRED} —— 过期但支持退货，用户应「先联系客服」，
     *       由客服按商家统一标准裁定后放行。</li>
     * </ul>
     * 前端据此前者置灰、后者渲染成**可点击的「联系客服」**按钮——
     * 这正是本次改造要打开的通道。
     */
    public static final String CODE_CS_REQUIRED = "CS_REQUIRED";

    /** 【2026-09-28】已获得客服放行授权，可继续提交售后申请 */
    public static final String CODE_CS_APPROVED = "CS_APPROVED";

    // ==================== 客服裁定工单（2026-09-28 新增） ====================

    /** 工单状态：0-待客服裁定 */
    public static final Integer TICKET_PENDING = 0;
    /** 工单状态：1-已裁定（是否放行看 resolution） */
    public static final Integer TICKET_RESOLVED = 1;
    /** 工单状态：2-用户已取消 */
    public static final Integer TICKET_CANCELLED = 2;

    /** 裁定结果：1-允许继续申请售后（一次性放行授权） */
    public static final Integer RESOLUTION_ALLOW = 1;
    /** 裁定结果：2-驳回申请 */
    public static final Integer RESOLUTION_REJECT = 2;

    /**
     * 客服放行授权的有效期（天）。
     *
     * <p>放行不等于「此后永久可退」：客服裁定通过后，买家应在 7 天内提交售后，
     * 否则授权失效、需重新联系客服。这样既给了买家缓冲，也避免一张工单
     * 变成无限期的超期通行证（配合工单的「一次性消费」形成双重约束）。
     */
    public static final int CS_AUTHORIZATION_DAYS = 7;

    /** 判定工单是否为「可用放行授权」：已裁定通过且尚未被消费。 */
    public static boolean isUsableAuthorization(ServiceTicket ticket) {
        return ticket != null
                && TICKET_RESOLVED.equals(ticket.getStatus())
                && RESOLUTION_ALLOW.equals(ticket.getResolution())
                && (ticket.getConsumed() == null || ticket.getConsumed() == 0);
    }

    /** 放行授权的到期时间；无操作时间时返回 null（视为不限期，不误伤买家）。 */
    public static LocalDateTime authorizationDeadline(ServiceTicket ticket) {
        if (ticket == null || ticket.getOperateTime() == null) {
            return null;
        }
        return ticket.getOperateTime().plusDays(CS_AUTHORIZATION_DAYS);
    }

    /**
     * 客服裁定结果的展示文案（工单列表 / 售后申请页共用，避免两端各写一份）。
     */
    public static String resolutionText(Integer resolution) {
        if (RESOLUTION_ALLOW.equals(resolution)) {
            return "客服已同意继续申请售后";
        }
        if (RESOLUTION_REJECT.equals(resolution)) {
            return "客服已驳回售后申请";
        }
        return "待客服裁定";
    }

    /**
     * 核心判定：某订单当前能否申请售后（**兼容入口**，无商品上下文）。
     *
     * <p>保留原签名与逐字行为，{@code AfterSaleRuleTest} 的 43 项断言依赖它：
     * 无商品上下文时按 {@link #DEFAULT_RETURN_WINDOW_DAYS}（=15，与改造前一致）
     * 计算期限，且不存在客服放行工单。
     *
     * <p>需要商品级退货期限 / 客服放行时，走
     * {@link #evaluate(Order, List, LocalDateTime, Map, Map)}。
     *
     * @param order   订单（可为 null，表示不存在/无权访问）
     * @param history 该订单已有的售后单（可为 null），不要求有序
     * @param now     判定基准时间（显式传入，便于单测固定时间）
     */
    public static AfterSaleEligibility evaluate(Order order, List<AfterSale> history, LocalDateTime now) {
        return evaluate(order, history, now, null, null);
    }

    /**
     * 核心判定（**完整口径**）：商品级退货期限 + 客服放行。
     *
     * @param order          订单（可为 null）
     * @param history        该订单已有的售后单（可为 null）
     * @param now            判定基准时间
     * @param productsById   {@code productId → Product}，用于取每个商品自己的退货期限；
     *                       为 null / 缺项时按 {@link #DEFAULT_RETURN_WINDOW_DAYS} 处理
     * @param approvedTicket {@code orderId → 已裁定通过的客服工单}，用于识别放行授权；
     *                       为 null 表示没有客服放行
     */
    public static AfterSaleEligibility evaluate(Order order,
                                                List<AfterSale> history,
                                                LocalDateTime now,
                                                Map<Long, Product> productsById,
                                                Map<Long, ServiceTicket> approvedTicket) {
        AfterSaleEligibility result = new AfterSaleEligibility();

        if (order == null) {
            result.setEligible(false);
            result.setCode(CODE_UNSUPPORTED);
            result.setHint("订单不存在或无权访问");
            return result;
        }

        // 商品级退货期限：取订单内最严（最小）的一档。
        // 0 = 该商品不支持退货退款；null 明细 / 缺商品信息 → 兜底 15 天。
        // 商品级退货期限：取订单内最严（最小）的一档。
        // 【关键区分】「没有商品信息」与「商品明确不支持退货」必须分开：
        //   · 有商品信息 + 期限 0  → 商家**明确声明**不支持退货 ⇒ 真没路，EXPIRED；
        //   · 查不到任何商品信息（空明细 / 商品已删 / 旧调用路径没传商品）
        //     → 不能凭空断定「不支持」，更不能把用户引向客服通道
        //       （客服届时也查不到该商品的规则，等于让用户白跑一趟）。
        //       此时按 DEFAULT_RETURN_WINDOW_DAYS 处理，行为与改造前逐字一致。
        // 这条区分同时是 AfterSaleRuleTest 43 项旧断言的兼容红线 ——
        // 那个测试构造的订单没有 items，必须仍然走 EXPIRED 而不是 CS_REQUIRED。
        Map<Long, Product> known = resolveKnownProducts(order, productsById);
        boolean hasProductContext = !known.isEmpty();
        int windowDays = hasProductContext
                ? strictestReturnWindow(order, known)
                : DEFAULT_RETURN_WINDOW_DAYS;
        result.setReturnWindowDays(windowDays);
        // 「是否商家显式配置过」的判定：字段本身为 null 即视为未配置。
        // 不能用 `windowDays != RETURN_WINDOW_NONE`（那是「是不是 0」），
        // 也不能只看 hasProductContext —— 商品存在但字段没填同样属于「未配置」，
        // 前端会据此选择「展示商品自己的规则」还是「回退到通用 15 天文案」。
        result.setReturnWindowConfigured(hasProductContext && hasExplicitReturnWindow(order, known));
        result.setReturnNoReasonAllowed(hasProductContext ? allowsNoReasonByProduct(order, known) : Boolean.TRUE);

        // 客服放行授权：必须是「本订单 + 已裁定通过 + 尚未被消费」的工单
        ServiceTicket ticket = approvedTicket == null ? null : approvedTicket.get(order.getId());
        boolean csApproved = isUsableAuthorization(ticket);
        // 放行授权本身也有有效期（CS_AUTHORIZATION_DAYS 天内须提交），
        // 但授权是否**过期**要等 completeTime 解析出来后才能算，故此处只记录工单，
        // 真正的过期判定放在第 3 步（拿到 start 之后）。
        result.setCsApproved(csApproved);
        if (csApproved) {
            result.setCsTicketId(ticket.getId());
            result.setCsTicketNo(ticket.getTicketNo());
            LocalDateTime csDeadline = authorizationDeadline(ticket);
            if (csDeadline != null) {
                result.setCsAuthorizationDeadline(csDeadline);
            }
        }
        result.setCsRequired(Boolean.FALSE);

        // 最近一条售后单（按 id 倒序，id 递增即时间递增）
        AfterSale latest = null;
        if (history != null && !history.isEmpty()) {
            latest = history.stream()
                    .filter(Objects::nonNull)
                    .max(Comparator.comparing(a -> a.getId() == null ? 0L : a.getId()))
                    .orElse(null);
        }
        if (latest != null) {
            result.setHasAfterSale(Boolean.TRUE);
            result.setLatestAfterSaleId(latest.getId());
            result.setLatestStatus(latest.getStatus());
            result.setLatestType(latest.getType());
        }

        int status = order.getOrderStatus() == null ? -1 : order.getOrderStatus();

        if (status == ORDER_STATUS_UNPAID) {
            result.setEligible(false);
            result.setCode(CODE_UNPAID);
            result.setHint("订单尚未付款，付款并确认收货后即可申请售后");
            return result;
        }
        if (status == ORDER_STATUS_PAID || status == ORDER_STATUS_SHIPPED) {
            // 边界：货还没到手上。此处不开放售后（未收货场景应先走取消订单）。
            result.setEligible(false);
            result.setCode(CODE_NOT_RECEIVED);
            result.setHint("订单尚未确认收货，收到货并点击「确认收货」后即可申请售后");
            return result;
        }
        if (status == ORDER_STATUS_CANCELED) {
            result.setEligible(false);
            result.setCode(CODE_CANCELED);
            result.setHint("订单已取消，无需申请售后");
            return result;
        }
        if (status != ORDER_STATUS_COMPLETED) {
            result.setEligible(false);
            result.setCode(CODE_UNSUPPORTED);
            result.setHint("当前订单状态不支持申请售后");
            return result;
        }

        // ===== 以下均为「已确认收货」的订单 =====

        // 【R15 兜底】售后是「往外退钱」的接口，只在收货环节把关不够：
        // 库里已经存在 pay_status=0 却 order_status=3 的历史数据（order id=4），
        // 这类数据不可能再经过「确认收货」，必须在这里独立拦一次。
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            result.setEligible(false);
            result.setCode(CODE_NOT_PAID);
            result.setHint("该订单未完成付款，不支持申请售后");
            return result;
        }

        // 1) 进行中优先于任何时效判断：单子还在处理，不能重复提
        if (latest != null && isActive(latest.getStatus())) {
            result.setEligible(false);
            result.setCode(CODE_PROCESSING);
            result.setHint("售后处理中，可点击查看进度");
            return result;
        }
        if (latest != null && latest.getStatus() == STATUS_FINISHED) {
            result.setEligible(false);
            result.setCode(CODE_FINISHED);
            result.setHint("售后已完成，如有疑问请联系客服");
            return result;
        }

        // 2) 时效。completeTime 为空的脏数据用 updateTime 兜底（并标记 degraded），
        //    避免历史订单因为一次签收时间缺记就永久无法申请。
        LocalDateTime start = order.getCompleteTime() != null ? order.getCompleteTime() : order.getUpdateTime();
        if (start == null) {
            result.setEligible(false);
            result.setCode(CODE_EXPIRED);
            result.setExpired(Boolean.TRUE);
            result.setHint("无法确定确认收货时间，请联系客服处理售后");
            return result;
        }
        if (order.getCompleteTime() == null) {
            result.setDegraded(Boolean.TRUE);
        }

        long passedDays = Math.max(0, Duration.between(start, now).toDays());

        // 3) 客服已放行：期限判断整体让位，其余限制（如商品不支持无理由）仍然生效。
        //    这正是「由客服依据商家指定标准裁定后，用户方可继续申请」的落点。
        if (csApproved) {
            LocalDateTime csDeadline = authorizationDeadline(ticket);
            if (csDeadline != null && now.isAfter(csDeadline)) {
                // 放行授权过期：回到「需重新联系客服」，而不是静默拒绝
                result.setEligible(false);
                result.setCsApproved(Boolean.FALSE);
                result.setCsRequired(Boolean.TRUE);
                result.setExpired(Boolean.TRUE);
                result.setCode(CODE_CS_REQUIRED);
                result.setCsAuthorizationDeadline(csDeadline);
                result.setHint("客服放行授权已于 " + csDeadline.toLocalDate()
                        + " 过期（有效期 " + CS_AUTHORIZATION_DAYS + " 天），请重新联系客服裁定");
                return result;
            }
            result.setEligible(true);
            result.setCode(CODE_CS_APPROVED);
            result.setExpired(Boolean.FALSE);
            result.setDaysLeft(0);
            result.setNoReasonDaysLeft(noReasonDaysLeftForVerdict(order, known, start, now, passedDays, windowDays));
            result.setHint("客服已依据商家退货规则同意您的申请，请在本次授权内提交（一次有效"
                    + (csDeadline == null ? "" : "，有效期至 " + csDeadline.toLocalDate()) + "）");
            return result;
        }

        // 4) 是否超出该商品的退货期限。
        //    【边界口径必须与改造前逐字一致】原实现是 `now.isAfter(deadline)` ——
        //    即 deadline **当天**（now == deadline）仍算在期限内（闭区间）。
        //    AfterSaleRuleTest#day15BoundaryStillEligible 锁的就是这条；
        //    若写成 `!now.isBefore(deadline)` 会把当天判成超期，属语义回归。
        LocalDateTime deadline = start.plusDays(windowDays);
        boolean overWindow = windowDays == RETURN_WINDOW_NONE || now.isAfter(deadline);

        if (overWindow) {
            result.setEligible(false);
            result.setExpired(Boolean.TRUE);
            result.setDaysLeft(0);
            result.setNoReasonDaysLeft(0);

            // 分岔：不支持退货 ⇒ 真的没路；支持退货 ⇒ 打开客服通道。
            // 注意 canAskCs 还要求「确实拿到了商品信息」—— 否则客服也查不到
            // 该商品的规则，把用户引过去只是让他白跑（见上面 hasProductContext 的说明）。
            // 三分岔（顺序即优先级）：
            //   ① 无商品信息 → 不能断言「商家不支持退货」，也不能把用户引向客服
            //      （客服届时也查不到该商品的规则）。文案回落到改造前那句，
            //      AfterSaleRuleTest#longExpired 锁定的正是这句（必须含 "15 天"）；
            //   ② 有商品信息 + 期限 0 → 商家**明确声明**不支持退货 ⇒ 真没路，EXPIRED；
            //   ③ 有商品信息 + 有期限 → 打开客服通道（本次改造要打开的路）。
            boolean canAskCs = hasProductContext && windowDays != RETURN_WINDOW_NONE;
            result.setCsRequired(canAskCs);
            result.setCode(canAskCs ? CODE_CS_REQUIRED : CODE_EXPIRED);
            if (!hasProductContext) {
                result.setHint("已超过确认收货后 " + DEFAULT_RETURN_WINDOW_DAYS + " 天的售后申请期");
            } else if (!canAskCs) {
                result.setHint("该商品不支持退货退款，如确有质量问题请联系客服处理");
            } else {
                result.setHint("已超过该商品支持的退货退款期限（" + returnWindowText(windowDays)
                        + "），请先联系客服，由客服依据商家规则裁定后即可继续申请");
            }
            return result;
        }

        result.setEligible(true);
        result.setCode(CODE_ELIGIBLE);
        result.setExpired(Boolean.FALSE);
        result.setDaysLeft((int) Math.max(0, windowDays - passedDays));
        result.setNoReasonDaysLeft(noReasonDaysLeftForVerdict(order, known, start, now, passedDays, windowDays));

        boolean noReasonAllowed = allowsNoReasonByProduct(order, known);
        // 「无理由」的可用天数上限 = min(商品期限, NO_REASON_DAYS)。
        // 商家把期限设到 7 天以下时（如 3 天），无理由不可能比整条售后线还长，
        // 此时提示要说清是「期限本身短」，而不是含糊地讲「已过无理由期」。
        int noReasonCap = Math.min(windowDays, NO_REASON_DAYS);
        if (!noReasonAllowed) {
            result.setHint("可在" + returnWindowText(windowDays) + "申请售后；该商品不支持无理由退货，"
                    + "请选择质量问题等原因");
        } else if (result.getNoReasonDaysLeft() > 0) {
            result.setHint("可在" + returnWindowText(windowDays) + "申请售后，其中无理由退货还剩 "
                    + result.getNoReasonDaysLeft() + " 天");
        } else if (noReasonCap < NO_REASON_DAYS) {
            result.setHint("该商品退货退款期限为 " + windowDays + " 天，已超过无理由退货期，"
                    + "仍可申请质量问题等有理由售后（剩余 " + result.getDaysLeft() + " 天）");
        } else {
            // 文案里保留 NO_REASON_DAYS 的字面值（"7 天"）：
            // AfterSaleRuleTest#day7NoReasonExhausted 断言的是 "7" 这个数字，
            // 也是用户在下单页被承诺过的口径，不要改写成别的说法。
            result.setHint("已超过 " + NO_REASON_DAYS + " 天无理由期，仍可申请质量问题等有理由售后（剩余 "
                    + result.getDaysLeft() + " 天）");
        }
        return result;
    }

    /**
     * 「无理由」剩余天数。
     *
     * <p>口径：无理由窗口 = min(商品退货期限, {@link #NO_REASON_DAYS})。
     * 商家把期限设成 7 天以下（如 3 天）时，无理由不能比整条售后线还长。
     * 商品配置为「不支持退货」时直接返回 0。
     */
    private static int noReasonDaysLeftForVerdict(Order order,
                                                  Map<Long, Product> productsById,
                                                  LocalDateTime start,
                                                  LocalDateTime now,
                                                  long passedDays,
                                                  int windowDays) {
        if (windowDays == RETURN_WINDOW_NONE) {
            return 0;
        }
        if (!allowsNoReasonByProduct(order, productsById)) {
            return 0;
        }
        int cap = Math.min(windowDays, NO_REASON_DAYS);
        return (int) Math.max(0, cap - passedDays);
    }

    /**
     * 从「订单明细 + productId→Product」里挑出**确实查到了商品**的那部分。
     *
     * <p>为什么要单独挑一遍，而不是直接用 productsById：
     * 「商品信息为空」和「商品明确配置成不支持退货」在业务上完全不同 ——
     * 前者是数据缺失（不该据此收紧用户权益），后者是商家的明确声明。
     * 二者在 productsById 里都表现为「取不到值」，故必须先用订单明细把
     * 「本该有商品的那些明细」筛出来，再判断有没有命中。
     *
     * @return 命中商品的映射；为空表示本次判定**没有可用的商品上下文**
     */
    private static Map<Long, Product> resolveKnownProducts(Order order, Map<Long, Product> productsById) {
        Map<Long, Product> known = new LinkedHashMap<>();
        if (order == null || order.getItems() == null || productsById == null) {
            return known;
        }
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getProductId() == null) {
                continue;
            }
            Product product = productsById.get(item.getProductId());
            if (product != null) {
                known.put(product.getId(), product);
            }
        }
        return known;
    }

    /**
     * 订单内是否至少有一个商品**显式配置**了退货期限
     * （{@code product.return_window_days} 非 null；0 也算显式配置）。
     */
    private static boolean hasExplicitReturnWindow(Order order, Map<Long, Product> known) {
        if (known == null || known.isEmpty()) {
            return false;
        }
        return known.values().stream().anyMatch(p -> p != null && p.getReturnWindowDays() != null);
    }

    /** 订单内所有商品是否都允许「无理由」退货；任一件不支持即收紧为不支持。 */
    private static boolean allowsNoReasonByProduct(Order order, Map<Long, Product> productsById) {
        if (productsById == null || order == null || order.getItems() == null || order.getItems().isEmpty()) {
            return true; // 无商品上下文 ⇒ 沿用旧口径（支持无理由）
        }
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getProductId() == null) {
                continue;
            }
            Product product = productsById.get(item.getProductId());
            if (product == null) {
                continue; // 商品已删/查不到 ⇒ 不因缺数据而收紧用户权益
            }
            if (product.getReturnNoReason() != null && product.getReturnNoReason() == 0) {
                return false;
            }
            if (normalizeReturnWindowDays(product.getReturnWindowDays()) == RETURN_WINDOW_NONE) {
                // 期限为 0 的商品本身就不支持退货，「无理由」自然不适用
                return false;
            }
        }
        return true;
    }

    /**
     * 【M-4 修复】退货期限是否已过 —— 全站唯一口径。
     *
     * <p>本项目里「超期」曾有两处各写一套比较：{@code evaluate} 用
     * {@code now.isAfter(deadline)}（deadline **当天**仍算期内，闭区间），
     * 而客服工单侧（{@link #isOverReturnWindow} 与 ServiceTicketServiceImpl）
     * 用 {@code !now.isBefore(deadline)}（当天即算超期，半开区间）
     * ⇒ 在退货期限第 N 天当天，售后页显示「可申请」、客服页提示「已超期」，
     * 两处互相打架。现统一为 evaluate 的**闭区间**语义（当天仍算期内）。
     *
     * <p>凡判断「超没超期」一律调用本方法，不要再各写比较。
     *
     * @param start      退货期限起算时间（通常是订单完成时间 completeTime）
     * @param windowDays 退货期限天数；{@link #RETURN_WINDOW_NONE} 表示不支持退货（视为已过）
     * @param now        当前时间
     */
    public static boolean isPastReturnDeadline(LocalDateTime start, int windowDays, LocalDateTime now) {
        if (start == null || now == null) {
            return false;
        }
        if (windowDays == RETURN_WINDOW_NONE) {
            return true;
        }
        // 闭区间：严格晚于 deadline 才算超期（deadline 当天仍可申请）
        return now.isAfter(start.plusDays(windowDays));
    }

    /**
     * 订单是否已超出退货期限（供客服工单侧复用同一口径，避免两处各算一遍）。
     *
     * <p>只做「超没超」这一件事，不掺订单状态：订单状态由调用方先行把关
     * （客服工单同样只接受「已完成且已付款」的订单）。
     */
    public static boolean isOverReturnWindow(Order order,
                                             Map<Long, Product> productsById,
                                             LocalDateTime now) {
        if (order == null || order.getId() == null) {
            return false;
        }
        LocalDateTime start = order.getCompleteTime() != null ? order.getCompleteTime() : order.getUpdateTime();
        int windowDays = strictestReturnWindow(order, productsById);
        // 【M-4】统一走 isPastReturnDeadline，与 evaluate 的边界口径保持一致
        return isPastReturnDeadline(start, windowDays, now);
    }

    /** 超出退货期限的天数（未超期返回 0），供客服工单快照「超了多久」。 */
    public static int overdueDays(Order order,
                                  Map<Long, Product> productsById,
                                  LocalDateTime now) {
        if (order == null) {
            return 0;
        }
        LocalDateTime start = order.getCompleteTime() != null ? order.getCompleteTime() : order.getUpdateTime();
        if (start == null || now == null) {
            return 0;
        }
        int windowDays = strictestReturnWindow(order, productsById);
        if (windowDays == RETURN_WINDOW_NONE) {
            return (int) Math.max(0, Duration.between(start, now).toDays());
        }
        long over = Duration.between(start.plusDays(windowDays), now).toDays();
        return (int) Math.max(0, over);
    }

    /**
     * 订单的退货期限 = 订单内所有商品期限的**最小值**（最严的一档）。
     *
     * <p>为何取最小而不是最大：期限是商家对买家的承诺，订单混装时应按
     * 最短承诺执行，否则买家可以在「已超期商品」上借着「未超期商品」的期限蒙混过关。
     *
     * <p>为何要遍历 order.items：调用方（订单列表/详情）已经加载了明细，
     * 不必再逐商品查库；productsById 只是一次批量查询的结果。
     */
    private static int strictestReturnWindow(Order order, Map<Long, Product> productsById) {
        if (order == null || order.getItems() == null || order.getItems().isEmpty()) {
            return DEFAULT_RETURN_WINDOW_DAYS;
        }
        int strictest = Integer.MAX_VALUE;
        boolean found = false;
        for (OrderItem item : order.getItems()) {
            if (item == null || item.getProductId() == null) {
                continue;
            }
            Product product = productsById == null ? null : productsById.get(item.getProductId());
            if (product == null) {
                continue; // 商品缺失不参与收紧，避免「查不到商品」被误判成 0 天
            }
            found = true;
            strictest = Math.min(strictest, normalizeReturnWindowDays(product.getReturnWindowDays()));
        }
        return found ? strictest : DEFAULT_RETURN_WINDOW_DAYS;
    }

    /**
     * 金额合法性：申请退款金额必须 &gt; 0 且不超过订单实付金额（容差 0.01 元）。
     * 换货不涉及退款，金额固定为 0，由调用方直接跳过本校验。
     */
    public static boolean isValidRefundAmount(BigDecimal amount, BigDecimal payAmount) {
        if (amount == null || payAmount == null) {
            return false;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return amount.compareTo(payAmount.add(new BigDecimal("0.01"))) <= 0;
    }

    // ==================== 按商品粒度（P1-6） ====================
    // 【只增不改】以下常量与方法为「按商品售后」新增；既有的时效 / 状态 / 类型 /
    // 原因 / 金额判定逻辑与签名一律未动（AfterSaleRuleTest 43 项零影响）。

    /** 售后范围：0-整单（历史数据 / 缺省，兼容旧语义） */
    public static final int SCOPE_ORDER = 0;

    /** 售后范围：1-按商品（after_sale_item 有明细） */
    public static final int SCOPE_ITEM = 1;

    /**
     * 按商品申请的数量合法性：数量必须 &gt; 0 且不超过对应订单明细的数量。
     *
     * 【「不可超退」也复用本方法】把「本次申请数量 + 该商品已生效售后数量」作为
     * 第一个入参传入即可，语义即 (本次 + 已生效) ≤ order_item.quantity —— 少一个
     * 只有一处使用的私有判定，规则口径仍只有一个出口。
     */
    public static boolean isValidItemQuantity(int claimQuantity, int orderItemQuantity) {
        return claimQuantity > 0 && claimQuantity <= orderItemQuantity;
    }

    /**
     * 按商品申请的退款上限 = Σ(order_item.price × 本次申请数量)。
     * 换货不涉及退款，由调用方跳过。
     *
     * @param claimed       本次申请的商品明细（{@link AfterSaleItemApplyDTO}）
     * @param orderItemMap  order_itemId → 订单明细，由调用方一次性查出（避免 N+1）
     */
    public static BigDecimal maxRefundableForItems(List<AfterSaleItemApplyDTO> claimed,
                                                   Map<Long, OrderItem> orderItemMap) {
        if (claimed == null || claimed.isEmpty() || orderItemMap == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (AfterSaleItemApplyDTO claim : claimed) {
            if (claim == null || claim.getOrderItemId() == null) {
                continue;
            }
            OrderItem orderItem = orderItemMap.get(claim.getOrderItemId());
            if (orderItem == null || orderItem.getPrice() == null) {
                continue;
            }
            int quantity = claim.getQuantity() == null ? 0 : claim.getQuantity();
            if (quantity <= 0) {
                continue;
            }
            total = total.add(orderItem.getPrice().multiply(BigDecimal.valueOf(quantity)));
        }
        return total;
    }
}
