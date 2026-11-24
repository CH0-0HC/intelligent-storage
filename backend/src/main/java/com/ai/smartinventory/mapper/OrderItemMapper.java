package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.OrderItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItem> {
    
    List<OrderItem> selectByProductId(@Param("productId") Long productId);
}
