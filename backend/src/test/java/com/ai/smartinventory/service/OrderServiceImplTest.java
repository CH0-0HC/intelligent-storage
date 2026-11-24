package com.ai.smartinventory.service;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.common.PageResult;
import com.ai.smartinventory.common.UserBehaviorRecorder;
import com.ai.smartinventory.dto.AfterSaleEligibility;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.entity.Order;
import com.ai.smartinventory.entity.OrderItem;
import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.mapper.InventoryLogMapper;
import com.ai.smartinventory.mapper.OrderItemMapper;
import com.ai.smartinventory.mapper.OrderMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.UserMapper;
import com.ai.smartinventory.service.impl.OrderServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.context.model.SaResponse;
import cn.dev33.satoken.context.model.SaStorage;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OrderServiceImpl 单元测试（订单域核心契约）
 *
 * 【为什么重点测这三块】
 * 订单域承载了两个「一旦回归就是事故」的安全/资金契约，且都不容易靠手工点测发现：
 *
 * 1. 【P0-1】金额必须由服务端按 product.price 计算。
 *    曾经的实现把 price / amount 直接取自客户端，攻击者传 price=0.01 即可
 *    一分钱买走商品。这个缺陷在正常下单流程里**完全看不出来**
 *    （客户端本来就传真实价格），只有构造恶意请求才暴露 —— 必须用单测锁死。
 *
 * 2. 【多租户】商家视角订单列表的空集合短路。
 *    selectOrderIdsByMerchantId 返回空时若不提前 return，MyBatis-Plus 会拼出
 *    非法的 `IN ()`，该商家订单列表直接 500。
 *
 * 3. 【库存时机】取消订单只回补「确实扣过」的订单。
 *    扣减已改到发货/签收时机，若取消时无条件回补，库存会越取消越多。
 *
 * 【Mockito / MyBatis-Plus 陷阱】见 InventoryServiceImplTest 的详细注释：
 *   - baseMapper 必须用 ReflectionTestUtils 手动注入；
 *   - ServiceImpl.getOne 走的是 selectOne(wrapper, true) 双参重载；
 *   - 纯 Mockito 环境需手动 initTableInfoCache，否则 LambdaQueryWrapper 解析列名抛异常。
 * 本测试同样适用，故在 setUp 内统一处理。
 *
 * 【Sa-Token 陷阱（本类独有）】
 * OrderServiceImpl 内部会调 MerchantContext.currentMerchantId()，
 * 而它第一行就是 StpUtil.isLogin() —— **没有 Servlet 请求上下文时直接抛**
 *   SaTokenContextException: 未能获取有效的上下文处理器
 * （SaHolder → SaTokenContextDefaultImpl.getStorage() 抛）。
 * 这属于「单测环境缺基础设施」，不是业务缺陷。
 * 修法（见下面的 static 初始化块）：
 *   1) SaManager.setSaTokenContext(new SaTokenContextForThreadLocal())  —— 无需 HTTP 的上下文；
 *   2) SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl())            —— 内存 Dao，token 查不到即未登录。
 * 于是 isLogin() 稳定返回 false ⇒ currentMerchantId() 返回 null（全局视图），
 * 正好覆盖「未登录/管理员」这条最常走的分支。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("订单服务单元测试")
class OrderServiceImplTest {

    private static final Long USER_ID = 2L;
    private static final Long PRODUCT_ID = 100L;

    /**
     * 初始化无 HTTP 的 Sa-Token 上下文。
     * 必须在任何 StpUtil 调用之前完成，故用 static 块而非 @BeforeEach。
     */
    @BeforeAll
    static void initSaTokenContext() {
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
    }

    /**
     * 每例重置 ThreadLocal Box。
     *
     * 【为什么还要这一步】
     * SaTokenContextForThreadLocal 只是「门面」，真正的存储在
     * SaTokenContextForThreadLocalStorage 的 ThreadLocal$Box 里；
     * 若 Box 未 set，getStorage() 会走 getBoxNotNull() 抛
     *   SaTokenContextException: 未能获取有效的上下文
     * （上一轮就是卡在这里）。
     * 因此每例必须先 setBox。这里用一个「无 token、空存储」的假上下文，
     * 效果等价于「匿名访客」：isLogin() = false ⇒ MerchantContext 返回 null。
     */
    @BeforeEach
    void resetSaStorageBox() {
        SaTokenContextForThreadLocalStorage.setBox(
                mockRequest(), mockResponse(), new MapBackedSaStorage());
    }

