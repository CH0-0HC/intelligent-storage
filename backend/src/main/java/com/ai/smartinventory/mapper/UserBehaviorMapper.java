package com.ai.smartinventory.mapper;

import com.ai.smartinventory.entity.UserBehavior;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface UserBehaviorMapper extends BaseMapper<UserBehavior> {
    
    List<UserBehavior> selectByUserId(@Param("userId") Long userId);
    
    List<UserBehavior> selectRecentBehaviors(@Param("days") Integer days);
}
