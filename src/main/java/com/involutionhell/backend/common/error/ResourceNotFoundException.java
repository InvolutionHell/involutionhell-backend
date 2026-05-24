package com.involutionhell.backend.common.error;

/**
 * 业务资源不存在（404 语义）。
 *
 * 用独立类型而非 IllegalArgumentException：
 * IllegalArgumentException 在 GlobalExceptionHandler 里统一映射成 400，
 * 找不到资源应是 404，不是请求参数错误。
 *
 * GlobalExceptionHandler 把它转成 404 + 自定义 message。
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
