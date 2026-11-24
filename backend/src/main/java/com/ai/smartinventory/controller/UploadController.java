package com.ai.smartinventory.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.ai.smartinventory.common.RateLimit;
import com.ai.smartinventory.common.Result;
import com.ai.smartinventory.config.UploadPathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
@RequiredArgsConstructor
public class UploadController {

    /**
     * 允许上传的图片扩展名白名单。
     *
     * 【安全加固】原实现直接沿用原文件名里的扩展名，没有任何校验，
     * 而 /uploads/** 是通过 WebMvcConfig 静态映射「按原样」提供文件的：
     * 上传一个 evil.html 就能拿到同源下的存储型 XSS（可直接读取
     * localStorage 里的 Sa-Token 凭证）；换成会解析脚本的 web 容器时，
     * .jsp/.php 之类还可能被直接执行。
     */
    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp");

    /**
     * 落盘目录的唯一来源。与 WebMvcConfig 的伺服目录同源（同一份解析逻辑），
     * 从结构上消除"写一个目录、读另一个目录"的可能。
     */
    private final UploadPathResolver uploadPathResolver;

    @SaCheckLogin
    // 【P1-1 限流补全】上传是重 IO 操作，防脚本批量刷盘。
    @RateLimit(key = "upload:image", permitsPerMinute = 30, permitsPerHour = 200)
    @PostMapping("/image")
    public Result<Map<String, Object>> uploadImage(@RequestParam("file") MultipartFile file) {
        // 【2026-10-10】错误码按语义分档，脱离已废弃的 Result.error(String)（它恒 500，
        // 前端与调用方无法区分「参数不合法」与「服务器坏了」）。
        //   · 客户端参数/白名单问题 → 400（与验证脚本对售后域的既有断言口径一致，全库 14 处断言 code===400、0 处断言 500）
        //   · 真正的服务端故障   → 500（落盘失败、IOException）
        // 前端 utils/request.js 只判 `code !== 200` 与 `code === 401`，此改动对界面无行为变化。
        if (file.isEmpty()) {
            return Result.error(400, "上传文件不能为空");
        }

        try {
            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.isEmpty()) {
                return Result.error(400, "文件名不能为空");
            }

            String extension = "";
            int lastDotIndex = originalFilename.lastIndexOf(".");
            if (lastDotIndex > 0) {
                extension = originalFilename.substring(lastDotIndex).toLowerCase();
            }

            // 【安全加固】白名单校验，见 ALLOWED_IMAGE_EXTENSIONS 的说明。
            // 注意行为变化：原来「无扩展名」会兜底成 .jpg，现在直接拒绝 ——
            // 没有扩展名的文件本来也不该被当成图片收下。
            if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension)) {
                log.warn("拒绝上传非法扩展名: '{}' (原文件名: {})", extension, originalFilename);
                return Result.error(400, "只允许上传图片文件（jpg / jpeg / png / gif / webp / bmp）");
            }

            String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
            String newFileName = UUID.randomUUID().toString() + extension;

            Path uploadDir = uploadPathResolver.resolveDateDir(datePath);
            Files.createDirectories(uploadDir);

            Path destFile = uploadDir.resolve(newFileName);
            file.transferTo(destFile);

            // 【加固】落盘后立刻回读校验：把"存了但读不到"在上传当场就暴露，
            // 而不是等用户次日登录才发现图片全没了。
            long expected = file.getSize();
            long actual = Files.exists(destFile) ? Files.size(destFile) : -1L;
            if (actual != expected) {
                log.error("落盘校验失败: {} (期望 {} 字节, 实际 {} 字节)", destFile, expected, actual);
                return Result.error(500, "文件保存失败：落盘校验未通过");
            }

            log.info("文件上传成功: {} ({} 字节)", destFile, actual);

            String accessUrl = "/uploads/" + datePath.replace("\\", "/") + "/" + newFileName;

            Map<String, Object> data = new HashMap<>();
            data.put("url", accessUrl);
            data.put("fileName", newFileName);
            data.put("filePath", destFile.toAbsolutePath().toString());

            log.info("返回URL: {}", accessUrl);

            return Result.success("上传成功", data);

        } catch (IOException e) {
            // 【异常出口收口】IOException 是受检异常（transferTo / Files），必须就地捕获，
            // 但**不能把 e.getMessage() 回吐前端** —— 它含落盘目录的绝对路径等内部信息。
            // 详情只进日志，对外给通用文案。
            log.error("文件上传失败", e);
            return Result.error(500, "文件上传失败，请稍后重试");
        }
        // 【已移除】原先这里还有一个 `catch (Exception e)` 把 e.getMessage() 原样回吐，
        // 属兜底捕获、会泄露内部结构。现已删除，未预期异常统一交给 GlobalExceptionHandler。
    }
}
