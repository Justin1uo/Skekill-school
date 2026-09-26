package com.luohaoyu.course.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 选课请求体：POST /api/selection {"studentId":1,"courseId":1}
 * 压测时 JMeter 的 CSV 数据文件就是这两列的排列组合。
 */
@Data
public class SelectionRequest {

    @NotNull(message = "不能为空")
    private Long studentId;

    @NotNull(message = "不能为空")
    private Long courseId;
}
