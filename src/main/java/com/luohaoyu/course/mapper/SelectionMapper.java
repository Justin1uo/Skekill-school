package com.luohaoyu.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.domain.entity.Selection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SelectionMapper extends BaseMapper<Selection> {

    /** 我的课表：selection 联 course（/api/selection/mine 用，Stage 1） */
    @Select("SELECT c.* FROM selection s JOIN course c ON c.id = s.course_id "
            + "WHERE s.student_id = #{studentId} AND s.status = 1")
    List<Course> selectMyCourses(@Param("studentId") Long studentId);

    /**
     * /api/courses 用：一次 group by 统计每门课的真实在读人数（避免逐课 count 的 N+1）。
     * ★ 为什么不用 course.selected_count 列：该列由 MQ 消费端从 Stage 3 起异步累加
     *   （语义见 Course 实体注释），当前恒为 0，直接展示会把"已满"判错。
     *   Stage 2 预热上线后，列表口径应切换为 Redis 余量 key，本查询届时退役为对账用。
     */
    @Select("SELECT course_id AS courseId, COUNT(*) AS cnt FROM selection WHERE status = 1 GROUP BY course_id")
    List<Map<String, Object>> countSelectedGroupByCourse();

    // Stage 3 提示：MQ 消费端落库不要直接 insert()，
    // 用 XML 写 INSERT ... ON DUPLICATE KEY UPDATE status = 1，
    // 配合唯一索引 uk_student_course 实现幂等（蓝图 7.5）。
    // 对应 XML 放 resources/mapper/SelectionMapper.xml。
}
