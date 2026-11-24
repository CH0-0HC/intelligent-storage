package com.ai.smartinventory.ai;

import com.ai.smartinventory.entity.Product;
import com.ai.smartinventory.entity.UserBehavior;
import com.ai.smartinventory.mapper.ProductMapper;
import com.ai.smartinventory.mapper.UserBehaviorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 协同过滤推荐算法单元测试
 *
 * 【本次修复的既有缺陷】
 * 1) testRecommendWithSimilarUsers 的测试数据选得不对，导致用例必然失败。
 *    原数据里用户1 评分为 (101:4, 102:隐式3.5, 103:5)，用户2 为 (101:3, 102:5, 104:隐式2.5)，
 *    两个共同商品的评分趋势完全相反 → Pearson = -1.0，
 *    混合相似度 = 0.4*Jaccard(0.5) + 0.6*(-1.0) = **-0.4**，
 *    低于 SIMILARITY_THRESHOLD(0.1)，算法必然回落到默认推荐，
 *    因此「应推出协同过滤结果」的期望不成立。
 *
 *    已用独立脚本实测确认（threshold=0.1, Jaccard权重0.4, Pearson权重0.6）：
 *      A: u1(4,-,5) vs u2(3,5,-)       J=0.50 P=-1.00 hybrid=-0.40 -> 回落
 *      B: u1(3,4,5) vs u2(3,4,-)       J=0.50 P=+1.00 hybrid=+0.80 -> 命中协同过滤
 *      C: u1(5,4,-) vs u2(5,4,+new)    J=0.50 P=+1.00 hybrid=+0.80 -> 命中协同过滤
 *      D: u1(3,4,5) vs u2(3,4,5,new)   J=0.75 P=+1.00 hybrid=+0.90 -> 命中协同过滤
 *    下面采用「评分趋势一致」的数据，使用例真正覆盖协同过滤分支。
 *
 * 2) testRecommendLimitCount 的 selectBatchIds 桩未被使用（用例走了默认推荐分支），
 *    触发 Mockito UnnecessaryStubbingException。已用 LENIENT 收敛并让用例走到 CF 分支。
 *
 * 3) 原断言 assertFalse(result.isEmpty()) 的**意图是对的**（结果不应为空），
 *    但配合错误的数据永远无法成立；数据修正后该断言自然通过，
 *    并额外补上「推荐内容确实包含共同项之外的商品」的强断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("协同过滤推荐算法单元测试")
class CollaborativeFilteringRecommenderTest {

    @Mock
    private UserBehaviorMapper userBehaviorMapper;

    @Mock
    private ProductMapper productMapper;

    @InjectMocks
    private CollaborativeFilteringRecommender recommender;

    private List<Product> defaultProducts;
    private List<UserBehavior> userBehaviors;

    @BeforeEach
    void setUp() {
        defaultProducts = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            Product p = new Product();
            p.setId((long) i);
            p.setName("商品" + i);
            p.setPrice(new BigDecimal("99.00"));
            defaultProducts.add(p);
        }

