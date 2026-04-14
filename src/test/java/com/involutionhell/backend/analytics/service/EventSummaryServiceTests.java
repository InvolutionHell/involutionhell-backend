package com.involutionhell.backend.analytics.service;

import com.involutionhell.backend.analytics.dto.EventSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EventSummaryService 单元测试，使用 Mockito mock JdbcTemplate，
 * 不依赖真实数据库，专注测试 window 参数解析和 SQL 传参逻辑。
 */
@ExtendWith(MockitoExtension.class)
class EventSummaryServiceTests {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private EventSummaryService service;

    @BeforeEach
    void setUp() {
        service = new EventSummaryService(jdbcTemplate);
    }

    // ---- window 参数规范化测试 ----
    // normalize 是 public static，测试直接调类方法即可

    @Test
    @DisplayName("normalize：入参 7d 返回 7d")
    void normalizeReturns7dWhenInputIs7d() {
        assertThat(EventSummaryService.normalize("7d")).isEqualTo("7d");
    }

    @Test
    @DisplayName("normalize：入参 30d 返回 30d")
    void normalizeReturns30dWhenInputIs30d() {
        assertThat(EventSummaryService.normalize("30d")).isEqualTo("30d");
    }

    @Test
    @DisplayName("normalize：入参 all 返回 all")
    void normalizeReturnsAllWhenInputIsAll() {
        assertThat(EventSummaryService.normalize("all")).isEqualTo("all");
    }

    @Test
    @DisplayName("normalize：入参 null 回退到 30d")
    void normalizeFallsBackTo30dWhenInputIsNull() {
        assertThat(EventSummaryService.normalize(null)).isEqualTo("30d");
    }

    @Test
    @DisplayName("normalize：非法值回退到 30d")
    void normalizeFallsBackTo30dWhenInputIsInvalid() {
        assertThat(EventSummaryService.normalize("invalid")).isEqualTo("30d");
    }

    // ---- SQL 聚合参数传递测试 ----

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("summarize：7d 窗口传入正确 interval 参数")
    void summarizeWith7dPassesCorrectInterval() {
        List<EventSummaryDto> fakeResult = List.of(
                new EventSummaryDto("page_view", 100L, 30L)
        );
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(fakeResult);

        List<EventSummaryDto> result = service.summarize("7d");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().eventType()).isEqualTo("page_view");
        assertThat(result.getFirst().count()).isEqualTo(100L);

        // 验证 interval 参数传的是 "7 days"
        ArgumentCaptor<Object> intervalCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), intervalCaptor.capture());
        assertThat(intervalCaptor.getValue()).isEqualTo("7 days");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("summarize：30d 窗口传入正确 interval 参数")
    void summarizeWith30dPassesCorrectInterval() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of());

        service.summarize("30d");

        ArgumentCaptor<Object> intervalCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), intervalCaptor.capture());
        assertThat(intervalCaptor.getValue()).isEqualTo("30 days");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("summarize：all 窗口不传 interval，走无时间条件 SQL")
    void summarizeWithAllDoesNotPassInterval() {
        List<EventSummaryDto> fakeResult = List.of(
                new EventSummaryDto("page_view", 500L, 80L),
                new EventSummaryDto("agent_welcome", 120L, 40L)
        );
        // "all" 分支调用无可变参的重载
        when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenReturn(fakeResult);

        List<EventSummaryDto> result = service.summarize("all");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).eventType()).isEqualTo("page_view");
        assertThat(result.get(1).eventType()).isEqualTo("agent_welcome");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("summarize：非法 window 回退到 30d")
    void summarizeWithInvalidWindowFallsBackTo30d() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any()))
                .thenReturn(List.of());

        service.summarize("xyz");

        ArgumentCaptor<Object> intervalCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), intervalCaptor.capture());
        // 非法值回退 30d，interval 应为 "30 days"
        assertThat(intervalCaptor.getValue()).isEqualTo("30 days");
    }
}
