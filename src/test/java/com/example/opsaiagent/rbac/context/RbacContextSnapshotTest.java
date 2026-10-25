package com.example.opsaiagent.rbac.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RbacContextSnapshot 单元测试。
 */
@DisplayName("上下文快照")
class RbacContextSnapshotTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        DeptContext.clear();
        UserContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("捕获并恢复租户/部门/用户上下文")
    void capture_restore() throws Exception {
        TenantContext.set("t1");
        DeptContext.set(10L);
        UserContext.set(new UserContext.CurrentUser("u1", "alice",
                Set.of("TENANT_ADMIN"), Set.of("alert:read")));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", null, Set.of()));

        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        snapshot.clear();
        assertThat(TenantContext.get()).isNull();

        String result = snapshot.call(() -> {
            assertThat(TenantContext.get()).isEqualTo("t1");
            assertThat(DeptContext.get()).isEqualTo(10L);
            assertThat(UserContext.get().username()).isEqualTo("alice");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("系统任务仅恢复租户上下文")
    void forTenant_restoresTenantOnly() {
        RbacContextSnapshot.forTenant("t2").run(() -> {
            assertThat(TenantContext.get()).isEqualTo("t2");
            assertThat(DeptContext.get()).isNull();
            assertThat(UserContext.get()).isNull();
        });
        assertThat(TenantContext.get()).isNull();
    }
}
