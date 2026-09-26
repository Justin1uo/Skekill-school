package com.luohaoyu.course;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 高校选课系统入口。
 *
 * 项目形态：单体 Spring Boot 3（本期不做微服务，P1 再拆 Nacos + Gateway）。
 * 核心卖点不是业务功能，而是高并发下的并发正确性：
 * 超卖 / 重复选课 / 时间冲突 / 学分超限，四个问题一个都不能出现。
 *
 * 完整设计规格见项目根目录 course-selection-blueprint.md。
 */
@SpringBootApplication
@MapperScan("com.luohaoyu.course.mapper")
public class CourseApplication {

    public static void main(String[] args) {
        SpringApplication.run(CourseApplication.class, args);
    }
}