    /** 假请求：任何 header / cookie / param 都取不到 ⇒ 拿不到 token */
    private static SaRequest mockRequest() {
        SaRequest req = mock(SaRequest.class);
        when(req.getHeader(anyString())).thenReturn(null);
        when(req.getCookieValue(anyString())).thenReturn(null);
        when(req.getParam(anyString())).thenReturn(null);
        when(req.getRequestPath()).thenReturn("/");
        return req;
    }

    private static SaResponse mockResponse() {
        return mock(SaResponse.class);
    }

    /** 内存版 SaStorage，供 isLogin() 读取「是否记住我」等标志位 */
    private static final class MapBackedSaStorage implements SaStorage {
        private final Map<String, Object> map = new java.util.HashMap<>();

        @Override public Object getSource() { return map; }
        @Override public Object get(String key) { return map.get(key); }
        @Override public SaStorage set(String key, Object value) { map.put(key, value); return this; }
        @Override public SaStorage delete(String key) { map.remove(key); return this; }
    }

    @AfterEach
    void clearSaStorageBox() {
        // 线程池复用下必须清理，否则残留 Box 会让下一个测试类误判为已登录
        SaTokenContextForThreadLocalStorage.clearBox();
    }

    @Mock private OrderMapper orderMapper;
    @Mock private OrderItemMapper orderItemMapper;
    @Mock private UserMapper userMapper;
    @Mock private InventoryService inventoryService;
    @Mock private CartService cartService;
    @Mock private UserBehaviorRecorder userBehaviorRecorder;
    @Mock private ProductMapper productMapper;
    @Mock private InventoryLogMapper inventoryLogMapper;
    @Mock private AfterSaleService afterSaleService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        // 陷阱 1：baseMapper 由 MyBatis-Plus 注入，@InjectMocks 覆盖不到
        ReflectionTestUtils.setField(orderService, "baseMapper", orderMapper);

