package com.involutionhell.backend.common.api;

public record ApiResponse<T>(boolean success, String message, T data) {

    /**
     * 构造一个成功响应，并附带提示语与数据
     */
    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    /**
     * 构造一个默认提示语为 ok 的成功响应
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ok("ok", data);
    }

    /**
     * 构造一个仅包含提示语的成功响应
     */
    public static ApiResponse<Void> okMessage(String message) {
        return ok(message, null);
    }

    /**
     * 构造一个失败响应
     */
    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}
