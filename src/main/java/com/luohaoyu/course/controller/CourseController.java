package com.luohaoyu.course.controller;

import com.luohaoyu.course.common.ErrorCode;
import com.luohaoyu.course.common.Result;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.service.CourseCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程只读接口。Stage 2 起取数路径换成 CourseCacheService（L1 Caffeine + L2 Redis），
 * 响应形状与本文件 Stage 1 版本一字不差——前端契约兑现（蓝图接口清单 S2 两项在此）。
 *
 * 注意 controller 变得很薄：缓存策略（空值标记/互斥重建/抖动 TTL）全部收在
 * Service 层，这里只负责 HTTP 映射。取数逻辑（两条 SQL 无 N+1）搬进了
 * CourseCacheService.loadListFromDb，git blame 能追到 bff0d84 的原始实现。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CourseController {

    private final CourseCacheService courseCacheService;

    /** 课程列表（L1 60s → L2 300s±抖动 → DB） */
    @GetMapping("/courses")
    public Result<List<Course>> list() {
        return Result.ok(courseCacheService.list());
    }

    /**
     * 课程详情。不存在返回 1001——但注意"不存在"也会被缓存（NULL_MARKER 60s），
     * 反复刷同一个非法 id 只有第一次真打到 DB（这就是防穿透的验证点，
     * Stage 2 完成标志第 2 条：清掉 Redis 重启，接口不报错）。
     */
    @GetMapping("/courses/{id}")
    public Result<Course> detail(@PathVariable Long id) {
        Course course = courseCacheService.getDetail(id);
        if (course == null) {
            return Result.fail(ErrorCode.COURSE_NOT_FOUND);
        }
        return Result.ok(course);
    }
}
