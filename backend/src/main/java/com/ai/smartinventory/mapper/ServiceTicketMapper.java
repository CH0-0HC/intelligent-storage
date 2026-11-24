package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.ServiceTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客服裁定工单 Mapper。
 *
 * 全部查询条件（归属、状态、消费标记）都由 ServiceTicketServiceImpl 用
 * LambdaQueryWrapper 组装，故这里保持 BaseMapper 的最小形态 ——
 * 与 AfterSaleMapper 同一风格。
 */
@Mapper
public interface ServiceTicketMapper extends BaseMapper<ServiceTicket> {
}
