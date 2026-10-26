package com.example.opsaiagent.common.config;

import com.example.opsaiagent.rbac.context.DeptContext;
import com.example.opsaiagent.rbac.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextPropagatingTaskDecorator 单元测试。
 */
@DisplayName("异步上下文装饰器")
class ContextPropagatingTaskDecoratorTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        DeptContext.clear();
    }

    @Test
    @DisplayName("提交时捕获，执行时恢复，结束后清理")
    void decorate_propagatesAndClears() {
        TenantContext.set("t1");
        DeptContext.set(5L);

        AtomicReference<String> tenant = new AtomicReference<>();
        AtomicReference<Long> dept = new AtomicReference<>();
        Runnable decorated = new ContextPropagatingTaskDecorator().decorate(() -> {
            tenant.set(TenantContext.get());
            dept.set(DeptContext.get());
        });

        TenantContext.clear();
        DeptContext.clear();
        decorated.run();

        assertThat(tenant.get()).isEqualTo("t1");
        assertThat(dept.get()).isEqualTo(5L);
        assertThat(TenantContext.get()).isNull();
        assertThat(DeptContext.get()).isNull();
    }
}
