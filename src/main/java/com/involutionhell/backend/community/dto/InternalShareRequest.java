package com.involutionhell.backend.community.dto;

/**
 * 机器人桥接渠道的提交请求体（/api/community/links/internal）。
 *
 * submitterLabel: 原始分享人名（Discord 昵称），由桥接服务填写；展示在 recommendation 里
 * url:            原始 URL（规范化交由后端 UrlNormalizer）
 * recommendation: 可选的推荐语（机器人自动渠道通常为空）
 */
public record InternalShareRequest(String url, String recommendation, String submitterLabel) {
}
