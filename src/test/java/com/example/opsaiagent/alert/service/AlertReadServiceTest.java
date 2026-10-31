package com.example.opsaiagent.alert.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.alert.entity.AlertEventEntity;
import com.example.opsaiagent.alert.entity.AlertReadEntity;
import com.example.opsaiagent.alert.mapper.AlertEventMapper;
import com.example.opsaiagent.alert.mapper.AlertReadMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AlertReadService 单元测试
 * 覆盖：单条/批量已读、未读计数口径（规格 1.2：FIRING/ACK/SILENCED 且未读、
 * 状态回退视为新事件 readAt >= firedAt）、readMap / readIds
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("告警已读服务")
class AlertReadServiceTest {

    @Mock
    private AlertReadMapper readMapper;

    @Mock
    private AlertEventMapper eventMapper;

    @InjectMocks
    private AlertReadService service;

    private AlertEventEntity event(Long id, String status, LocalDateTime firedAt) {
        AlertEventEntity e = new AlertEventEntity();
        e.setId(id);
        e.setStatus(status);
        e.setFiredAt(firedAt);
        return e;
    }

    private AlertReadEntity readRow(Long eventId, LocalDateTime readAt) {
        AlertReadEntity r = new AlertReadEntity();
        r.setUserId("admin");
        r.setAlertEventId(eventId);
        r.setReadAt(readAt);
        return r;
    }

    // ==================== markRead / markAllRead ====================

    @Test
    @DisplayName("markRead：插入已读记录（带当前租户）")
    void markRead_inserts() {
        service.markRead(1L, "admin");

        verify(readMapper).insertIgnore("t-default", "admin", 1L);
    }

    @Test
    @DisplayName("markRead：userId 为空跳过")
    void markRead_blankUserSkipped() {
        service.markRead(1L, " ");

        verify(readMapper, never()).insertIgnore(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("markAllRead：空列表跳过，有列表逐条插入")
    void markAllRead_batch() {
        service.markAllRead(List.of(1L, 2L), "admin");

        verify(readMapper).insertIgnore("t-default", "admin", 1L);
        verify(readMapper).insertIgnore("t-default", "admin", 2L);
    }

    @Test
    @DisplayName("markAllRead：空列表/空用户不执行")
    void markAllRead_emptySkipped() {
        service.markAllRead(List.of(), "admin");
        service.markAllRead(List.of(1L), null);

        verify(readMapper, never()).insertIgnore(anyString(), anyString(), any());
    }

    // ==================== unreadCount（规格 1.2 口径） ====================

    @Test
    @DisplayName("unreadCount：无待处理事件返回 0")
    void unreadCount_noPending() {
        when(eventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.unreadCount("admin")).isZero();
    }

    @Test
    @DisplayName("unreadCount：FIRING 未读计数 1")
    void unreadCount_firingUnread() {
        when(eventMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(event(1L, "FIRING", LocalDateTime.now().minusMinutes(5))));
        when(readMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThat(service.unreadCount("admin")).isEqualTo(1);
    }

    @Test
    @DisplayName("unreadCount：已读事件不计入未读")
    void unreadCount_readExcluded() {
        when(eventMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(event(1L, "ACKNOWLEDGED", LocalDateTime.now().minusMinutes(5))));
        when(readMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readRow(1L, LocalDateTime.now().minusMinutes(1))));

        assertThat(service.unreadCount("admin")).isZero();
    }

    @Test
    @DisplayName("unreadCount：readAt < firedAt（状态回退）视为未读")
    void unreadCount_staleReadCountsUnread() {
        LocalDateTime firedAt = LocalDateTime.now().minusMinutes(2);
        when(eventMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(event(1L, "SILENCED", firedAt)));
        // 已读时间早于重新触发时间 -> 视为新事件未读
        when(readMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readRow(1L, firedAt.minusMinutes(10))));

        assertThat(service.unreadCount("admin")).isEqualTo(1);
    }

    @Test
    @DisplayName("unreadCount：空白 userId 返回 0 且不查库")
    void unreadCount_blankUser() {
        assertThat(service.unreadCount(null)).isZero();
        verify(eventMapper, never()).selectList(any(Wrapper.class));
    }

    // ==================== readMap ====================

    @Test
    @DisplayName("readMap：返回 eventId -> readAt 映射")
    void readMap_maps() {
        when(readMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readRow(1L, LocalDateTime.now())));

        Map<Long, LocalDateTime> map = service.readMap("admin", List.of(1L));

        assertThat(map).containsKey(1L);
    }

    @Test
    @DisplayName("readMap：空事件列表返回空映射")
    void readMap_empty() {
        assertThat(service.readMap("admin", List.of())).isEmpty();
    }

    // ==================== readIds（有效已读） ====================

    @Test
    @DisplayName("readIds：readAt >= firedAt 记为有效已读")
    void readIds_validRead() {
        when(readMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readRow(1L, LocalDateTime.now())));
        Map<Long, LocalDateTime> firedMap = Map.of(1L, LocalDateTime.now().minusMinutes(10));

        Set<Long> readIds = service.readIds("admin", List.of(1L), firedMap);

        assertThat(readIds).contains(1L);
    }

    @Test
    @DisplayName("readIds：readAt < firedAt 不记有效已读")
    void readIds_staleNotRead() {
        LocalDateTime firedAt = LocalDateTime.now().minusMinutes(1);
        when(readMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(readRow(1L, firedAt.minusMinutes(30))));
        Map<Long, LocalDateTime> firedMap = Map.of(1L, firedAt);

        assertThat(service.readIds("admin", List.of(1L), firedMap)).doesNotContain(1L);
    }
}
