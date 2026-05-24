package com.involutionhell.backend.events.service;

import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.repository.EventInterestRepository;
import com.involutionhell.backend.events.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * EventService 单元测试。
 * 主要验证对 EventRepository 和 EventInterestRepository 的委托逻辑。
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTests {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventInterestRepository interestRepository;

    @InjectMocks
    private EventService eventService;

    // ────────────────────────────────────────────────────────────────────────
    // ── EventRepository 委托测试 ─────────────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void listPublic_delegatesToRepository() {
        Event e1 = new Event(1L, "T1", "", null, null, null, null, null, null, null, "published", null, null, null);
        when(eventRepository.findPublic()).thenReturn(List.of(e1));

        List<Event> result = eventService.listPublic();

        assertThat(result).hasSize(1).contains(e1);
        verify(eventRepository).findPublic();
    }

    @Test
    void listAllForAdmin_delegatesToRepository() {
        Event e1 = new Event(1L, "T1", "", null, null, null, null, null, null, null, "draft", null, null, null);
        when(eventRepository.findAllForAdmin()).thenReturn(List.of(e1));

        List<Event> result = eventService.listAllForAdmin();

        assertThat(result).hasSize(1).contains(e1);
        verify(eventRepository).findAllForAdmin();
    }

    @Test
    void findById_delegatesToRepository() {
        Event e1 = new Event(1L, "T1", "", null, null, null, null, null, null, null, "published", null, null, null);
        when(eventRepository.findById(1L)).thenReturn(Optional.of(e1));

        Optional<Event> result = eventService.findById(1L);

        assertThat(result).isPresent().contains(e1);
        verify(eventRepository).findById(1L);
    }

    @Test
    void create_delegatesToRepository() {
        Event draft = new Event(null, "New", "", null, null, null, null, null, null, null, "draft", null, null, null);
        Event created = new Event(1L, "New", "", null, null, null, null, null, null, null, "draft", null, Instant.now(), Instant.now());
        when(eventRepository.insert(draft)).thenReturn(created);

        Event result = eventService.create(draft);

        assertThat(result).isEqualTo(created);
        verify(eventRepository).insert(draft);
    }

    @Test
    void update_delegatesToRepository() {
        Event updateData = new Event(1L, "Updated", "", null, null, null, null, null, null, null, "published", null, Instant.now(), null);
        Event updated = new Event(1L, "Updated", "", null, null, null, null, null, null, null, "published", null, Instant.now(), Instant.now());
        when(eventRepository.update(updateData)).thenReturn(updated);

        Event result = eventService.update(updateData);

        assertThat(result).isEqualTo(updated);
        verify(eventRepository).update(updateData);
    }

    @Test
    void delete_delegatesToRepository() {
        eventService.delete(1L);
        verify(eventRepository).deleteById(1L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ── EventInterestRepository 委托测试 ──────────────────────────────────────
    // ────────────────────────────────────────────────────────────────────────

    @Test
    void countInterest_delegatesToRepository() {
        when(interestRepository.countByEvent(100L)).thenReturn(42L);

        long result = eventService.countInterest(100L);

        assertThat(result).isEqualTo(42L);
        verify(interestRepository).countByEvent(100L);
    }

    @Test
    void countInterestByEventIds_delegatesToRepository() {
        Set<Long> ids = Set.of(100L, 200L);
        Map<Long, Long> expectedMap = Map.of(100L, 10L, 200L, 5L);
        when(interestRepository.countByEventIds(ids)).thenReturn(expectedMap);

        Map<Long, Long> result = eventService.countInterestByEventIds(ids);

        assertThat(result).isEqualTo(expectedMap);
        verify(interestRepository).countByEventIds(ids);
    }

    @Test
    void countInterestByEventIds_emptyCollection_returnsEmptyMapAndDelegates() {
        Set<Long> ids = Set.of();
        when(interestRepository.countByEventIds(ids)).thenReturn(Map.of());

        Map<Long, Long> result = eventService.countInterestByEventIds(ids);

        assertThat(result).isEmpty();
        verify(interestRepository).countByEventIds(ids);
    }

    @Test
    void isInterested_delegatesToRepository() {
        when(interestRepository.isInterested(100L, 1L)).thenReturn(true);
        when(interestRepository.isInterested(100L, 2L)).thenReturn(false);

        assertThat(eventService.isInterested(100L, 1L)).isTrue();
        assertThat(eventService.isInterested(100L, 2L)).isFalse();

        verify(interestRepository).isInterested(100L, 1L);
        verify(interestRepository).isInterested(100L, 2L);
    }

    @Test
    void markInterested_delegatesToRepository() {
        eventService.markInterested(100L, 1L);
        verify(interestRepository).add(100L, 1L);
    }

    @Test
    void unmarkInterested_delegatesToRepository() {
        eventService.unmarkInterested(100L, 1L);
        verify(interestRepository).remove(100L, 1L);
    }
}
