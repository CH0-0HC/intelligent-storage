package com.ai.smartinventory.service;

import com.ai.smartinventory.common.BusinessException;
import com.ai.smartinventory.entity.Inventory;
import com.ai.smartinventory.entity.InventoryLog;
import com.ai.smartinventory.mapper.InventoryMapper;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.service.impl.InventoryServiceImpl;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InventoryServiceImpl 单元测试（原子扣减模型）
 *
 * 【为什么重写】
 * 原测试是按「读-改-写」旧实现写的：断言内存对象被 setQuantity 修改、校验 updateById。
 * 库存逻辑本次被改造为「数据库层条件更新 + 受影响行数判断」（防超卖核心），
 * 契约已变，测试必须同步改造，否则测试会「正确地失败」——这点必须说明清楚，
 * 不能让后人误以为是业务代码坏了。
 *
 * 新契约：
 *   - 扣减成功 ⇔ deductStock 返回 affectedRows = 1；
 *   - 扣减失败 ⇔ deductStock 返回 0，必须抛 BusinessException(400)，且不得写流水；
 *   - 数量非法（<= 0）在进入任何 SQL 之前就被拒绝。
 *
 * 【两个必须知道的 Mockito/MyBatis-Plus 陷阱】
 * 1) ServiceImpl.baseMapper 由 MyBatis-Plus 注入，@InjectMocks 覆盖不到，
 *    必须用 ReflectionTestUtils 手动反射注入，否则报 "baseMapper can not be null"。
 * 2) ServiceImpl.getOne(wrapper) 在 3.5.x 中调用的**不是** selectOne(wrapper)，
 *    而是双参重载 selectOne(wrapper, boolean throwEx)，即
 *    ServiceImpl.java:238 -> baseMapper.selectOne(wrapper, true)。
 *    若桩只打在单参 selectOne 上，永远匹配不到，getOne 会返回 null，
 *    表现为「明明打桩了却查不到库存」。这一点是从 surefire 的 mock 交互日志
 *    （selectOne(wrapper, true)）反查出来的，极易踩坑，故在此显式记录。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("库存服务单元测试（原子扣减）")
class InventoryServiceImplTest {

    private static final Long PRODUCT_ID = 100L;

    @Mock
    private InventoryMapper inventoryMapper;

    @Mock
    private InventoryLogService inventoryLogService;

    @Mock
    private ProductService productService;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private Inventory testInventory;

    @BeforeEach
    void setUp() {
        // 陷阱 1：手动注入 baseMapper
        ReflectionTestUtils.setField(inventoryService, "baseMapper", inventoryMapper);

        testInventory = new Inventory();
        testInventory.setId(1L);
        testInventory.setProductId(PRODUCT_ID);
        testInventory.setQuantity(50);
        testInventory.setWarningQuantity(10);
        testInventory.setDeleted(0);
        testInventory.setLastInTime(LocalDateTime.now());

        // 方法末尾的私有 syncProductStock() 会再查一次库存并可能回写 product。
        // 让 productMapper.selectById 返回 null，使回写短路，
        // 既不干扰库存读取的桩，也不需要为 product 造数据。
        when(productMapper.selectById(anyLong())).thenReturn(null);

        // 【陷阱 3】纯 Mockito 单测没有 Spring 容器，MyBatis-Plus 的 TableInfoHelper
        // 不会自动初始化 lambda 列名缓存。而 ServiceImpl 内部使用了
        // new LambdaUpdateWrapper<Inventory>().set(Inventory::getLastOutTime, ...)，
        // 该语句在运行期需要把 lambda 解析成列名，会走
        // AbstractLambdaWrapper.tryInitCache -> getColumnCache，
        // 缓存缺失时直接抛
        //   MybatisPlusException: can not find lambda cache for this entity [Inventory]。
        // 这里手动初始化一次实体元数据，为 lambda 解析提供缓存。
        initTableInfoCache(Inventory.class);
        initTableInfoCache(InventoryLog.class);
    }

