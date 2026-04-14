package com.involutionhell.backend.analytics.dto;

/**
 * 事件聚合摘要 DTO。
 * uniqueUsers 只统计非 null 的 userId（匿名用户不计入），
 * 这样数字更具实际意义，匿名流量可通过 count 减去有用户的事件推算。
 */
public record EventSummaryDto(
        String eventType,
        long count,
        long uniqueUsers
) {}
