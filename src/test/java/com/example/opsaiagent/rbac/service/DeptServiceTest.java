package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.entity.DeptEntity;
import com.example.opsaiagent.rbac.mapper.DeptMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DeptService 单元测试。
 *
 * <p>覆盖：按 ID 查询 / 子树查询 / 空参数 / 跨租户 / 部门不存在。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部门服务")
class DeptServiceTest {

    @Mock
    private DeptMapper deptMapper;

    @InjectMocks
    private DeptService deptService;

    @Test
    @DisplayName("findById：空 ID 返回空")
    void findById_null() {
        assertThat(deptService.findById(null)).isEmpty();
        verifyNoInteractions(deptMapper);
    }

    @Test
    @DisplayName("findById：存在返回部门")
    void findById_found() {
        DeptEntity dept = dept(5L, "t-default", "/1/5/");
        when(deptMapper.selectById(5L)).thenReturn(dept);

        assertThat(deptService.findById(5L)).contains(dept);
    }

    @Test
    @DisplayName("subtreeIds：租户/部门为空返回空列表")
    void subtreeIds_nullInputs() {
        assertThat(deptService.subtreeIds(null, 1L)).isEmpty();
        assertThat(deptService.subtreeIds("t-default", null)).isEmpty();
        verifyNoInteractions(deptMapper);
    }

    @Test
    @DisplayName("subtreeIds：跨租户返回空列表")
    void subtreeIds_crossTenant() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "other", "/1/5/"));

        assertThat(deptService.subtreeIds("t-default", 5L)).isEmpty();
    }

    @Test
    @DisplayName("subtreeIds：返回当前部门及全部下级")
    void subtreeIds_returnsSubtree() {
        DeptEntity root = dept(5L, "t-default", "/1/5/");
        DeptEntity child = dept(6L, "t-default", "/1/5/6/");
        when(deptMapper.selectById(5L)).thenReturn(root);
        when(deptMapper.selectList(any())).thenReturn(List.of(root, child));

        List<Long> ids = deptService.subtreeIds("t-default", 5L);

        assertThat(ids).containsExactly(5L, 6L);
    }

    @Test
    @DisplayName("subtreeIds：部门不存在返回空列表")
    void subtreeIds_notFound() {
        when(deptMapper.selectById(99L)).thenReturn(null);

        assertThat(deptService.subtreeIds("t-default", 99L)).isEmpty();
    }

    private DeptEntity dept(Long id, String tenantId, String path) {
        DeptEntity dept = new DeptEntity();
        dept.setId(id);
        dept.setTenantId(tenantId);
        dept.setPath(path);
        return dept;
    }
}
