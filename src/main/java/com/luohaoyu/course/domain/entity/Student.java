package com.luohaoyu.course.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学生。max_credit 是学分上限校验（错误码 1005）的数据来源。
 * created_at 为 null 时不参与 INSERT（MyBatis-Plus 默认 NOT_NULL 策略），由 DB 默认值填充。
 */
@Data
@TableName("student")
public class Student {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String studentNo;

    private String name;

    private String grade;

    private String major;

    /** 学分上限，gen-data 默认 30 */
    private Integer maxCredit;

    private LocalDateTime createdAt;
}
