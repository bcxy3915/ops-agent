package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.DeptCreateRequest;
import com.example.opsaiagent.rbac.dto.DeptUpdateRequest;
import com.example.opsaiagent.rbac.entity.DeptEntity;
import com.example.opsaiagent.rbac.mapper.DeptMapper;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DeptService 单元测试。
 *
 * 覆盖：按 ID 查询 / 子树查询 / 空参数 / 跨租户 / 部门不存在。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部门服务")
class DeptServiceTest {

    @Mock
    private DeptMapper deptMapper;

    @Mock
    private OpsUserMapper userMapper;

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

    @Test
    @DisplayName("create：创建根部门并补全物化路径")
    void create_root() {
        when(deptMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            DeptEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        }).when(deptMapper).insert(any(DeptEntity.class));

        DeptCreateRequest request = new DeptCreateRequest();
        request.setCode("OPS");
        request.setName("运维部");

        DeptEntity created = deptService.create("t-default", request);

        assertThat(created.getId()).isEqualTo(10L);
        assertThat(created.getPath()).isEqualTo("/10/");
        assertThat(created.getLevel()).isEqualTo(1);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
        verify(deptMapper).updateById(created);
    }

    @Test
    @DisplayName("create：创建子部门，路径/层级继承父部门")
    void create_child() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "t-default", "/1/5/"));
        when(deptMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            DeptEntity entity = invocation.getArgument(0);
            entity.setId(10L);
            return 1;
        }).when(deptMapper).insert(any(DeptEntity.class));

        DeptCreateRequest request = new DeptCreateRequest();
        request.setParentId(5L);
        request.setCode("OPS");
        request.setName("运维部");

        DeptEntity created = deptService.create("t-default", request);

        assertThat(created.getPath()).isEqualTo("/1/5/10/");
        assertThat(created.getLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("create：部门编码重复抛业务异常")
    void create_duplicateCode() {
        when(deptMapper.selectOne(any())).thenReturn(dept(5L, "t-default", "/5/"));
        DeptCreateRequest request = new DeptCreateRequest();
        request.setCode("OPS");
        request.setName("运维部");

        assertThatThrownBy(() -> deptService.create("t-default", request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPT_CODE_EXISTS);
    }

    @Test
    @DisplayName("update：更新名称/排序/状态")
    void update_success() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "t-default", "/5/"));
        DeptUpdateRequest request = new DeptUpdateRequest();
        request.setName("平台运维部");
        request.setSort(10);
        request.setStatus("DISABLED");

        DeptEntity updated = deptService.update("t-default", 5L, request);

        assertThat(updated.getName()).isEqualTo("平台运维部");
        assertThat(updated.getSort()).isEqualTo(10);
        assertThat(updated.getStatus()).isEqualTo("DISABLED");
        verify(deptMapper).updateById(updated);
    }

    @Test
    @DisplayName("delete：存在子部门时拒绝删除")
    void delete_hasChildren() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "t-default", "/5/"));
        when(deptMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> deptService.delete("t-default", 5L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPT_HAS_CHILDREN);
    }

    @Test
    @DisplayName("delete：存在用户时拒绝删除")
    void delete_hasUsers() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "t-default", "/5/"));
        when(deptMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> deptService.delete("t-default", 5L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEPT_HAS_USERS);
    }

    @Test
    @DisplayName("delete：无子部门且无用户时删除成功")
    void delete_success() {
        when(deptMapper.selectById(5L)).thenReturn(dept(5L, "t-default", "/5/"));
        when(deptMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(0L);

        deptService.delete("t-default", 5L);

        verify(deptMapper).deleteById(5L);
    }

    @Test
    @DisplayName("listByTenant：返回租户部门列表")
    void listByTenant_success() {
        when(deptMapper.selectList(any())).thenReturn(List.of(dept(5L, "t-default", "/5/")));

        assertThat(deptService.listByTenant("t-default")).hasSize(1);
    }

    private DeptEntity dept(Long id, String tenantId, String path) {
        DeptEntity dept = new DeptEntity();
        dept.setId(id);
        dept.setTenantId(tenantId);
        dept.setPath(path);
        return dept;
    }
}
