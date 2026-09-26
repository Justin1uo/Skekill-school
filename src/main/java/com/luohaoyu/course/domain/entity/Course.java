package com.luohaoyu.course.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程。
 *
 * ★ selected_count 的语义（面试会问）：运行期名额以 Redis 的 course:cap:{id} 为准，
 * DB 里的 selected_count 由 MQ 消费端异步累加，只用于展示与对账，
 * 允许短暂落后于 Redis——这是"异步落库削峰"的代价，也是必须讲清楚的一致性边界。
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String courseCode;

    private String name;

    private String teacher;

    private Integer credit;

    /** 总容量。剩余名额 = Redis course:cap:{id}，不在 DB 实时维护 */
    private Integer capacity;

    /** 已选人数（MQ 消费端累加，最终一致） */
    private Integer selectedCount;

    /** 1 开放 0 关闭 */
    private Integer status;

    private LocalDateTime createdAt;
}
