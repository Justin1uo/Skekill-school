package com.luohaoyu.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohaoyu.course.domain.entity.Student;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StudentMapper extends BaseMapper<Student> {
}