    /**
     * 手动注册实体元数据，等价于 Spring 环境下 Mapper 扫描时做的事情，
     * 目的是让 LambdaUpdateWrapper 的列名解析能找到缓存。
     */
    private void initTableInfoCache(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    entityClass);
        }
    }

    /**
     * 桩：库存记录存在（quantity = 50）。
     *
     * 关键：getOne 走的是 selectOne(wrapper, true) 双参重载（见类注释陷阱 2），
     * 同时保险起见把单参重载也打上，避免 MyBatis-Plus 内部实现差异导致漏桩。
     *
     * 【P0-2 修复后的契约变化】reduceStock / addStock 现在会**读两次**：
     *   第 1 次在原子 UPDATE 之前（判存在），第 2 次在 UPDATE 之后（回读真实值写流水）。
     * 因此桩必须能按调用次序返回不同的值，否则第二次读仍拿到旧快照，
     * 流水区间就是错的 —— 那正是被修复的 bug 本身，测试不能再假定旧行为。
     */
    private void stubExistingInventory() {
        when(inventoryMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(testInventory);
        when(inventoryMapper.selectOne(any(Wrapper.class))).thenReturn(testInventory);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(testInventory));
    }

    /**
     * 桩：库存记录存在，且**第 2 次 selectOne 返回扣减后的真实值**。
     * 用于出库路径（reduceStock）：第 1 次读 50，扣减 qty 后第 2 次读 (50 - qty)。
     */
    private void stubExistingInventorySequenced(int afterQuantity) {
        Inventory after = new Inventory();
        after.setId(1L);
        after.setProductId(PRODUCT_ID);
        after.setQuantity(afterQuantity);
        after.setWarningQuantity(10);
        after.setDeleted(0);
        // 第 1 次 → 扣减前；第 2 次 → 扣减后
        when(inventoryMapper.selectOne(any(Wrapper.class), anyBoolean()))
                .thenReturn(testInventory, after);
        when(inventoryMapper.selectOne(any(Wrapper.class)))
                .thenReturn(testInventory, after);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(testInventory));
    }

    /**
     * 桩：库存记录存在，且**第 2 次 selectOne 返回入库后的真实值**。
     * 用于入库路径（addStock）：第 1 次读 50，入库 qty 后第 2 次读 (50 + qty)。
     */
    private void stubExistingInventoryAfterAdd(int afterQuantity) {
        Inventory after = new Inventory();
        after.setId(1L);
        after.setProductId(PRODUCT_ID);
        after.setQuantity(afterQuantity);
        after.setWarningQuantity(10);
        after.setDeleted(0);
        when(inventoryMapper.selectOne(any(Wrapper.class), anyBoolean()))
                .thenReturn(testInventory, after);
        when(inventoryMapper.selectOne(any(Wrapper.class)))
                .thenReturn(testInventory, after);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(testInventory));
    }

    /**
     * 桩：库存记录不存在。
     */
    private void stubMissingInventory() {
        when(inventoryMapper.selectOne(any(Wrapper.class), anyBoolean())).thenReturn(null);
        when(inventoryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(Collections.emptyList());
    }

    // ==================== 入库 ====================

    @Test
    @DisplayName("入库 - 已有库存记录：走原子 increaseStock")
    void testAddStockExistingInventory() {
        // 【P0-2】入库也会读两次：UPDATE 前 50、UPDATE 后 80
        stubExistingInventoryAfterAdd(80);
        when(inventoryMapper.increaseStock(PRODUCT_ID, 30)).thenReturn(1);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        boolean result = inventoryService.addStock(PRODUCT_ID, 30, "purchase", 1L);

        assertTrue(result);
        verify(inventoryMapper).increaseStock(PRODUCT_ID, 30);
        // 流水数量必须与本次入库量一致，类型 0 = 入库
        verify(inventoryLogService).save(argThat(log ->
                log.getType() == 0 && log.getQuantity() == 30));
    }

    @Test
    @DisplayName("入库 - 无库存记录：首次建账走 insert，不走 increaseStock")
    void testAddStockNewInventory() {
        stubMissingInventory();
        when(inventoryMapper.insert(any(Inventory.class))).thenReturn(1);

        boolean result = inventoryService.addStock(PRODUCT_ID, 50, "purchase", 1L);

        assertTrue(result);
        verify(inventoryMapper).insert(any(Inventory.class));
        // 首次入库不应触发原子的增量 UPDATE
        verify(inventoryMapper, never()).increaseStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("入库 - 数量为 0 应被拒绝（不产生任何 SQL）")
    void testAddStockZeroQuantity() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.addStock(PRODUCT_ID, 0, "adjustment", null));

        assertEquals(400, ex.getCode());
        verify(inventoryMapper, never()).increaseStock(anyLong(), anyInt());
        verify(inventoryMapper, never()).insert(any(Inventory.class));
    }

    @Test
    @DisplayName("入库 - 数量为负应被拒绝")
    void testAddStockNegativeQuantity() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.addStock(PRODUCT_ID, -5, "adjustment", null));

        assertEquals(400, ex.getCode());
        verify(inventoryMapper, never()).increaseStock(anyLong(), anyInt());
    }

    // ==================== 出库（防超卖核心）====================

    @Test
    @DisplayName("出库 - 正常扣减：原子 SQL 返回 1 即成功")
    void testReduceStockSuccess() {
        // 【P0-2】第 1 次读 50，扣 10 后第 2 次读 40
        stubExistingInventorySequenced(40);
        when(inventoryMapper.deductStock(PRODUCT_ID, 10)).thenReturn(1);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        boolean result = inventoryService.reduceStock(PRODUCT_ID, 10, "order", 2L);

        assertTrue(result);
        verify(inventoryMapper).deductStock(PRODUCT_ID, 10);
        verify(inventoryLogService).save(argThat(log ->
                log.getType() == 1 && log.getQuantity() == 10));
    }

    @Test
    @DisplayName("出库 - 库存不足：原子 SQL 返回 0 必须抛业务异常且不写流水")
    void testReduceStockInsufficient() {
        stubExistingInventory();
        // 模拟并发下已被他人扣走 / 库存本来就不够
        when(inventoryMapper.deductStock(PRODUCT_ID, 100)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reduceStock(PRODUCT_ID, 100, "order", 2L));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("库存不足"),
                "异常文案应说明库存不足，实际为：" + ex.getMessage());
        // 关键：失败时绝不能写库存流水（事务回滚前的最后一道防线）
        verify(inventoryLogService, never()).save(any(InventoryLog.class));
    }

    @Test
    @DisplayName("出库 - 库存记录不存在：直接拒绝，不发起扣减 SQL")
    void testReduceStockInventoryNotFound() {
        stubMissingInventory();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reduceStock(999L, 10, "order", 2L));

        assertTrue(ex.getMessage().contains("不存在"));
        verify(inventoryMapper, never()).deductStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("出库 - 数量刚好等于库存：应当成功（边界值不许出现「等于却失败」）")
    void testReduceStockExactQuantity() {
        // 【P0-2】第 1 次读 50，扣 50 后第 2 次读 0
        stubExistingInventorySequenced(0);
        when(inventoryMapper.deductStock(PRODUCT_ID, 50)).thenReturn(1);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        boolean result = inventoryService.reduceStock(PRODUCT_ID, 50, "order", 2L);

        assertTrue(result);
        verify(inventoryMapper).deductStock(PRODUCT_ID, 50);
        // 边界扣减后剩余 0，流水后置数量必须是 0 而不是负数
        verify(inventoryLogService).save(argThat(log -> log.getAfterQuantity() == 0));
    }

    @Test
    @DisplayName("出库 - 数量为 0 应被拒绝")
    void testReduceStockZeroQuantity() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reduceStock(PRODUCT_ID, 0, "order", 2L));

        assertEquals(400, ex.getCode());
        verify(inventoryMapper, never()).deductStock(anyLong(), anyInt());
    }

    // ==================== 并发防超卖语义验证 ====================

    @Test
    @DisplayName("并发防超卖 - 库存 50、两请求各扣 30：只有第一个成功，第二个必须失败")
    void testConcurrencyOnlyOneSucceeds() {
        // 两个请求都会先读到库存 50 —— 这正是旧「读-改-写」实现会超卖的场景
        // 【P0-2】每次 reduceStock 会读两次（UPDATE 前判存在、UPDATE 后回读真实值）。
        //   第 1 个请求：读 50 → 扣 30 → 读 20（成功）
        //   第 2 个请求：读 50（并发下的旧快照）→ deductStock 返回 0 → 抛异常，不再回读
        Inventory afterFirst = new Inventory();
        afterFirst.setId(1L);
        afterFirst.setProductId(PRODUCT_ID);
        afterFirst.setQuantity(20);
        afterFirst.setDeleted(0);
        when(inventoryMapper.selectOne(any(Wrapper.class), anyBoolean()))
                .thenReturn(testInventory, afterFirst, testInventory);
        when(inventoryMapper.selectOne(any(Wrapper.class)))
                .thenReturn(testInventory, afterFirst, testInventory);
        when(inventoryMapper.selectList(any(Wrapper.class))).thenReturn(List.of(testInventory));
        // 数据库行锁保证只有一个 UPDATE 能命中 quantity >= 30
        when(inventoryMapper.deductStock(PRODUCT_ID, 30)).thenReturn(1, 0);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        // 第一个请求成功
        assertTrue(inventoryService.reduceStock(PRODUCT_ID, 30, "order", 1L));

        // 第二个请求被数据库层拦下，抛异常 → 整个下单事务回滚
        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reduceStock(PRODUCT_ID, 30, "order", 2L));
        assertEquals(400, ex.getCode());

        // 整个过程中只应产生一条库存流水
        verify(inventoryLogService, times(1)).save(any(InventoryLog.class));
    }

    @Test
    @DisplayName("【P0-2】出库流水区间取自扣减后的真实值，而非扣减前的旧快照")
    void testReduceStockLogUsesTrueAfterValue() {
        // 第 1 次读 50（旧快照），扣 10 后第 2 次读 40（真实值）
        stubExistingInventorySequenced(40);
        when(inventoryMapper.deductStock(PRODUCT_ID, 10)).thenReturn(1);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        inventoryService.reduceStock(PRODUCT_ID, 10, "order", 2L);

        // 流水必须记 before=50 / after=40 —— 修复前 after 会用旧快照算成 40 也对，
        // 但 before 会错误地取旧值；关键是 after 必须与库存表真实值一致。
        verify(inventoryLogService).save(argThat(log ->
                log.getType() == 1
                        && log.getBeforeQuantity() == 50
                        && log.getAfterQuantity() == 40));
    }

    @Test
    @DisplayName("【P0-2】入库流水区间取自入库后的真实值")
    void testAddStockLogUsesTrueAfterValue() {
        stubExistingInventoryAfterAdd(60);
        when(inventoryMapper.increaseStock(PRODUCT_ID, 10)).thenReturn(1);
        when(inventoryLogService.save(any(InventoryLog.class))).thenReturn(true);

        inventoryService.addStock(PRODUCT_ID, 10, "purchase", 7L);

        verify(inventoryLogService).save(argThat(log ->
                log.getType() == 0
                        && log.getBeforeQuantity() == 50
                        && log.getAfterQuantity() == 60));
    }
}
