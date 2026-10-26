package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.notification.NotificationChannelType;
import com.example.opsaiagent.notification.NotifierRegistry;
import com.example.opsaiagent.notification.dto.NotificationChannelRequest;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.notification.mapper.NotificationChannelMapper;
import com.example.opsaiagent.notification.mapper.NotificationRecordMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知渠道配置管理测试（T11）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("通知渠道管理")
class NotificationChannelServiceTest {

    @Mock
    private NotificationChannelMapper channelMapper;
    @Mock
    private NotificationRecordMapper recordMapper;
    @Mock
    private NotifierRegistry notifierRegistry;
    @Mock
    private NotificationCenter notificationCenter;

    @Spy
    private NotificationScope notificationScope = new NotificationScope(null);

    @InjectMocks
    private NotificationChannelService channelService;

    private NotificationChannelConfigEntity entity(Long id, String name, String type) {
        NotificationChannelConfigEntity e = new NotificationChannelConfigEntity();
        e.setId(id);
        e.setName(name);
        e.setType(type);
        e.setEnabled(true);
        e.setConfig("{\"webhookUrl\":\"https://hook\"}");
        return e;
    }

    private NotificationChannelRequest request(String name, String type) {
        NotificationChannelRequest req = new NotificationChannelRequest();
        req.setName(name);
        req.setType(type);
        req.setEnabled(true);
        req.setConfig("{\"webhookUrl\":\"https://hook\"}");
        return req;
    }

    // ==================== list ====================

    @Test
    @DisplayName("列表：无条件返回全部")
    void list_all() {
        when(channelMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(entity(1L, "飞书", "FEISHU_WEBHOOK")));
        List<NotificationChannelConfigEntity> result = channelService.list(null, null);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("列表：按类型 / 启用过滤")
    void list_filtered() {
        when(channelMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(channelService.list("EMAIL", true)).isEmpty();
        assertThat(channelService.list("", null)).isEmpty();
        verify(channelMapper, org.mockito.Mockito.times(2)).selectList(any(Wrapper.class));
    }

    // ==================== create ====================

    @Test
    @DisplayName("创建：合法请求创建成功")
    void create_success() {
        when(channelMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(notifierRegistry.supports(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(true);
        NotificationChannelConfigEntity created = channelService.create(request("飞书", "FEISHU_WEBHOOK"));
        assertThat(created.getName()).isEqualTo("飞书");
        assertThat(created.getEnabled()).isTrue();
        verify(channelMapper).insert(created);
    }

    @Test
    @DisplayName("创建：名称重复抛业务异常")
    void create_duplicateName() {
        when(channelMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(notifierRegistry.supports(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(true);
        assertThatThrownBy(() -> channelService.create(request("飞书", "FEISHU_WEBHOOK")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_NAME_EXISTS);
    }

    @Test
    @DisplayName("创建：非法类型抛业务异常")
    void create_invalidType() {
        assertThatThrownBy(() -> channelService.create(request("x", "BAD_TYPE")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_INVALID);
    }

    @Test
    @DisplayName("创建：类型合法但无适配器抛业务异常")
    void create_unsupportedType() {
        when(notifierRegistry.supports(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(false);
        assertThatThrownBy(() -> channelService.create(request("飞书", "FEISHU_WEBHOOK")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_INVALID);
    }

    // ==================== update ====================

    @Test
    @DisplayName("更新：成功修改字段")
    void update_success() {
        NotificationChannelConfigEntity existing = entity(1L, "旧名", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(existing);
        when(channelMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(notifierRegistry.supports(NotificationChannelType.EMAIL)).thenReturn(true);
        NotificationChannelRequest req = request("新名", "EMAIL");
        req.setEnabled(false);

        NotificationChannelConfigEntity updated = channelService.update(1L, req);

        assertThat(updated.getName()).isEqualTo("新名");
        assertThat(updated.getType()).isEqualTo("EMAIL");
        assertThat(updated.getEnabled()).isFalse();
        verify(channelMapper).updateById(existing);
    }

    @Test
    @DisplayName("更新：渠道不存在抛业务异常")
    void update_notFound() {
        when(channelMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> channelService.update(99L, request("x", "FEISHU_WEBHOOK")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_NOT_FOUND);
    }

    @Test
    @DisplayName("更新：改名与他人冲突抛业务异常")
    void update_nameConflict() {
        NotificationChannelConfigEntity existing = entity(1L, "旧名", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(existing);
        NotificationChannelConfigEntity other = entity(2L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectOne(any(Wrapper.class))).thenReturn(other);
        when(notifierRegistry.supports(NotificationChannelType.FEISHU_WEBHOOK)).thenReturn(true);

        assertThatThrownBy(() -> channelService.update(1L, request("飞书", "FEISHU_WEBHOOK")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_NAME_EXISTS);
    }

    // ==================== setEnabled / delete / testSend ====================

    @Test
    @DisplayName("启停：切换 enabled")
    void setEnabled() {
        NotificationChannelConfigEntity existing = entity(1L, "飞书", "FEISHU_WEBHOOK");
        when(channelMapper.selectById(1L)).thenReturn(existing);
        NotificationChannelConfigEntity result = channelService.setEnabled(1L, false);
        assertThat(result.getEnabled()).isFalse();
        verify(channelMapper).updateById(existing);
    }

    @Test
    @DisplayName("删除：删除渠道及其发送记录")
    void delete() {
        when(channelMapper.selectById(1L)).thenReturn(entity(1L, "飞书", "FEISHU_WEBHOOK"));
        channelService.delete(1L);
        verify(channelMapper).deleteById(1L);
        verify(recordMapper).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("删除：渠道不存在抛业务异常")
    void delete_notFound() {
        when(channelMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> channelService.delete(1L))
                .isInstanceOf(BusinessException.class);
        verify(recordMapper, never()).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("测试发送：委托通知中心并返回记录")
    void testSend() {
        when(channelMapper.selectById(1L)).thenReturn(entity(1L, "飞书", "FEISHU_WEBHOOK"));
        NotificationRecordEntity record = new NotificationRecordEntity();
        record.setStatus("SUCCESS");
        when(notificationCenter.sendTest(1L)).thenReturn(record);

        NotificationRecordEntity result = channelService.testSend(1L);
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("测试发送：渠道不存在抛业务异常")
    void testSend_notFound() {
        when(channelMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> channelService.testSend(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_NOT_FOUND);
    }

    @Test
    @DisplayName("getById：不存在抛业务异常")
    void getById_notFound() {
        when(channelMapper.selectById(anyLong())).thenReturn(null);
        assertThatThrownBy(() -> channelService.getById(1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_CHANNEL_NOT_FOUND);
    }
}
