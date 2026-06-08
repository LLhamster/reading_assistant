package com.example.httpreading.api;

public class CommonResponse<T> {

    private int code; // 0 表示成功，非 0 业务错误码
    private String message;
    private T data;

    public CommonResponse() {}

    public CommonResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> CommonResponse<T> success(T data) {
        return new CommonResponse<>(0, "ok", data);
    }

    public static <T> CommonResponse<T> error(int code, String message) {
        return new CommonResponse<>(code, message, null);
    }

    /**
     * 从 ErrorCode 生成响应（使用默认 message）
     */
    public static <T> CommonResponse<T> error(ErrorCode errorCode) {
        return new CommonResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /**
     * 从 ErrorCode 生成响应（覆盖默认 message）
     */
    public static <T> CommonResponse<T> error(ErrorCode errorCode, String message) {
        return new CommonResponse<>(errorCode.getCode(), message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