        // 陷阱 3：让 LambdaQueryWrapper 能解析列名
        initTableInfoCache(Order.class);
        initTableInfoCache(OrderItem.class);
        initTableInfoCache(InventoryLog.class);
    }

    private void initTableInfoCache(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    entityClass);
        }
    }

    private Product product(Long id, String name, String price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStock(stock);
        p.setImage("/uploads/p" + id + ".jpg");
        p.setDeleted(0);
        return p;
    }

    /** 让 this.save(order) 成功并回填自增 id，模拟 MyBatis-Plus 的 INSERT 行为 */
    private void stubOrderSaveSucceeds() {
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) o.setId(1000L);
            return 1;
        });
    }

    // ==================== 1. P0-1 金额服务端权威 ====================

    @Nested
    @DisplayName("P0-1 下单金额服务端计算")
    class AmountAuthority {

        @Test
        @DisplayName("金额严格按 product.price × quantity 计算，与客户端入参无关")
        void amountFromProductTable() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "测试商品", "199.00", 50));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            Order order = orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 2)),
                    "张三", "13800000000", "北京市朝阳区", null);

            // 199.00 × 2 = 398.00，两个金额字段都必须一致
            assertEquals(0, new BigDecimal("398.00").compareTo(order.getTotalAmount()));
            assertEquals(0, new BigDecimal("398.00").compareTo(order.getPayAmount()));
            // 初始状态：待付款
            assertEquals(0, order.getOrderStatus());
            assertEquals(0, order.getPayStatus());
            assertNotNull(order.getOrderNo());
        }

        @Test
        @DisplayName("订单明细的 price/amount/name/image 全部来自服务端回填")
        void orderItemFieldsComeFromServer() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "权威商品名", "88.50", 10));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 3)),
                    "李四", "13900000000", "上海市浦东新区", "尽快发货");

            // 抓取实际落库的明细，逐字段校验来源
            ArgumentCaptor<OrderItem> captor = ArgumentCaptor.forClass(OrderItem.class);
            verify(orderItemMapper).insert(captor.capture());
            OrderItem saved = captor.getValue();

            assertEquals("权威商品名", saved.getProductName(), "商品名必须来自 product 表");
            assertEquals("/uploads/p100.jpg", saved.getProductImage(), "图片必须来自 product 表");
            assertEquals(0, new BigDecimal("88.50").compareTo(saved.getPrice()));
            assertEquals(3, saved.getQuantity());
            assertEquals(0, new BigDecimal("265.50").compareTo(saved.getAmount()),
                    "小计必须由服务端计算（88.50 × 3）");
        }

        @Test
        @DisplayName("下单不扣库存（扣减延后到发货/签收）")
        void doesNotDeductStockOnCreate() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "商品", "10.00", 100));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 5)),
                    "王五", "13700000000", "广州市天河区", null);

            verify(inventoryService, never()).reduceStock(anyLong(), anyInt(), anyString(), anyLong());
        }

        @Test
        @DisplayName("多商品订单金额累加正确")
        void multiItemTotal() {
            when(productMapper.selectById(1L)).thenReturn(product(1L, "A", "10.00", 100));
            when(productMapper.selectById(2L)).thenReturn(product(2L, "B", "20.50", 100));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            Order order = orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(1L, 2),   // 20.00
                            new OrderService.OrderItemDTO(2L, 3)),  // 61.50
                    "赵六", "13600000000", "深圳市南山区", null);

            assertEquals(0, new BigDecimal("81.50").compareTo(order.getPayAmount()));
        }
    }

    // ==================== 2. 下单入参校验 ====================

    @Nested
    @DisplayName("下单入参校验")
    class InputValidation {

        @Test
        @DisplayName("商品列表为空 → 拒绝")
        void emptyItems() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID, Collections.emptyList(),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("商品列表为 null → 拒绝，不抛 NPE")
        void nullItems() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID, null,
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("productId 为 null → 拒绝")
        void nullProductId() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(null, 1)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("数量为 0 或负数 → 拒绝")
        void invalidQuantity() {
            BusinessException zero = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 0)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, zero.getCode());

            BusinessException neg = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(PRODUCT_ID, -3)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, neg.getCode());
        }

        @Test
        @DisplayName("商品不存在 → 拒绝")
        void productNotFound() {
            when(productMapper.selectById(anyLong())).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(999L, 1)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("商品已软删 → 拒绝（防止下单已下架商品）")
        void productDeleted() {
            Product p = product(PRODUCT_ID, "已下架", "10.00", 100);
            p.setDeleted(1);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(p);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 1)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
        }

        @Test
        @DisplayName("库存不足 → 拒绝（只读可售性校验）")
        void insufficientStock() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "紧俏商品", "10.00", 3));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 10)),
                            "张三", "13800000000", "地址", null));
            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("库存不足"), "实际：" + ex.getMessage());
        }

        @Test
        @DisplayName("数量刚好等于库存 → 放行（边界值不许『等于却失败』）")
        void exactStockBoundary() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "商品", "10.00", 5));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            Order order = assertDoesNotThrow(() -> orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 5)),
                    "张三", "13800000000", "地址", null));
            assertNotNull(order);
        }

        @Test
        @DisplayName("下单失败（save 返回 false）→ 抛 500，不继续写明细")
        void orderSaveFails() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "商品", "10.00", 100));
            when(orderMapper.insert(any(Order.class))).thenReturn(0);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.createOrderWithItems(USER_ID,
                            List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 1)),
                            "张三", "13800000000", "地址", null));
            assertEquals(500, ex.getCode());
            verify(orderItemMapper, never()).insert(any(OrderItem.class));
        }
    }

    // ==================== 3. 购物车精确清理（数据丢失修复）====================

    @Nested
    @DisplayName("购物车精确清理")
    class CartCleanup {

        @Test
        @DisplayName("只移除本单购买的商品，不动未勾选项")
        void removeOnlyOrderedProducts() {
            when(productMapper.selectById(1L)).thenReturn(product(1L, "A", "10.00", 100));
            when(productMapper.selectById(2L)).thenReturn(product(2L, "B", "10.00", 100));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(1L, 1),
                            new OrderService.OrderItemDTO(2L, 1)),
                    "张三", "13800000000", "地址", null);

            // 必须按 productId 精确移除，且不得调用「整单清空」
            ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
            verify(cartService).removeByProductIds(eq(USER_ID), captor.capture());
            assertEquals(2, captor.getValue().size());
            assertTrue(captor.getValue().containsAll(List.of(1L, 2L)));
        }

        @Test
        @DisplayName("同一商品多行时去重，避免重复清理")
        void distinctProductIds() {
            when(productMapper.selectById(1L)).thenReturn(product(1L, "A", "10.00", 100));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(1L, 1),
                            new OrderService.OrderItemDTO(1L, 2)),
                    "张三", "13800000000", "地址", null);

            ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
            verify(cartService).removeByProductIds(eq(USER_ID), captor.capture());
            assertEquals(1, captor.getValue().size(), "同一 productId 应去重");
        }
    }

    // ==================== 4. 多租户隔离 ====================

    @Nested
    @DisplayName("多租户隔离")
    class TenantIsolation {

        @Test
        @DisplayName("未登录（无 merchantId）→ 按全局视图分页，不拼 IN () 子句")
        void noMerchantScopeFallsBackToGlobalPage() {
            // 说明：MerchantContext.currentMerchantId() 依赖 Sa-Token 会话，
            // 纯单测里 isLogin() 恒为 false ⇒ 返回 null（admin/user/未登录 = 全局视图）。
            // 真正的「商家空订单短路」分支需要登录态，属集成测试范畴（见 verify-*.mjs）。
            // 这里锁定的是「未登录不走 IN () 也不炸」这条基础路径。
            Page<Order> emptyPage = new Page<>(1, 10);
            emptyPage.setTotal(0);
            emptyPage.setRecords(Collections.emptyList());
            when(orderMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(emptyPage);

            PageResult<Order> result = assertDoesNotThrow(() -> orderService.getOrderPage(USER_ID, 1, 10, null));

            assertNotNull(result);
            assertTrue(result.getRecords().isEmpty());
            assertEquals(0L, result.getTotal());
            // 未登录场景一律按 userId 过滤，不会退化成全表查询
            verify(orderMapper).selectPage(any(Page.class), any(Wrapper.class));
            // 未登录时绝不会去查商家归属，更不会拼 IN ()
            verify(orderMapper, never()).selectOrderIdsByMerchantId(anyLong());
        }

        @Test
        @DisplayName("订单列表为每个订单回填售后入口状态")
        void fillsAfterSaleEligibility() {
            Order o1 = new Order();
            o1.setId(1L);
            o1.setUserId(USER_ID);
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(1);
            page.setRecords(List.of(o1));

            when(orderMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

            AfterSaleEligibility elig = new AfterSaleEligibility();
            elig.setEligible(false);
            elig.setCode("NOT_RECEIVED");
            when(afterSaleService.evaluateBatch(anyList())).thenReturn(Map.of(1L, elig));

            PageResult<Order> result = orderService.getOrderPage(USER_ID, 1, 10, null);

            assertEquals(1, result.getRecords().size());
            assertNotNull(result.getRecords().get(0).getAfterSale(),
                    "前端入口完全依赖该字段，漏回填会导致按钮不出现");
            assertEquals("NOT_RECEIVED", result.getRecords().get(0).getAfterSale().getCode());
        }
    }

    // ==================== 5. 订单访问权限 ====================

    @Nested
    @DisplayName("订单访问权限")
    class AccessControl {

        @Test
        @DisplayName("管理员 → 任意订单可访问")
        void adminCanAccessAny() {
            Order o = new Order();
            o.setId(1L);
            o.setUserId(999L); // 别人的订单
            when(orderMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(o);
            when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(o);

            assertNotNull(orderService.getOrderByIdWithPermission(1L, 1L, "admin"));
        }

        @Test
        @DisplayName("商家无权访问不含自己商品的订单 → null（防个人信息泄露）")
        void merchantCannotAccessOthersOrder() {
            Order o = new Order();
            o.setId(1L);
            o.setUserId(999L);
            when(orderMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(o);
            when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(o);
            // 未登录时 MerchantContext.currentMerchantId() 返回 null → 直接拒绝
            when(orderMapper.countOrderOwnedByMerchant(anyLong(), anyLong())).thenReturn(0L);

            assertNull(orderService.getOrderByIdWithPermission(1L, 1L, "merchant"),
                    "商家拿不到 merchantId 时必须拒绝，不能放行");
        }

        @Test
        @DisplayName("普通用户访问自己的订单 → 通过")
        void userCanAccessOwnOrder() {
            Order o = new Order();
            o.setId(1L);
            o.setUserId(USER_ID);
            when(orderMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(o);
            when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(o);

            assertNotNull(orderService.getOrderByIdWithPermission(1L, USER_ID, "user"));
        }

        @Test
        @DisplayName("普通用户访问他人订单 → null（横向越权防护）")
        void userCannotAccessOthersOrder() {
            Order o = new Order();
            o.setId(1L);
            o.setUserId(999L);
            when(orderMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(o);
            when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(o);

            assertNull(orderService.getOrderByIdWithPermission(1L, USER_ID, "user"));
        }

        @Test
        @DisplayName("订单不存在 → null")
        void orderNotFound() {
            when(orderMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
            when(orderMapper.selectOne(any(Wrapper.class))).thenReturn(null);

            assertNull(orderService.getOrderByIdWithPermission(999L, USER_ID, "user"));
        }

        @Test
        @DisplayName("isOrderOwnedByMerchant：入参含 null 一律 false，不查库")
        void ownedByMerchantNullGuard() {
            assertFalse(orderService.isOrderOwnedByMerchant(null, 1L));
            assertFalse(orderService.isOrderOwnedByMerchant(1L, null));
            verify(orderMapper, never()).countOrderOwnedByMerchant(anyLong(), anyLong());
        }
    }

    // ==================== 6. 取消订单的条件回补 ====================

    @Nested
    @DisplayName("取消订单库存回补")
    class CancelRestock {

        private Order order(Long id) {
            Order o = new Order();
            o.setId(id);
            o.setOrderNo("NO" + id);
            o.setOrderStatus(1); // 已付款未发货
            return o;
        }

        @Test
        @DisplayName("【扣过才回补】已扣减的订单取消 → 逐项回补库存")
        void restockWhenDeducted() {
            Order o = order(1L);
            OrderItem i1 = new OrderItem();
            i1.setOrderId(1L);
            i1.setProductId(10L);
            i1.setQuantity(2);
            i1.setProductName("A");
            OrderItem i2 = new OrderItem();
            i2.setOrderId(1L);
            i2.setProductId(20L);
            i2.setQuantity(3);
            i2.setProductName("B");

            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(i1, i2));
            // 库存流水里存在本订单的出库记录 → 说明已扣过
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
            // 【S-4】原子占位（条件更新）返回 1 → 本线程取得「取消权」
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

            orderService.cancelOrder(o);

            verify(inventoryService).addStock(10L, 2, "cancel", 1L);
            verify(inventoryService).addStock(20L, 3, "cancel", 1L);
            assertEquals(4, o.getOrderStatus(), "订单状态必须置为已取消");
            assertNotNull(o.getCancelTime());
        }

        @Test
        @DisplayName("【关键】未扣减的订单取消 → 绝不回补（否则库存凭空增加）")
        void noRestockWhenNotDeducted() {
            Order o = order(2L);
            OrderItem item = new OrderItem();
            item.setOrderId(2L);
            item.setProductId(10L);
            item.setQuantity(5);
            item.setProductName("A");

            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
            // 流水查询返回 0 → 从未扣减（扣减已延后到发货/签收）
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            // 【S-4】原子占位返回 1 → 取得取消权（随后因「未扣过」而不回补）
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

            orderService.cancelOrder(o);

            verify(inventoryService, never()).addStock(anyLong(), anyInt(), anyString(), anyLong());
            assertEquals(4, o.getOrderStatus(), "状态仍要正常置为已取消");
        }

        @Test
        @DisplayName("已取消的订单重复取消 → 幂等，不做任何操作")
        void alreadyCancelledIsIdempotent() {
            Order o = order(3L);
            o.setOrderStatus(4); // 已取消

            // 【S-4】已取消订单不满足「状态 ∈ {0,1,2}」的原子占位条件 → 影响行数 0
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(0);

            orderService.cancelOrder(o);

            // 占位尝试过，但条件不满足、未取得取消权 → 不做任何后续操作（幂等）
            verify(orderMapper, never()).updateById(any(Order.class));
            verify(orderItemMapper, never()).selectList(any(Wrapper.class));
            verify(inventoryService, never()).addStock(anyLong(), anyInt(), anyString(), anyLong());
        }
    }

    // ==================== 7. 发货/签收扣减的幂等 ====================

    @Nested
    @DisplayName("订单扣减库存的幂等")
    class DeductIdempotency {

        @Test
        @DisplayName("已有出库流水 → 直接跳过，不重复扣减")
        void skipWhenAlreadyDeducted() {
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

            orderService.deductStockForOrder(1L);

            verify(orderItemMapper, never()).selectList(any(Wrapper.class));
            verify(inventoryService, never()).reduceStock(anyLong(), anyInt(), anyString(), anyLong());
        }

        @Test
        @DisplayName("orderId 为 null → 直接返回")
        void nullOrderId() {
            orderService.deductStockForOrder(null);

            verify(inventoryLogMapper, never()).selectCount(any(Wrapper.class));
        }

        @Test
        @DisplayName("无订单明细 → 直接返回，不发起扣减")
        void noItems() {
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

            orderService.deductStockForOrder(1L);

            verify(inventoryService, never()).reduceStock(anyLong(), anyInt(), anyString(), anyLong());
        }

        @Test
        @DisplayName("正常扣减 → 逐项调用 reduceStock")
        void deductEachItem() {
            OrderItem item = new OrderItem();
            item.setOrderId(1L);
            item.setProductId(10L);
            item.setQuantity(2);
            item.setProductName("A");

            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
            when(inventoryService.reduceStock(10L, 2, "order", 1L)).thenReturn(true);

            orderService.deductStockForOrder(1L);

            verify(inventoryService).reduceStock(10L, 2, "order", 1L);
        }

        @Test
        @DisplayName("库存不足 → 抛出带商品名的业务异常，保证事务整体回滚")
        void insufficientStockWrapsWithProductName() {
            OrderItem item = new OrderItem();
            item.setOrderId(1L);
            item.setProductId(10L);
            item.setQuantity(99);
            item.setProductName("紧俏商品");

            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));
            when(inventoryService.reduceStock(anyLong(), anyInt(), anyString(), anyLong()))
                    .thenThrow(new BusinessException(400, "库存不足"));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> orderService.deductStockForOrder(1L));

            assertEquals(400, ex.getCode());
            assertTrue(ex.getMessage().contains("紧俏商品"),
                    "异常文案必须指出是哪个商品，否则商家无从排查。实际：" + ex.getMessage());
        }
    }

    // ==================== 8. 行为埋点 ====================

    @Nested
    @DisplayName("R18 下单行为埋点")
    class BehaviorRecording {

        @Test
        @DisplayName("每个明细记一条 purchase 行为（协同过滤唯一数据来源）")
        void recordPurchasePerItem() {
            when(productMapper.selectById(1L)).thenReturn(product(1L, "A", "10.00", 100));
            when(productMapper.selectById(2L)).thenReturn(product(2L, "B", "10.00", 100));
            stubOrderSaveSucceeds();
            when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);

            orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(1L, 1),
                            new OrderService.OrderItemDTO(2L, 1)),
                    "张三", "13800000000", "地址", null);

            verify(userBehaviorRecorder).record(USER_ID, UserBehaviorRecorder.TYPE_PURCHASE, 1L);
            verify(userBehaviorRecorder).record(USER_ID, UserBehaviorRecorder.TYPE_PURCHASE, 2L);
        }

        @Test
        @DisplayName("订单创建失败时不产生任何行为埋点")
        void noRecordingWhenOrderFails() {
            when(productMapper.selectById(PRODUCT_ID))
                    .thenReturn(product(PRODUCT_ID, "商品", "10.00", 100));
            when(orderMapper.insert(any(Order.class))).thenReturn(0);

            assertThrows(BusinessException.class, () -> orderService.createOrderWithItems(USER_ID,
                    List.of(new OrderService.OrderItemDTO(PRODUCT_ID, 1)),
                    "张三", "13800000000", "地址", null));

            verify(userBehaviorRecorder, never()).record(anyLong(), anyString(), anyLong());
        }
    }

    // ==================== 9. 未付款超时回收（2026-10-09） ====================

    /**
     * 【这一段锁的是什么】
     * 「下单后 15 分钟未付款自动取消」这条链路由两块拼成：
     *   · {@code listTimeoutUnpaidOrders}：捞出**该被取消**的订单（只读，不越界）；
     *   · {@code cancelOrder(order, reason)}：真正取消并**打上原因标记**。
     * 前者一旦条件写宽（例如漏掉 order_status = 0），就会把已付款待发货的单子
     * 也捞出来；后者一旦漏写 cancel_reason，前端就无法区分「系统超时取消」与
     * 「用户自己取消」，会把用户亲手点的取消提示成系统行为。
     */
    @Nested
    @DisplayName("未付款超时回收")
    class TimeoutCancel {

        private Order pendingOrder(Long id) {
            Order o = new Order();
            o.setId(id);
            o.setOrderNo("NO" + id);
            o.setOrderStatus(0); // 待付款
            o.setPayStatus(0);   // 未付款
            o.setCreateTime(LocalDateTime.of(2026, 10, 9, 10, 0, 0));
            return o;
        }

        @Test
        @DisplayName("超时单查询：条件必须同时限定「待付款 + 未付款 + 创建时间早于截止点」")
        void timeoutQueryConditions() {
            when(orderMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());

            orderService.listTimeoutUnpaidOrders(LocalDateTime.of(2026, 10, 9, 10, 15, 0));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(orderMapper).selectList(captor.capture());

            LambdaQueryWrapper<Order> w = (LambdaQueryWrapper<Order>) captor.getValue();
            String sql = w.getSqlSegment();
            assertTrue(sql.contains("order_status"), "必须限定订单状态，否则已付款单会被一起取消");
            assertTrue(sql.contains("pay_status"), "必须限定付款状态，否则付过款的单会被取消");
            assertTrue(sql.contains("create_time"), "必须限定创建时间，否则刚下的单会被立刻取消");
            // 截止点必须以参数形式传入，而不是被写死在 SQL 里
            assertTrue(w.getParamNameValuePairs().containsValue(LocalDateTime.of(2026, 10, 9, 10, 15, 0)));
            // 待付款状态、未付款状态两个值都必须在
            assertTrue(w.getParamNameValuePairs().containsValue(0));
        }

        @Test
        @DisplayName("deadline 为 null → 返回空列表，绝不退化成全表查询")
        void nullDeadlineReturnsEmpty() {
            assertTrue(orderService.listTimeoutUnpaidOrders(null).isEmpty());
            verify(orderMapper, never()).selectList(any(Wrapper.class));
        }

        @Test
        @DisplayName("取消时写入取消原因，供前端区分「系统超时」与「用户主动」")
        void writesCancelReason() {
            Order o = pendingOrder(1L);
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

            orderService.cancelOrder(o, OrderService.CANCEL_REASON_TIMEOUT);

            assertEquals(4, o.getOrderStatus());
            assertEquals(OrderService.CANCEL_REASON_TIMEOUT, o.getCancelReason());

            // 落库的 SET 子句里必须真的带上 cancel_reason，而不只是改了内存对象
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(orderMapper).update(any(), captor.capture());
            LambdaUpdateWrapper<Order> uw = (LambdaUpdateWrapper<Order>) captor.getValue();
            assertTrue(uw.getSqlSet().contains("cancel_reason"),
                    "本地改了原因但没写库，刷新后前端就分不清是谁取消的。实际 SET：" + uw.getSqlSet());
            assertTrue(uw.getParamNameValuePairs().containsValue(OrderService.CANCEL_REASON_TIMEOUT));
        }

        @Test
        @DisplayName("旧签名 cancelOrder(order) 等价于「用户主动取消」，原因不得为空")
        void legacySignatureDefaultsToUser() {
            Order o = pendingOrder(2L);
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

            orderService.cancelOrder(o);

            assertEquals(OrderService.CANCEL_REASON_USER, o.getCancelReason());
        }

        @Test
        @DisplayName("待付款订单回填付款截止时间 = 创建时间 + 超时分钟数")
        void fillsPayDeadline() {
            Order o = pendingOrder(3L);
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(1);
            page.setRecords(List.of(o));
            when(orderMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(afterSaleService.evaluateBatch(anyList())).thenReturn(Collections.emptyMap());

            orderService.getOrderPage(USER_ID, 1, 10, null);

            // 单测环境没有 Spring 容器，@Value 不生效，字段用默认值 15 兜底
            assertEquals(LocalDateTime.of(2026, 10, 9, 10, 15, 0), o.getPayDeadline());
        }

        @Test
        @DisplayName("非待付款订单不回填截止时间（前端据此不渲染倒计时）")
        void noDeadlineForPaidOrders() {
            Order paid = pendingOrder(4L);
            paid.setOrderStatus(1);
            paid.setPayStatus(1);
            Page<Order> page = new Page<>(1, 10);
            page.setTotal(1);
            page.setRecords(List.of(paid));
            when(orderMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(afterSaleService.evaluateBatch(anyList())).thenReturn(Collections.emptyMap());

            orderService.getOrderPage(USER_ID, 1, 10, null);

            assertNull(paid.getPayDeadline());
        }
    }

    // ==================== 10. 付款与取消的并发闸门（S-5） ====================

    /**
     * 【这一段锁的是什么】
     * 「付款」与「超时取消」曾是两条**都没有数据库闸门**的路径：
     *   · 付款 = 先读订单 → 内存里判 orderStatus == 0 → set 后 updateById 整实体写回；
     *   · 超时取消 = 一律用 IN (0,1,2)。
     * 于是两个方向都会产出不一致的订单：
     *   · 取消先落地 → 付款把已取消的单无条件写回 1（幽灵单，且从未扣库存）；
     *   · 付款先落地 → 超时取消的 IN (0,1,2) 依然命中，付过款的单被标成已取消。
     * 这里锁死的是**条件更新的 WHERE 内容** —— 内存判断、错误文案都不是防线，
     * 只有 WHERE 里的谓词才是；谓词被改宽或改没，这些断言会立刻变红。
     */
    @Nested
    @DisplayName("付款与取消的并发闸门（S-5）")
    class PaymentRaceGuard {

        private Order orderWithStatus(Long id, int status, Integer payStatus) {
            Order o = new Order();
            o.setId(id);
            o.setOrderNo("NO" + id);
            o.setOrderStatus(status);
            o.setPayStatus(payStatus);
            return o;
        }

        @SuppressWarnings("unchecked")
        private LambdaUpdateWrapper<Order> captureUpdateWrapper() {
            ArgumentCaptor<Wrapper<Order>> captor = ArgumentCaptor.forClass(Wrapper.class);
            verify(orderMapper).update(any(), captor.capture());
            return (LambdaUpdateWrapper<Order>) captor.getValue();
        }

        @Test
        @DisplayName("markPaid 的 WHERE 必须钉死「当前仍是待付款(0)」，SET 必须同事务落三个字段")
        void markPaidPinsStatusZero() {
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);

            assertTrue(orderService.markPaid(1L));

            LambdaUpdateWrapper<Order> uw = captureUpdateWrapper();
            String where = uw.getSqlSegment();
            assertTrue(where.contains("order_status"),
                    "WHERE 必须限定 order_status，否则已取消(4)/已发货(2)的订单会被写成已付款。实际：" + where);

            String set = uw.getSqlSet();
            assertTrue(set.contains("order_status"), "SET 必须推进 order_status：" + set);
            assertTrue(set.contains("pay_status"), "SET 必须落 pay_status：" + set);
            assertTrue(set.contains("pay_time"), "SET 必须落 pay_time：" + set);
        }

        @Test
        @DisplayName("markPaid 拿不到闸门（affected=0）→ 返回 false，交调用方拒绝而不是静默成功")
        void markPaidReturnsFalseWhenClaimLost() {
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(0);

            assertFalse(orderService.markPaid(1L));
        }

        @Test
        @DisplayName("markPaid 传 null id → false，且不发任何 SQL")
        void markPaidNullIdIsSafe() {
            assertFalse(orderService.markPaid(null));
            verify(orderMapper, never()).update(any(), any(Wrapper.class));
        }

        @Test
        @DisplayName("超时取消的 WHERE 必须同时钉死「待付款」与「未付款」，否则刚付过款的单会被误杀")
        void timeoutCancelRequiresUnpaid() {
            Order o = orderWithStatus(1L, 0, 0);
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

            orderService.cancelOrder(o, OrderService.CANCEL_REASON_TIMEOUT);

            String where = captureUpdateWrapper().getSqlSegment();
            assertTrue(where.contains("order_status"),
                    "超时取消必须限定订单状态：" + where);
            assertTrue(where.contains("pay_status"),
                    "超时取消必须限定付款状态 —— 漏了它，扫描与取消之间完成的付款会被标成已取消。实际：" + where);
        }

        @Test
        @DisplayName("用户/商家主动取消不加付款闸门（否则无法关闭已付款待发货的订单）")
        void manualCancelKeepsWideGate() {
            Order o = orderWithStatus(2L, 1, 1);
            when(orderMapper.update(any(), any(Wrapper.class))).thenReturn(1);
            when(orderItemMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
            when(inventoryLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

            orderService.cancelOrder(o, OrderService.CANCEL_REASON_USER);

            String where = captureUpdateWrapper().getSqlSegment();
            assertTrue(where.contains("order_status"),
                    "主动取消仍要限定状态：" + where);
            assertFalse(where.contains("pay_status"),
                    "主动取消不该限定付款状态 —— 否则商家关不掉已付款待发货的订单。实际：" + where);
        }
    }
}
