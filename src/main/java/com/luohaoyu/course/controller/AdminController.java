package com.luohaoyu.course.controller;

import com.luohaoyu.course.common.Result;
import com.luohaoyu.course.service.DataGenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端：压测数据生成。仅开发/压测环境使用（Stage 6 部署时会在 README 标注
 * 公网部署需关闭 /api/admin/** 或加 SimpleAuth，不在本期安全范围内展开）。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DataGenService dataGenService;

    /** 生成/重置压测数据：5000 学生 × 50 门课 × 容量 100。幂等 = 每次全量重置，理由见 DataGenService 类注释 */
    @PostMapping("/gen-data")
    public Result<Map<String, Object>> genData() {
        return Result.ok(dataGenService.regenerate());
    }
}
