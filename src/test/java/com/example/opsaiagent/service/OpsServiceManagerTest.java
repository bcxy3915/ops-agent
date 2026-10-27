package com.example.opsaiagent.service;

import com.example.opsaiagent.ops.entity.OpsServiceEntity;
import com.example.opsaiagent.ops.mapper.OpsServiceMapper;
import com.example.opsaiagent.ops.service.OpsServiceManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OpsServiceManager 单元测试
 * 覆盖：查询 / 保存或更新 / 删除 / 更新状态
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("服务管理")
class OpsServiceManagerTest {

    @Mock
    private OpsServiceMapper mapper;

    @InjectMocks
    private OpsServiceManager manager;

    private OpsServiceEntity service(String name) {
        OpsServiceEntity e = new OpsServiceEntity();
        e.setId("id-" + name);
        e.setName(name);
        e.setBaseUrl("http://" + name + ":8080");
        e.setEnv("prod");
        e.setStatus("UP");
        return e;
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("listAll：返回全部服务")
    void listAll() {
        when(mapper.selectList(null)).thenReturn(List.of(service("a"), service("b")));

        List<OpsServiceEntity> result = manager.listAll();

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findByName：存在返回服务")
    void findByName_found() {
        when(mapper.selectOne(any())).thenReturn(service("a"));

        assertThat(manager.findByName("a")).isPresent();
    }

    @Test
    @DisplayName("findByName：不存在返回空")
    void findByName_notFound() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(manager.findByName("x")).isEmpty();
    }

    @Test
    @DisplayName("findByEnv：按环境过滤")
    void findByEnv() {
        when(mapper.selectList(any())).thenReturn(List.of(service("a")));

        assertThat(manager.findByEnv("prod")).hasSize(1);
    }

    @Test
    @DisplayName("findByStatus：按状态过滤")
    void findByStatus() {
        when(mapper.selectList(any())).thenReturn(List.of(service("a")));

        assertThat(manager.findByStatus("UP")).hasSize(1);
    }

    // ==================== saveOrUpdate ====================

    @Test
    @DisplayName("saveOrUpdate：已存在时更新")
    void saveOrUpdate_existing_updates() {
        when(mapper.selectOne(any())).thenReturn(service("a"));
        OpsServiceEntity entity = service("a");

        manager.saveOrUpdate(entity);

        assertThat(entity.getId()).isEqualTo("id-a");
        verify(mapper).updateById(entity);
        verify(mapper, never()).insert(any(OpsServiceEntity.class));
    }

    @Test
    @DisplayName("saveOrUpdate：不存在时插入")
    void saveOrUpdate_new_inserts() {
        when(mapper.selectOne(any())).thenReturn(null);

        manager.saveOrUpdate(service("new"));

        verify(mapper).insert(any(OpsServiceEntity.class));
        verify(mapper, never()).updateById(any(OpsServiceEntity.class));
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("deleteByName：删除成功返回 true")
    void deleteByName_success() {
        when(mapper.delete(any())).thenReturn(1);

        assertThat(manager.deleteByName("a")).isTrue();
    }

    @Test
    @DisplayName("deleteByName：未删除返回 false")
    void deleteByName_notFound() {
        when(mapper.delete(any())).thenReturn(0);

        assertThat(manager.deleteByName("x")).isFalse();
    }

    // ==================== 更新状态 ====================

    @Test
    @DisplayName("updateStatus：设置状态与检查时间")
    void updateStatus() {
        manager.updateStatus("id-a", "DOWN");

        verify(mapper).updateById(any(OpsServiceEntity.class));
    }

    // ==================== 简单保存/更新 ====================

    @Test
    @DisplayName("save：插入服务")
    void save() {
        manager.save(service("a"));

        verify(mapper).insert(any(OpsServiceEntity.class));
    }

    @Test
    @DisplayName("update：更新服务")
    void update() {
        manager.update(service("a"));

        verify(mapper).updateById(any(OpsServiceEntity.class));
    }

    // ==================== P2 租户/部门 ====================

    @Test
    @DisplayName("listScoped：按租户/部门/环境/状态过滤")
    void listScoped() {
        when(mapper.selectList(any())).thenReturn(List.of(service("a")));

        List<OpsServiceEntity> result = manager.listScoped(
                "t1", List.of(1L, 2L), "prod", "UP");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listScoped：部门范围为空时直接返回空")
    void listScoped_emptyDept() {
        assertThat(manager.listScoped("t1", List.of(), null, null)).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("findByName：按租户查询")
    void findByName_tenant() {
        when(mapper.selectOne(any())).thenReturn(service("a"));

        assertThat(manager.findByName("t1", "a")).isPresent();
    }

    @Test
    @DisplayName("deleteByName：按租户删除")
    void deleteByName_tenant() {
        when(mapper.delete(any())).thenReturn(1);

        assertThat(manager.deleteByName("t1", "a")).isTrue();
    }
}
