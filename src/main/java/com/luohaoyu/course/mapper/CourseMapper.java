package com.luohaoyu.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohaoyu.course.domain.entity.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * MQ 消费端累加已选人数（Stage 3 用）。
     * 写成 UPDATE ... SET selected_count = selected_count + 1 而不是先查后改：
     * 单条 UPDATE 在 InnoDB 行锁下天然原子，多个消费者并发累加不会丢更新。
     */
    @Update("UPDATE course SET selected_count = selected_count + #{delta} WHERE id = #{courseId}")
    int incrSelectedCount(@Param("courseId") Long courseId, @Param("delta") int delta);
}
