package com.luohaoyu.course.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：所有异常统一转 Result，HTTP 状态码保持 200，
 * 由 body 里的 code 表达业务结果（压测脚本按 code 分类统计更方便）。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 预期内的业务拒绝，warn 级别即可，不打堆栈（洪峰下刷堆栈会拖垮日志 IO） */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("biz reject: code={}, msg={}", e.getErrorCode().getCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * ★ 唯一索引兜底的落点（蓝图 7.5）：
     * 当 Lua 判重 + 学生锁都失效（极端：Redis 主从切换丢锁）时，
     * 第二条 INSERT 触发 uk_student_course 冲突 → DuplicateKeyException，
     * 在这里被翻译成 1003 重复选课，对用户表现为正常业务拒绝，数据仍然正确。
     * 这就是"分布式锁不是 100% 可靠，最终一致性靠数据库约束兜底"的代码体现。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        log.error("unique index caught a duplicate write (lock may have failed): {}", e.getMessage());
        return Result.fail(ErrorCode.DUPLICATE_SELECTION);
    }

    /** @Valid 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse(ErrorCode.PARAM_ERROR.getMsg());
        return Result.fail(ErrorCode.PARAM_ERROR, msg);
    }

    /** 未预期异常：error 级别 + 完整堆栈，对外不暴露细节 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("unexpected error", e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }
}
