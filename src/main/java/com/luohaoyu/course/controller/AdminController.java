package com.luohaoyu.course.controller;

import com.luohaoyu.course.common.Result;
import com.luohaoyu.course.service.CourseCacheService;
import com.luohaoyu.course.service.DataGenService;
import com.luohaoyu.course.service.WarmupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端：压测数据生成 + 预热。仅开发/压测环境使用（Stage 6 部署时会在 README 标注
 * 公网部署需关闭 /api/admin/** 或加 SimpleAuth，不在本期安全范围内展开）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DataGenService dataGenService;
    private final WarmupService warmupService;
    private final CourseCacheService courseCacheService;

    /** 生成/重置压测数据：5000 学生 × 50 门课 × 容量 100。幂等 = 每次全量重置，理由见 DataGenService 类注释 */
    @PostMapping("/gen-data")
    public Result<Map<String, Object>> genData() {
        Map<String, Object> summary = dataGenService.regenerate();
        // 表刚清空 → L1/L2 里的课程对象全是脏的，不失效的话 /api/courses 最长 360s 展示旧数据
        courseCacheService.evictCourseCaches();
        return Result.ok(summary);
    }

    /**
     * 预热：把课程现状灌进 Redis（cap/selected/sched/credit/detail/list）。
     * ★ 开抢前专用——压测进行中绝不可调（会用滞后的 DB 覆盖权威 Redis 余量，
     *   制造假超卖），数据流向与取舍见 WarmupService 类注释。
     */
    @PostMapping("/warmup")
    public Result<Map<String, Object>> warmup() {
        return Result.ok(warmupService.warmup());
    }
}
