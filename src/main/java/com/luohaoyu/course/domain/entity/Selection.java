package com.luohaoyu.course.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选课记录。
 *
 * ★ 表上的 uk_student_course(student_id, course_id) 唯一索引是并发正确性的最后防线：
 * Redis 主从切换可能丢锁 → 两个重复请求同时走到 INSERT → 第二条被唯一索引拒绝
 * → GlobalExceptionHandler 把 DuplicateKeyException 翻译成 1003。
 * 锁和 Lua 都失效时，数据依然正确，只是用户看到一次"重复选课"。
 *
 * status: 1 已选 / 0 已退。退选不删行（保留审计痕迹，且唯一索引含 status 会失效，
 * 重选走 UPDATE status=1）。
 */
@Data
@TableName("selection")
public class Selection {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long studentId;

    private Long courseId;

    private Integer status;

    private LocalDateTime createdAt;
}
