package com.ai.smartinventory.common;

import com.ai.smartinventory.entity.UserBehavior;
import com.ai.smartinventory.mapper.UserBehaviorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户行为埋点记录器（R18）。
 *
 * 【为什么需要它】
 * 在 R18 之前，{@code user_behavior} 表**只被读、从未被写**：
 * {@code CollaborativeFilteringRecommender} 依赖它做协同过滤，
 * 但表里永远是 0 行 ⇒「个性化推荐」恒走降级分支，属于「界面在、功能不在」。
 *
 * 【三条硬约束（都踩过或极易踩）】
 * 1. **绝不能影响主流程**：埋点是锦上添花，写库失败只打 WARN，不抛异常。
 * 2. **绝不能污染主事务**：下单/加购都在 {@code @Transactional} 内，
 *    若直接 insert 且埋点失败，会把订单一起回滚；
 *    若直接 insert 而订单随后回滚，则会留下「买了但订单不存在」的脏行为。
 *    ⇒ 因此事务内注册 {@code afterCommit} 回调，**提交后才写**。
 * 3. **未登录不记**：{@code GET /product/{id}} 在匿名白名单里
 *    （JwtAuthenticationFilter 精确放行纯数字 id），
 *    {@code StpUtil.getLoginIdAsLong()} 对匿名会抛异常 ⇒ 必须先判 userId 是否为 null。
 *
 * @see MerchantContext#currentUserId() 未登录时安全返回 null
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserBehaviorRecorder {

    /** 行为类型取值与 user_behavior.behavior_type 的列注释一致 */
    public static final String TYPE_VIEW = "view";
    public static final String TYPE_CART = "cart";
    public static final String TYPE_PURCHASE = "purchase";
    public static final String TYPE_COLLECT = "collect";

    private final UserBehaviorMapper userBehaviorMapper;

    /** 记录「当前登录用户」对某商品的行为；未登录自动跳过 */
    public void record(String behaviorType, Long productId) {
        record(MerchantContext.currentUserId(), behaviorType, productId);
    }

    public void record(Long userId, String behaviorType, Long productId) {
        if (userId == null || productId == null || behaviorType == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务内：等主事务提交成功后再落库（回滚则整条行为不记）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSave(userId, behaviorType, productId);
                }
            });
        } else {
            doSave(userId, behaviorType, productId);
        }
    }

    private void doSave(Long userId, String behaviorType, Long productId) {
        try {
            UserBehavior behavior = new UserBehavior();
            behavior.setUserId(userId);
            behavior.setProductId(productId);
            behavior.setBehaviorType(behaviorType);
            behavior.setIp(ClientIpUtils.getClientIp());
            // deleted / create_time / update_time 交给 DB 默认值与 MetaObjectHandler
            userBehaviorMapper.insert(behavior);
        } catch (Exception e) {
            log.warn("用户行为埋点写入失败（已吞掉，不影响业务）：type={} userId={} productId={} err={}",
                    behaviorType, userId, productId, e.getMessage());
        }
    }
}
