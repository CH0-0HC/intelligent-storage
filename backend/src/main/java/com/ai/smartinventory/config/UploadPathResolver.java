package com.ai.smartinventory.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 上传路径的「唯一权威来源」（single source of truth）。
 *
 * 【为什么要有这个类】
 * 历史故障：文件落盘目录与静态资源挂载目录是**两处独立推导**出来的，
 * 都基于 ${upload.path}。一旦该配置是相对路径，两者会随「进程工作目录
 * (user.dir)」一起漂移；而**历史文件不会跟着漂移**。结果是：
 *   - 换一种启动方式（IDEA 默认 cwd=backend/  vs  命令行 cwd=项目根）
 *   - 或改了配置但旧实例没重启
 * 就会出现「写入目录 ≠ 伺服目录」，DB 里存的 /uploads/... 全部 404。
 *
 * 现在把「解析上传根目录」这件事收敛到本类：写入方（UploadController）
 * 与伺服方（WebMvcConfig）都必须调用这里，物理上杜绝再次分叉。
 *
 * 另附一个低成本漂移探测器：启动时若发现「工作目录附近存在 uploads 目录
 * 且它不等于配置目录」，立刻 WARN。这类问题越早暴露越好，而不是等用户
 * 次日登录发现图片全没了。
 */
@Slf4j
@Component
public class UploadPathResolver {

    @Value("${upload.path}")
    private String configuredPath;

    /**
     * 可选：历史遗留的上传目录，逗号分隔的绝对路径。
     * 这些目录会被**只读兜底挂载**到 /uploads/** 之下，
     * 用于避免"历史文件落在别处"直接 404。
     * 正确做法是迁移合并（scripts/migrate-legacy-uploads.ps1），
     * 本项只是安全网，不替代迁移。
     */
    @Value("${upload.legacy-paths:}")
    private String legacyPathsConfig;

    /** 规范化的上传根目录：绝对路径、已存在 */
    private Path root;

    /** 只读兜底的遗留目录 */
    private final List<Path> legacyRoots = new ArrayList<>();

    @PostConstruct
    public void init() throws IOException {
        Path raw = Paths.get(configuredPath);

        if (!raw.isAbsolute()) {
            log.warn("upload.path 不是绝对路径（当前值: {}）。相对路径会随进程工作目录漂移，"
                    + "历史文件不会跟着漂移 —— 这会直接导致图片 404。"
                    + "请改为绝对路径，或用环境变量 UPLOAD_PATH 覆盖。", configuredPath);
        }

        root = raw.toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("上传根目录（落盘与伺服共用此路径）: {}", root);

        if (StringUtils.hasText(legacyPathsConfig)) {
            for (String item : legacyPathsConfig.split(",")) {
                if (!StringUtils.hasText(item)) {
                    continue;
                }
                Path p = Paths.get(item.trim()).toAbsolutePath().normalize();
                if (p.equals(root)) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    legacyRoots.add(p);
                    log.info("遗留上传目录已挂为只读兜底: {}", p);
                } else {
                    log.info("遗留上传目录不存在，已忽略: {}", p);
                }
            }
        }

        detectWorkingDirectoryDrift();
    }

    /**
     * 漂移探测器：找出「就近存在的、但不等于配置目录的 uploads 目录」。
     * 命中即说明历史文件可能落在伺服范围之外，其中的文件将 404。
     */
    private void detectWorkingDirectoryDrift() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        // 用 Set 去重：cwd 与 configured root 的父目录可能指向同一个位置，
        // 否则同一个漂移目录会被重复告警。
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(cwd.resolve("uploads"));
        candidates.add(cwd.resolve("backend").resolve("uploads"));
        Path parent = root.getParent();
        if (parent != null) {
            candidates.add(parent.resolve("uploads"));
            candidates.add(parent.resolve("backend").resolve("uploads"));
        }

        Set<Path> reported = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            Path c = candidate.normalize();
            if (c.equals(root) || legacyRoots.contains(c) || !Files.isDirectory(c) || !reported.add(c)) {
                continue;
            }
            long files = countFiles(c);
            if (files > 0) {
                log.warn("检测到疑似「漂移」的上传目录: {}（{} 个文件）。"
                        + "它不在伺服范围内，其中的文件会全部 404。"
                        + "处理：用 scripts/migrate-legacy-uploads.ps1 合并到 {}，"
                        + "或将其加入 upload.legacy-paths 作为只读兜底。", c, files, root);
            }
        }
    }

    private long countFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            log.debug("统计目录文件数失败: {}", dir, e);
            return -1;
        }
    }

    /** 供 UploadController 落盘使用 */
    public Path root() {
        return root;
    }

    /** 解析某个日期子目录（yyyy/MM/dd）的绝对落盘路径 */
    public Path resolveDateDir(String datePath) {
        return root.resolve(datePath.replace('\\', '/'));
    }

    /**
     * 供 WebMvcConfig 使用：静态资源位置列表。
     * 下标 0 恒为主目录，其后为只读兜底目录。
     */
    public String[] resourceLocations() {
        List<String> locations = new ArrayList<>();
        locations.add(toFileUrl(root));
        for (Path p : legacyRoots) {
            locations.add(toFileUrl(p));
        }
        return locations.toArray(new String[0]);
    }

    private static String toFileUrl(Path p) {
        String s = p.toAbsolutePath().normalize().toString().replace("\\", "/");
        if (!s.endsWith("/")) {
            s = s + "/";
        }
        return "file:" + s;
    }
}
