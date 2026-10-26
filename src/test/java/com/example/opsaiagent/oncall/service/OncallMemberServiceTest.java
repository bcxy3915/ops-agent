package com.example.opsaiagent.oncall.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.oncall.dto.OncallMemberCreateRequest;
import com.example.opsaiagent.oncall.dto.OncallMemberResponse;
import com.example.opsaiagent.oncall.entity.OncallMemberEntity;
import com.example.opsaiagent.oncall.mapper.OncallMemberMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OncallMemberService 单元测试（P1 规格 7）
 * 覆盖：添加（幂等）、列表筛选、移除、通知收件人解析（toUsers 优先 / L1→BACKUP / L2→ADMIN / 角色兜底）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("值班管理服务")
class OncallMemberServiceTest {

    @Mock
    private OncallMemberMapper memberMapper;

    @InjectMocks
    private OncallMemberService service;

    private OncallMemberEntity member(Long id, String userId, String role) {
        OncallMemberEntity e = new OncallMemberEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setDutyRole(role);
        return e;
    }

    @Test
    @DisplayName("添加：正常插入并返回响应")
    void add_insertsMember() {
        OncallMemberCreateRequest req = new OncallMemberCreateRequest();
        req.setUserId("1001");
        req.setDutyRole("PRIMARY");
        when(memberMapper.selectCount(any())).thenReturn(0L);

        OncallMemberResponse resp = service.add(req);

        ArgumentCaptor<OncallMemberEntity> captor = ArgumentCaptor.forClass(OncallMemberEntity.class);
        verify(memberMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("1001");
        assertThat(captor.getValue().getDutyRole()).isEqualTo("PRIMARY");
        assertThat(resp.getUserId()).isEqualTo("1001");
    }

    @Test
    @DisplayName("添加：同用户同角色重复添加抛异常")
    void add_duplicateThrows() {
        OncallMemberCreateRequest req = new OncallMemberCreateRequest();
        req.setUserId("1001");
        req.setDutyRole("BACKUP");
        when(memberMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.add(req))
                .isInstanceOf(BusinessException.class);
        verify(memberMapper, never()).insert(any());
    }

    @Test
    @DisplayName("列表：按角色筛选")
    void list_filtersByRole() {
        when(memberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(member(1L, "1001", "PRIMARY"), member(2L, "1002", "BACKUP")));
        List<OncallMemberResponse> resp = service.list("PRIMARY");
        assertThat(resp).hasSize(2);
    }

    @Test
    @DisplayName("移除：正常删除")
    void remove_deletes() {
        OncallMemberEntity entity = member(3L, "1003", "PRIMARY");
        entity.setTenantId("t-default");
        when(memberMapper.selectById(3L)).thenReturn(entity);
        when(memberMapper.deleteById(3L)).thenReturn(1);
        service.remove(3L);
        verify(memberMapper).deleteById(3L);
    }

    @Test
    @DisplayName("移除：不存在抛异常")
    void remove_notFoundThrows() {
        when(memberMapper.selectById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.remove(99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("收件人解析：规则显式 toUsers 优先（逗号分隔，保留非空项）")
    void resolveRecipients_toUsersWins() {
        List<String> users = service.resolveRecipients("1001, 1002, abc, 1003", null, null);
        assertThat(users).containsExactly("1001", "1002", "abc", "1003");
        verify(memberMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("收件人解析：L1 升级 → 备值班 BACKUP")
    void resolveRecipients_l1UsesBackup() {
        when(memberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(member(1L, "2002", "BACKUP")));
        List<String> users = service.resolveRecipients(null, "OPERATOR", 1);
        assertThat(users).containsExactly("2002");
    }

    @Test
    @DisplayName("收件人解析：L2 升级 → ADMIN")
    void resolveRecipients_l2UsesAdmin() {
        when(memberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(member(1L, "3001", "ADMIN")));
        List<String> users = service.resolveRecipients(null, null, 2);
        assertThat(users).containsExactly("3001");
    }

    @Test
    @DisplayName("收件人解析：OPERATOR 角色且无级别 → 主值班 PRIMARY")
    void resolveRecipients_operatorUsesPrimary() {
        when(memberMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(member(1L, "1001", "PRIMARY")));
        List<String> users = service.resolveRecipients(null, "OPERATOR", null);
        assertThat(users).containsExactly("1001");
    }

    @Test
    @DisplayName("收件人解析：值班表为空且无显式用户 → 空列表（调用方角色兜底）")
    void resolveRecipients_emptyTableReturnsEmpty() {
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.resolveRecipients(null, "OPERATOR", null)).isEmpty();
        assertThat(service.resolveRecipients("", "", null)).isEmpty();
    }
}
