package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.AiConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 配置 Mapper。查询条件全部走 MyBatis-Plus 的 LambdaQueryWrapper，
 * 不需要自定义 XML。
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfig> {
}
