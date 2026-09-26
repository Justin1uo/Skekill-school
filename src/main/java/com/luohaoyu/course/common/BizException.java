package com.luohaoyu.course.common;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由 {@link GlobalExceptionHandler} 统一转为 Result。
 * 只用于"预期内的业务拒绝"（课程不存在、名额已满等），不用于程序 bug。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detailMsg) {
        super(detailMsg);
        this.errorCode = errorCode;
    }
}
