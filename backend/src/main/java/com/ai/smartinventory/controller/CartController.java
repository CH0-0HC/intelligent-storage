package com.ai.smartinventory.controller;

import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.entity.Cart;
import com.ai.smartinventory.service.CartService;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.annotation.Resource;

import java.util.List;
import java.util.Map;

/**
 * 购物车控制器
 */
@Slf4j
@RestController
@RequestMapping("/cart")
@Tag(name = "购物车管理", description = "购物车添加、修改、删除、清空等接口")
public class CartController {

    @Resource
    private CartService cartService;

    @Operation(summary = "获取购物车列表", description = "获取当前登录用户的购物车商品列表")
    @GetMapping("/list")
    public Result<List<Cart>> getCartList() {
        Long userId = StpUtil.getLoginIdAsLong();
        List<Cart> cartList = cartService.getUserCart(userId);
        return Result.success(cartList);
    }

    @Operation(summary = "添加到购物车", description = "将指定商品添加到当前用户的购物车")
    // 【P1-1 限流补全】购物车写操作原先无限流，可被脚本刷量。
    @RateLimit(key = "cart:add", permitsPerMinute = 60, permitsPerHour = 600)
    @PostMapping("/add")
    public Result<String> addToCart(@RequestBody Map<String, Object> params) {
        // 【参数校验】原实现直接 params.get("productId").toString()，
        // 缺字段或传非数字时抛 NPE / NumberFormatException -> 500「系统繁忙」。
        if (params == null || params.get("productId") == null
                || String.valueOf(params.get("productId")).isBlank()) {
            return Result.error(400, "请指定商品");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        Long productId;
        try {
            productId = Long.valueOf(String.valueOf(params.get("productId")).trim());
        } catch (NumberFormatException e) {
            return Result.error(400, "商品参数格式不正确");
        }
        Integer quantity = params.get("quantity") != null
                ? Integer.valueOf(params.get("quantity").toString()) : 1;

        boolean success = cartService.addToCart(userId, productId, quantity);
        return success ? Result.success("已添加到购物车") : Result.error(500, "添加失败");
    }

    @Operation(summary = "修改购物车商品数量", description = "修改购物车中指定商品的数量")
    @RateLimit(key = "cart:update", permitsPerMinute = 120, permitsPerHour = 1000)
    @PutMapping("/update/{id}")
    public Result<String> updateQuantity(
            @Parameter(description = "购物车记录ID") @PathVariable Long id,
            @RequestBody Map<String, Object> params) {
        // 【参数校验】原实现直接 params.get("quantity").toString()，缺字段即 NPE -> 500
        if (params == null || params.get("quantity") == null
                || String.valueOf(params.get("quantity")).isBlank()) {
            return Result.error(400, "请指定数量");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        Integer quantity;
        try {
            quantity = Integer.valueOf(String.valueOf(params.get("quantity")).trim());
        } catch (NumberFormatException e) {
            return Result.error(400, "数量格式不正确");
        }

        boolean success = cartService.updateQuantity(id, userId, quantity);
        return success ? Result.success("更新成功") : Result.error(500, "更新失败");
    }

    @Operation(summary = "从购物车移除商品", description = "从购物车中移除指定商品")
    @RateLimit(key = "cart:remove", permitsPerMinute = 60, permitsPerHour = 600)
    @DeleteMapping("/remove/{id}")
    public Result<String> removeFromCart(
            @Parameter(description = "购物车记录ID") @PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        boolean success = cartService.removeFromCart(id, userId);
        return success ? Result.success("已移除") : Result.error(500, "移除失败");
    }

    @Operation(summary = "清空购物车", description = "清空当前登录用户的购物车")
    @RateLimit(key = "cart:clear", permitsPerMinute = 30, permitsPerHour = 200)
    @DeleteMapping("/clear")
    public Result<String> clearCart() {
        Long userId = StpUtil.getLoginIdAsLong();
        boolean success = cartService.clearCart(userId);
        return success ? Result.success("购物车已清空") : Result.error(500, "清空失败");
    }

    // ==================== 勾选（2026-09-22 新增） ====================
    // 勾选状态持久化在 cart.selected 列，因此刷新页面 / 重新登录后依然保持。

    @Operation(summary = "勾选/取消勾选单件商品",
            description = "selected=1 选中，selected=0 取消；状态持久化在服务端")
    @RateLimit(key = "cart:selected", permitsPerMinute = 180, permitsPerHour = 2000)
    @PutMapping("/selected/{id}")
    public Result<String> updateSelected(
            @Parameter(description = "购物车记录ID") @PathVariable Long id,
            @RequestBody Map<String, Object> params) {
        if (params == null || params.get("selected") == null
                || String.valueOf(params.get("selected")).isBlank()) {
            return Result.error(400, "请指定勾选状态");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        Integer selected;
        try {
            selected = Integer.valueOf(String.valueOf(params.get("selected")).trim());
        } catch (NumberFormatException e) {
            return Result.error(400, "勾选状态格式不正确");
        }

        boolean success = cartService.updateSelected(id, userId, selected);
        return success ? Result.success("更新成功") : Result.error(500, "更新失败");
    }

    @Operation(summary = "全选/取消全选",
            description = "把当前用户购物车内所有商品的勾选状态统一设为 selected")
    @RateLimit(key = "cart:selectedAll", permitsPerMinute = 60, permitsPerHour = 600)
    @PutMapping("/selected-all")
    public Result<Integer> updateSelectedAll(@RequestBody Map<String, Object> params) {
        if (params == null || params.get("selected") == null
                || String.valueOf(params.get("selected")).isBlank()) {
            return Result.error(400, "请指定勾选状态");
        }
        Long userId = StpUtil.getLoginIdAsLong();
        Integer selected;
        try {
            selected = Integer.valueOf(String.valueOf(params.get("selected")).trim());
        } catch (NumberFormatException e) {
            return Result.error(400, "勾选状态格式不正确");
        }

        int changed = cartService.updateSelectedAll(userId, selected);
        return Result.success(selected == 1 ? "已全选" : "已取消全选", changed);
    }

    @Operation(summary = "移除已勾选的商品",
            description = "部分结算成功后调用：只移除本次下单（已勾选）的商品，未勾选的保留")
    @RateLimit(key = "cart:removeSelected", permitsPerMinute = 60, permitsPerHour = 600)
    @DeleteMapping("/remove-selected")
    public Result<Integer> removeSelected() {
        Long userId = StpUtil.getLoginIdAsLong();
        int removed = cartService.removeSelected(userId);
        return Result.success("已从购物车移除已结算商品", removed);
    }
}