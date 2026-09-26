package com.luohaoyu.course.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务错误码（蓝图第 5 节，1001-1007 与蓝图一一对应，不要改数字）。
 *
 * 压测时错误码就是"分类计数器"：JMeter 聚合报告只能看 HTTP 200，
 * 靠 code 才能区分"名额已满(1002)"是正常业务拒绝还是"超卖 bug"。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "ok"),

    PARAM_ERROR(400, "参数错误"),
    SYSTEM_ERROR(500, "系统异常"),

    COURSE_NOT_FOUND(1001, "课程不存在"),
    COURSE_FULL(1002, "名额已满"),
    DUPLICATE_SELECTION(1003, "重复选课"),
    TIME_CONFLICT(1004, "上课时间冲突"),
    CREDIT_EXCEEDED(1005, "学分超限"),
    BUSY(1006, "系统繁忙，请稍后重试"),   // 限流拒绝 / 抢锁失败，快速失败优于排队
    NOT_WARMED_UP(1007, "选课尚未开放");  // Redis 中无名额数据 = 未预热，直接拒绝而非回源

    private final int code;
    private final String msg;
}
