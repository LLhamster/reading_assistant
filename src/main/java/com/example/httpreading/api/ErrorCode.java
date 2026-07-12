package com.example.httpreading.api;

/**
 * 统一错误码枚举
 * <p>
 * 结构：{区间}{类别序号}
 * 区间：4xx=客户端错误，5xx=服务端错误
 */
public enum ErrorCode {

    // === 4xx 客户端错误 ===
    BAD_REQUEST(40001, "参数错误", 400),
    VALIDATION_ERROR(40002, "参数校验失败", 400),

    // 认证 401xx
    UNAUTHORIZED(40101, "未登录或token已过期", 401),
    TOKEN_INVALID(40102, "token无效", 401),

    // 权限 403xx
    FORBIDDEN(40301, "无权限访问", 403),

    // 限流 429xx
    TOO_MANY_REQUESTS(42901, "请求过于频繁，请稍后再试", 429),

    // 资源 404xx
    RESOURCE_NOT_FOUND(40401, "资源不存在", 404),
    BOOK_NOT_FOUND(40411, "书籍不存在", 404),
    CHAPTER_NOT_FOUND(40412, "章节不存在", 404),
    READING_RECORD_NOT_FOUND(40413, "阅读记录不存在", 404),

    // 业务冲突 409xx
    DUPLICATE_FAVORITE(40901, "已收藏过该书籍", 409),
    ALREADY_IN_BOOKSHELF(40902, "已在阅读列表中", 409),
    DUPLICATE_USERNAME(40903, "用户名已存在", 409),

    // === 5xx 服务端错误 ===
    INTERNAL_ERROR(50001, "服务器内部错误", 500),
    DB_ERROR(50011, "数据库操作失败", 500),

    // 未知错误（保底）
    UNKNOWN(59999, "未知错误", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * 创建业务异常对象（不抛出，用于 orElseThrow 等需要返回对象的场景）
     */
    public BusinessException toException(String customMsg) {
        return new BusinessException(this.code, customMsg);
    }

    /**
     * 创建业务异常对象（不抛出，使用默认message）
     */
    public BusinessException toException() {
        return new BusinessException(this);
    }

    /**
     * 抛出业务异常（使用默认message）
     */
    public void throwException() {
        throw toException();
    }

    /**
     * 抛出业务异常（覆盖默认message）
     */
    public void throwException(String customMsg) {
        throw toException(customMsg);
    }

    /**
     * 根据code查找枚举值
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode ec : values()) {
            if (ec.code == code) {
                return ec;
            }
        }
        return UNKNOWN;
    }
}
