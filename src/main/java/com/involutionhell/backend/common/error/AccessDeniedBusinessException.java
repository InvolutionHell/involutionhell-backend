package com.involutionhell.backend.common.error;

/**
 * 业务规则触发的"访问被拒"——不是 Sa-Token 缺权限/缺角色，而是业务对象归属
 * 不匹配（例如试图写入他人的 chat 历史、修改他人的资源）。
 *
 * 用独立类型而不是复用 NotPermissionException：
 *   1. NotPermissionException 的 message 模板由 GlobalExceptionHandler 强制成
 *      "拒绝访问: 缺少权限 [...]"，业务语义会被吞
 *   2. 单独类型让调用方一眼看出是"业务归属"问题而非"角色权限"问题
 *
 * GlobalExceptionHandler 把它转成 403 + 自定义 message。
 */
public class AccessDeniedBusinessException extends RuntimeException {

    public AccessDeniedBusinessException(String message) {
        super(message);
    }
}
