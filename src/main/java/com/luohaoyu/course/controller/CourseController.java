package com.luohaoyu.course.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.luohaoyu.course.common.Result;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.mapper.CourseMapper;
import com.luohaoyu.course.mapper.SelectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程只读接口。GET /api/courses 是前端"课程广场"列表的数据源。
 *
 * ★ 定位说明（前端契约先行，此处是提前落地的朴素 DB 版）：
 *   蓝图 Stage 2 会给该接口加 L1 Caffeine + L2 Redis 缓存与预热，
 *   届时只替换本方法的取数路径，响应形状（Result&lt;List&lt;Course&gt;&gt;）一字不改，
 *   前端课程网格自动享受加速，无需改动。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CourseController {

    private final CourseMapper courseMapper;
    private final SelectionMapper selectionMapper;

    /**
     * 课程列表（status=1 按 id 升序，全集可枚举故不做分页——与蓝图第 7 节布隆裁决一致）。
     * selectedCount 实时取自 selection 表聚合（原因见 SelectionMapper.countSelectedGroupByCourse 注释），
     * 固定两条 SQL，无 N+1。
     */
    @GetMapping("/courses")
    public Result<List<Course>> list() {
        List<Course> courses = courseMapper.selectList(
                new QueryWrapper<Course>().eq("status", 1).orderByAsc("id"));

        Map<Long, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : selectionMapper.countSelectedGroupByCourse()) {
            Long courseId = ((Number) row.get("courseId")).longValue();
            counts.put(courseId, ((Number) row.get("cnt")).intValue());
        }
        courses.forEach(c -> c.setSelectedCount(counts.getOrDefault(c.getId(), 0)));

        return Result.ok(courses);
    }
}
