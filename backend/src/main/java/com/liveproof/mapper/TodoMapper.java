package com.liveproof.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liveproof.entity.TodoEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TodoMapper extends BaseMapper<TodoEntity> {
}
