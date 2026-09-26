package com.luohaoyu.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohaoyu.course.domain.entity.Selection;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SelectionMapper extends BaseMapper<Selection> {

    // Stage 3 提示：MQ 消费端落库不要直接 insert()，
    // 用 XML 写 INSERT ... ON DUPLICATE KEY UPDATE status = 1，
    // 配合唯一索引 uk_student_course 实现幂等（蓝图 7.5）。
    // 对应 XML 放 resources/mapper/SelectionMapper.xml。
}