        // 【数据修正】评分趋势保持一致，使 Pearson 为正，混合相似度能越过 0.1 阈值
        // 用户1: 101(view,3) 102(view,4) 103(purchase,5)
        // 用户2: 101(view,3) 102(view,4) 104(collect,5的隐式分)
        // 两者共同项 101/102 趋势一致 → Pearson = +1.0，Jaccard = 0.5
        // hybrid = 0.4*0.5 + 0.6*1.0 = 0.8 > 0.1，命中协同过滤
        // 用户2 独有的 104 应被推荐给用户1
        userBehaviors = Arrays.asList(
                createBehavior(1L, 1L, 101L, "view", 3),
                createBehavior(2L, 1L, 102L, "view", 4),
                createBehavior(3L, 1L, 103L, "purchase", 5),
                createBehavior(4L, 2L, 101L, "view", 3),
                createBehavior(5L, 2L, 102L, "view", 4),
                createBehavior(6L, 2L, 104L, "collect", 5)
        );
    }

    private UserBehavior createBehavior(Long id, Long userId, Long productId,
                                        String behaviorType, Integer rating) {
        UserBehavior behavior = new UserBehavior();
        behavior.setId(id);
        behavior.setUserId(userId);
        behavior.setProductId(productId);
        behavior.setBehaviorType(behaviorType);
        behavior.setRating(rating);
        behavior.setCreateTime(LocalDateTime.now());
        return behavior;
    }

    /** selectBatchIds 返回按传入 id 构造的商品，便于断言推荐内容。 */
    private void stubBatchIdsEcho() {
        when(productMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            List<Product> products = new ArrayList<>();
            for (Long id : ids) {
                Product p = new Product();
                p.setId(id);
                p.setName("推荐商品" + id);
                p.setPrice(new BigDecimal("99.00"));
                products.add(p);
            }
            return products;
        });
    }

    @Test
    @DisplayName("推荐 - userId 为 null 时返回默认推荐")
    void testRecommendNullUserId() {
        when(productMapper.selectRecommendProducts(10)).thenReturn(defaultProducts);

        List<Product> result = recommender.recommend(null, 10);

        assertNotNull(result);
        assertEquals(10, result.size());
        verify(productMapper).selectRecommendProducts(10);
    }

    @Test
    @DisplayName("推荐 - 用户无行为记录时返回默认推荐")
    void testRecommendNoUserBehavior() {
        when(userBehaviorMapper.selectByUserId(999L)).thenReturn(Collections.emptyList());
        when(productMapper.selectRecommendProducts(5)).thenReturn(defaultProducts.subList(0, 5));

        List<Product> result = recommender.recommend(999L, 5);

        assertNotNull(result);
        assertEquals(5, result.size());
        verify(userBehaviorMapper).selectByUserId(999L);
    }

    @Test
    @DisplayName("推荐 - 有相似用户时返回协同过滤结果（含共同项之外的新商品）")
    void testRecommendWithSimilarUsers() {
        stubBatchIdsEcho();
        when(userBehaviorMapper.selectByUserId(1L)).thenReturn(userBehaviors.subList(0, 3));
        when(userBehaviorMapper.selectRecentBehaviors(30))
                .thenReturn(new ArrayList<>(userBehaviors));

        List<Product> result = recommender.recommend(1L, 5);

        assertNotNull(result);
        assertFalse(result.isEmpty(), "存在相似用户时应能推出协同过滤结果");
        // 用户1已有 101/102/103，用户2独有的 104 应当被推荐
        assertTrue(result.stream().anyMatch(p -> p.getId().equals(104L)),
                "应推荐用户1未接触过的商品 104，实际：" + result);
        // 走的是协同过滤分支，而不是默认推荐
        verify(productMapper).selectBatchIds(anyList());
        verify(productMapper, never()).selectRecommendProducts(anyInt());
    }

    @Test
    @DisplayName("推荐 - 所有用户行为相同时返回默认推荐")
    void testRecommendAllSameBehavior() {
        List<UserBehavior> sameBehaviors = Arrays.asList(
                createBehavior(1L, 1L, 101L, "view", 4),
                createBehavior(2L, 2L, 101L, "view", 4)
        );

        when(userBehaviorMapper.selectByUserId(1L))
                .thenReturn(Collections.singletonList(sameBehaviors.get(0)));
        when(userBehaviorMapper.selectRecentBehaviors(30))
                .thenReturn(new ArrayList<>(sameBehaviors));
        when(productMapper.selectRecommendProducts(5)).thenReturn(defaultProducts.subList(0, 5));

        List<Product> result = recommender.recommend(1L, 5);

        assertNotNull(result);
        assertEquals(5, result.size());
    }

    @Test
    @DisplayName("推荐 - 返回数量受 limit 限制")
    void testRecommendLimitCount() {
        stubBatchIdsEcho();
        when(userBehaviorMapper.selectByUserId(1L)).thenReturn(userBehaviors.subList(0, 3));
        when(userBehaviorMapper.selectRecentBehaviors(30))
                .thenReturn(new ArrayList<>(userBehaviors));

        List<Product> result = recommender.recommend(1L, 1);

        assertNotNull(result);
        // limit=1 时必须严格收敛到 1 条
        assertEquals(1, result.size());
        // 确认真的走了协同过滤分支（而不是默认推荐），selectBatchIds 桩才是有意义的
        verify(productMapper).selectBatchIds(anyList());
    }

    @Test
    @DisplayName("推荐 - 评分趋势相反（Pearson 为负）时会稀释相似度并回落默认推荐")
    void testRecommendNegativeCorrelationFallsBack() {
        // 回归保护：这组数据正是原测试用例的数据。
        // 共同项 101/102 的评分趋势相反 → Pearson = -1.0，
        // hybrid = 0.4*0.5 + 0.6*(-1.0) = -0.4 < 阈值 0.1，算法应回落默认推荐。
        // 把这个边界行为固化下来，防止后人再误判。
        List<UserBehavior> negativeCase = Arrays.asList(
                createBehavior(1L, 1L, 101L, "view", 4),
                createBehavior(2L, 1L, 102L, "cart", null),
                createBehavior(3L, 1L, 103L, "purchase", 5),
                createBehavior(4L, 2L, 101L, "view", 3),
                createBehavior(5L, 2L, 102L, "purchase", 5),
                createBehavior(6L, 2L, 104L, "collect", null)
        );
        when(userBehaviorMapper.selectByUserId(1L)).thenReturn(negativeCase.subList(0, 3));
        when(userBehaviorMapper.selectRecentBehaviors(30)).thenReturn(new ArrayList<>(negativeCase));
        when(productMapper.selectRecommendProducts(5)).thenReturn(defaultProducts.subList(0, 5));

        List<Product> result = recommender.recommend(1L, 5);

        assertNotNull(result);
        assertEquals(5, result.size());
        // 相似度被负相关稀释 → 走默认推荐
        verify(productMapper).selectRecommendProducts(5);
        verify(productMapper, never()).selectBatchIds(anyList());
    }

    @Test
    @DisplayName("默认推荐 - 推荐商品为空时回落到热门商品")
    void testDefaultRecommendFallbackToHot() {
        when(userBehaviorMapper.selectByUserId(999L)).thenReturn(Collections.emptyList());
        when(productMapper.selectRecommendProducts(5)).thenReturn(Collections.emptyList());
        when(productMapper.selectHotProducts(5)).thenReturn(defaultProducts.subList(0, 5));

        List<Product> result = recommender.recommend(999L, 5);

        assertNotNull(result);
        assertEquals(5, result.size());
        verify(productMapper).selectHotProducts(5);
    }
}
