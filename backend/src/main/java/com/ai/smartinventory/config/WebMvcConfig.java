package com.ai.smartinventory.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射。
 *
 * 【修复说明】原实现自己用 new File(uploadPath).getAbsolutePath() 推导伺服目录，
 * 与 UploadController 里推导落盘目录的逻辑是「两处独立实现」。两者只要有一处
 * 因为工作目录漂移而算出不同结果，就会出现「写一个目录、读另一个目录」，
 * 表现为 DB 里存着 /uploads/... 但文件取不到（404）。
 *
 * 现在伺服目录统一由 UploadPathResolver 提供，与落盘目录同源，物理上不可能分叉。
 * 若配置了 upload.legacy-paths，历史遗留目录会挂在主目录之后作为**只读兜底**，
 * 避免历史文件直接 404（安全网，不能替代迁移）。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final UploadPathResolver uploadPathResolver;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPathResolver.resourceLocations())
                .setCachePeriod(0);
    }
}
