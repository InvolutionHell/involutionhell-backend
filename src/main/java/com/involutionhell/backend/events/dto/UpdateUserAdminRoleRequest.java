package com.involutionhell.backend.events.dto;

/**
 * 超管界面的"切换用户 admin 角色"入参。
 *
 * 刻意只暴露一个布尔字段 admin：
 *   true  → 授予 admin 角色（普通用户变管理员）
 *   false → 撤销 admin 角色（降级回普通用户）
 *
 * 为什么不让前端直接传 roles 列表：
 *   - 如果暴露 roles 字段，前端可以伪造 "superadmin"、"owner" 等未定义角色，
 *     后端就要做严格白名单，不如直接收一个布尔动作
 *   - superadmin 角色永远不允许通过 API 授予 / 撤销（防止误操作把唯一站长
 *     降级锁死后台）；想升级新的 superadmin 只能走数据库
 *   - user 角色由 AuthService 在 OAuth 登录时自动挂上，前端不用管
 */
public record UpdateUserAdminRoleRequest(boolean admin) {}
