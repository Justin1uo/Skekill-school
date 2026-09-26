package com.luohaoyu.course.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 课程时段：一门课可占多个时段（如周三 3-4 节 + 周五 8-9 节 = 两条记录）。
 *
 * 时段在 Redis 中的表达（方案 A 用）："{dayOfWeek}:{period}" 逐节展开，
 * 例如周三 3-4 节 → "3:3"、"3:4"。冲突检测 = 两个集合求交，SISMEMBER 即可，
 * 不需要区间运算——把"区间重叠"预处理成"离散点位"，是这里的关键简化。
 */
@Data
@TableName("course_schedule")
public class CourseSchedule {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    /** 1-7（周一到周日） */
    private Integer dayOfWeek;

    /** 1-12 节 */
    private Integer startPeriod;

    private Integer endPeriod;

    /** 展开为 Redis slot 元素："day:period" */
    public String toSlot(int period) {
        return dayOfWeek + ":" + period;
    }
}
