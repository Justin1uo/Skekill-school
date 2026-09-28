package com.luohaoyu.course.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 压测数据管理专用 Mapper（只有 /api/admin/* 用，业务代码禁止引用）。
 * 用 DELETE 而不是 TRUNCATE：TRUNCATE 是 DDL 会隐式提交，包不进 gen-data 的事务，
 * 中途失败会留下"清了半张表"的脏状态；DELETE 是 DML，可回滚。5000 行量级无性能问题。
 */
@Mapper
public interface AdminMapper {

    @Delete("DELETE FROM selection")
    int clearSelection();

    @Delete("DELETE FROM course_schedule")
    int clearCourseSchedule();

    @Delete("DELETE FROM course")
    int clearCourse();

    @Delete("DELETE FROM student")
    int clearStudent();
}
