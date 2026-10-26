package com.example.opsaiagent.rbac.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户 / 部门 / 用户上下文单元测试。
 *
 * 覆盖：写入、读取、清除、null 清除、CurrentUser 快照复制。
 */
@DisplayName("RBAC 上下文")
class RbacContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
    }

    @Test
    @DisplayName("TenantContext：set/get/clear")
    void tenantContext() {
        TenantContext.set("t-default");
        assertThat(TenantContext.get()).isEqualTo("t-default");

        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();

        TenantContext.set("t-default");
        TenantContext.set(null);
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("DeptContext：set/get/clear")
    void deptContext() {
        DeptContext.set(5L);
        assertThat(DeptContext.get()).isEqualTo(5L);

        DeptContext.clear();
        assertThat(DeptContext.get()).isNull();
    }

    @Test
    @DisplayName("UserContext：保存快照并复制集合")
    void userContext() {
        UserContext.CurrentUser user = new UserContext.CurrentUser(
                "u-1", "zhangwei", Set.of("DEPT_ADMIN"), Set.of("knowledge:review"));
        UserContext.set(user);

        assertThat(UserContext.get()).isEqualTo(user);
        assertThat(UserContext.get().roles()).containsExactly("DEPT_ADMIN");
        assertThat(UserContext.get().permissions()).containsExactly("knowledge:review");

        UserContext.clear();
        assertThat(UserContext.get()).isNull();
    }

    @Test
    @DisplayName("UserContext：null 角色/权限归一为空集合")
    void userContext_nullSets() {
        UserContext.CurrentUser user = new UserContext.CurrentUser("u-2", "viewer", null, null);

        assertThat(user.roles()).isEmpty();
        assertThat(user.permissions()).isEmpty();
    }
}
