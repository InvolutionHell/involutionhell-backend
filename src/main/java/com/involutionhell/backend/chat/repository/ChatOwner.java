package com.involutionhell.backend.chat.repository;

/**
 * Chat 行的归属信息——只承载本系统真正在乎的两件事：是否存在 + owner 是谁。
 *
 * 为什么不直接 Optional<Long> 表示 owner：会丢掉"chat 不存在"和"chat 存在但匿名"
 * 这两种状态的差别。匿名 chat 允许继续匿名写；他人 chat 必须拒绝。两态合并会让
 * controller 的归属校验逻辑写不清楚。
 *
 * @param ownerId user_accounts.id；匿名 chat 为 null
 */
public record ChatOwner(Long ownerId) {

    public boolean isAnonymous() {
        return ownerId == null;
    }
}
