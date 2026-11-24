package com.ai.smartinventory.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.LocalDateTime;

@Configuration
@MapperScan("com.ai.smartinventory.mapper")
public class MybatisPlusConfig implements MetaObjectHandler {
    
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 【P1-3 修复】分页 size 此前无任何上限：客户端传 size=1000000 即可
        // 让服务端一次性捞出全表（DoS / 内存打爆），且绕不过去——因为入口分散在
        // PageQueryDTO、多个裸 @RequestParam Integer size、以及 service 内部 new Page<>，
        // 逐个加 @Max 必然漏。故在分页拦截器这一「所有分页查询的必经之路」上统一夹取上限。
        // 500 条/页对管理后台足够，同时把单次响应体控制在合理量级。
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
    
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
    
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
