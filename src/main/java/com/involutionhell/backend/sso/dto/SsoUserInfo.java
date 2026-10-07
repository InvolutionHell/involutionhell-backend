package com.involutionhell.backend.sso.dto;

/**
 * 换码成功后交给 client 的用户资料。故意只有这四项：不给 email、角色、权限，
 * 也不给任何 IH 的 token（INV-010）。加字段前先改 SECURITY.md。
 *
 * @param sub user_accounts.id 的十进制字符串
 */
public record SsoUserInfo(String sub, String username, String displayName, String avatarUrl) {
}
