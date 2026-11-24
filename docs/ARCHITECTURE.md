# 架构与设计说明

## 1. 总体结构

```
Vue 3 SPA (frontend)
        │  axios（Authorization 头 = Sa-Token）
        ▼
Nginx / Vite Dev Server        /api/**  →  Spring Boot（context-path=/api）
                                       │
                                       ├─ Sa-Token 鉴权 + MerchantContext 商户上下文
                                       ├─ Controller → Service → Mapper(MyBatis-Plus)
                                       └─ MySQL 8（24 表） / Redis（限流，可选） / Ollama & 云端模型
```

后端包结构：

| 包 | 职责 |
| --- | --- |
| `controller` | 12 个 REST 控制器，只做参数接收、权限注解、结果包装 |
| `service` / `service.impl` | 业务规则、事务边界、商户归属断言 |
| `mapper` | MyBatis-Plus Mapper，复杂查询走 `resources/mapper/*.xml` |
| `entity` / `dto` | 表映射与出入参对象 |
| `ai` | 推荐、销量预测、库存预警、经营报告、模型客户端与配置 |
| `common` | 统一响应 `Result`、全局异常、分页边界、金额精度、日志切面、限流注解 |
| `config` / `security` | Web 配置、Sa-Token 装配、JWT 过滤器、商户上下文 |
| `task` | 订单超时回收等后台任务 |

前端：`views/` 按角色分目录（`admin` / `merchant` / `user` / `kefu` 共用若干公共页），`api/` 11 个模块与后端控制器一一对应，`router/index.js` 用 `meta.roles` 做页面级收口。

## 2. 数据模型

全库 24 张表，按域划分：

| 域 | 表 |
| --- | --- |
| 用户与权限 | `sys_user`、`sys_token`、`operation_log`、`system_config` |
| 商品 | `product`、`product_category` |
| 供应商与采购 | `supplier`、`purchase_order`、`purchase_item` |
| 库存 | `inventory`、`inventory_log`、`stock_in`、`stock_in_item`、`stock_out`、`stock_out_item` |
| 交易 | `cart`、`order`、`order_item` |
| 售后 | `after_sale`、`after_sale_item` |
| 客服 | `service_ticket` |
| AI | `user_behavior`、`ai_analysis_log`、`ai_config` |

约定：

- **零物理外键**：关系由业务层保证（见第 4 节）。
- **逻辑删除**：`deleted` 字段（0/1），由 MyBatis-Plus 全局配置处理。
- **金额**：以分为单位的整型存储，展示层统一格式化，避免浮点误差。

## 3. 关键流程

**下单与库存**：购物车结算生成 `order` + `order_item` 时**不扣库存**；商家确认发货或订单签收时才调用 `deductStockForOrder`（幂等，重复调用不会二次扣减），扣减使用 `UPDATE ... WHERE quantity >= ?` 的条件更新，失败即回滚整单并明确报错。

**付款与超时回收**：付款走 `markPaid`（`WHERE order_status = 0` 条件更新），后台任务按 `unpaid-timeout-minutes` 扫描待付款订单并取消（同样条件更新）。两侧都以状态为闸门，因此「超时取消」与「刚刚付款」不会互相覆盖。前端倒计时与后端口径来自同一配置项。

**售后与客服裁定**：订单完成后进入售后域，支持整单与按商品两种粒度（`after_sale.item_scope`）；退货期限由商家按商品配置。超出商家自定规则时限的申诉走 `service_ticket`：客服（平台角色，不绑定商户）裁定通过后，用户才被放行提交售后，`after_sale.cs_ticket_id` 做一次性授权，防止同一工单重复放行。

**AI 链路**：`AiFacade` 统一入口，按 `ai.provider` 选择本地 Ollama 或云端通义千问；本地不可用且云端已配置时自动降级。能力包括：

| 能力 | 实现 |
| --- | --- |
| 商品推荐 | 协同过滤：Jaccard（行为集合）权重 0.4 + Pearson（评分向量，阈值 0.3）权重 0.6，数据不足时回退热门 |
| 销量预测 | 数据充分时用线性递减权重的加权移动平均，不足时退回线性回归 |
| 库存预警 | 按日均销量推算可售天数，< 3 天「紧急」、< 7 天「重要」，并给出补货建议 |
| 经营报告 | 聚合订单与商品维度指标，输出结构化摘要 |
| 对话助手 | 流式/一次性对话，统一封装本地与云端协议差异 |

## 4. 设计取舍

**为什么不用物理外键**：演示数据导入、分库与批量运维都受外键约束拖累；改由 `MerchantContext` + Service 层归属断言保证租户隔离，配合测试覆盖关键越权路径。代价是所有插入路径都要自觉断言，因此代码注释刻意写明「为什么」。

**业务错误也返回 HTTP 200**：前端以响应体 `Result.code` 判定成败，HTTP 状态只表达「请求是否被正确处理」。好处是网关与前端只需一套分支；代价是不能用 HTTP 状态做通用监控，需要看 `code` 维度。

**演示优先于绝对强一致**：例如允许「未付款也发货」的宽松路径，便于演示完整链路；生产接入真实支付时应在 `markPaid` 前加支付回调校验。

**中间件可选**：Redis 缺失时限流降级放行；AI 缺失时面板给提示而非抛错。目标是 clone 下来只装 MySQL 就能跑通。

## 5. 已知边界与后续可做的事

- 推荐与预测**没有附带离线评估**（无 Precision@K / MAE / 基线对比），算法权重为经验取值；做算法型研究需补数据集与实验。
- 演示库规模小（个位数商品与订单），无压测资产；推荐接口为全量行为的内存计算，用户量上升后应改为预计算或离线任务。
- 前端为 SPA，未做 SSR；首屏依赖静态资源缓存策略（`nginx.conf` 已配置长缓存）。
- 可扩展方向：支付网关接入、消息通知、Excel 导入导出、审计日志归档、多仓库调拨。
