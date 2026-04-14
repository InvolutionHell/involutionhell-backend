package com.involutionhell.backend.analytics.service;

/**
 * 调用 GA4 Data API 失败时抛出。由 GlobalExceptionHandler 捕获并返回 503，
 * 避免把 gRPC/Google 库的原始异常栈暴露给客户端。
 */
public class Ga4UnavailableException extends RuntimeException {
    public Ga4UnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
