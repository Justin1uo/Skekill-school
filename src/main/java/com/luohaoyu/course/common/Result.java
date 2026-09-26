package com.luohaoyu.course.common;

import lombok.Data;

/**
 * 统一响应体：{"code":0,"msg":"ok","data":{...}}
 * code=0 成功；非 0 见 {@link ErrorCode}。
 */
@Data
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = ErrorCode.SUCCESS.getCode();
        r.msg = ErrorCode.SUCCESS.getMsg();
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.msg = errorCode.getMsg();
        return r;
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detailMsg) {
        Result<T> r = new Result<>();
        r.code = errorCode.getCode();
        r.msg = detailMsg;
        return r;
    }
}
