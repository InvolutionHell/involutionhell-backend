package com.involutionhell.backend.community.dto;

/**
 * 用户提交分享链接的请求体。
 * recommendation 可空（没有推荐语也允许提交，UGC 习惯）。
 */
public record SharedLinkRequest(String url, String recommendation) {
}
