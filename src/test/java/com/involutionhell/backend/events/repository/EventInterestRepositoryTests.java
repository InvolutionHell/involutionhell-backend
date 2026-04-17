package com.involutionhell.backend.events.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * EventInterestRepository 集成测试。
 *
 * 重点覆盖 CR 要求的场景：
 *   - countByEventIds 多 event 准确聚合
 *   - 未出现在结果集中的 id 对外要能 getOrDefault(0)
 *   - 传入空集合直接短路，不打 DB
 *   - add / remove 幂等
 *   - ON DELETE CASCADE（H2 test-schema 和生产对齐，删 event 自动清 interest）
 *
 * 和 JdbcUserAccountRepositoryTests 同一套 H2 + test-schema 模式，
 * @Transactional 自动回滚保证互不污染。
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:test-schema.sql"
        })
@ActiveProfiles("test")
@Transactional
class EventInterestRepositoryTests {

    @Autowired private EventInterestRepository repository;
    @Autowired private JdbcTemplate jdbc;

    /** 每个用例开始前种 2 条活动 + 拿 2 个已有种子用户（alice id=2, admin id=1）。 */
    @BeforeEach
    void seedEvents() {
        jdbc.update(
                "INSERT INTO events (title, description, status) VALUES (?, '', 'published')",
                "event-A");
        jdbc.update(
                "INSERT INTO events (title, description, status) VALUES (?, '', 'published')",
                "event-B");
    }

    private long eventId(String title) {
        return jdbc.queryForObject("SELECT id FROM events WHERE title = ?", Long.class, title);
    }

    @Test
    void add_isIdempotent_and_count_reflectsUniqueUsers() {
        long a = eventId("event-A");
        repository.add(a, 1L);
        repository.add(a, 1L); // 重复 add 不应增加计数
        repository.add(a, 2L);

        assertThat(repository.countByEvent(a)).isEqualTo(2L);
    }

    @Test
    void remove_isIdempotent() {
        long a = eventId("event-A");
        repository.add(a, 1L);
        repository.remove(a, 1L);
        repository.remove(a, 1L); // 再删一次不报错

        assertThat(repository.countByEvent(a)).isZero();
    }

    @Test
    void countByEventIds_aggregatesAcrossEvents() {
        long a = eventId("event-A");
        long b = eventId("event-B");
        repository.add(a, 1L);
        repository.add(a, 2L);
        repository.add(b, 2L);

        Map<Long, Long> result = repository.countByEventIds(List.of(a, b));
        assertThat(result).containsEntry(a, 2L).containsEntry(b, 1L);
    }

    @Test
    void countByEventIds_missingEventId_returnsNoEntry() {
        long a = eventId("event-A");
        long b = eventId("event-B");
        repository.add(a, 1L);
        // event-B 没人感兴趣，不应在返回 map 里；调用方自己 getOrDefault(0L)

        Map<Long, Long> result = repository.countByEventIds(List.of(a, b));
        assertThat(result).containsEntry(a, 1L).doesNotContainKey(b);
        assertThat(result.getOrDefault(b, 0L)).isZero();
    }

    @Test
    void countByEventIds_emptyInput_doesNotHitDb() {
        // 空集合要短路，不要发出 "WHERE event_id IN ()" 那种非法 SQL
        Map<Long, Long> result = repository.countByEventIds(Set.of());
        assertThat(result).isEmpty();
    }

    @Test
    void isInterested_tracksSingleUser() {
        long a = eventId("event-A");
        repository.add(a, 1L);

        assertThat(repository.isInterested(a, 1L)).isTrue();
        assertThat(repository.isInterested(a, 2L)).isFalse();
    }

    @Test
    void cascadeDelete_removesInterestsWhenEventDropped() {
        long a = eventId("event-A");
        repository.add(a, 1L);
        repository.add(a, 2L);

        jdbc.update("DELETE FROM events WHERE id = ?", a);

        // FK ON DELETE CASCADE 应该把两条 interest 都清掉
        Integer remaining =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM event_interests WHERE event_id = ?",
                        Integer.class,
                        a);
        assertThat(remaining).isZero();
    }
}
