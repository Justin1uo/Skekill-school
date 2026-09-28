package com.luohaoyu.course.controller;

import com.luohaoyu.course.common.Result;
import com.luohaoyu.course.domain.dto.SelectionRequest;
import com.luohaoyu.course.domain.entity.Course;
import com.luohaoyu.course.mapper.SelectionMapper;
import com.luohaoyu.course.service.SelectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 选课接口。POST /api/selection 是 Stage 5 压测的靶子接口。
 */
@RestController
@RequestMapping("/api/selection")
@RequiredArgsConstructor
public class SelectionController {

    private final SelectionService selectionService;
    private final SelectionMapper selectionMapper;

    /** 朴素版选课（v1 基线，语义与限制见 SelectionService 类注释） */
    @PostMapping
    public Result<Long> select(@Valid @RequestBody SelectionRequest request) {
        Long selectionId = selectionService.select(request.getStudentId(), request.getCourseId());
        return Result.ok(selectionId);
    }

    /** 我的课表：联表返回该生已选课程（status=1） */
    @GetMapping("/mine")
    public Result<List<Course>> mine(@RequestParam Long studentId) {
        return Result.ok(selectionMapper.selectMyCourses(studentId));
    }
}
