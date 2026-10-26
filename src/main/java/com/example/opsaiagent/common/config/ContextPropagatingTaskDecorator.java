package com.example.opsaiagent.common.config;

import com.example.opsaiagent.rbac.context.RbacContextSnapshot;
import org.springframework.core.task.TaskDecorator;

/**
 * 线程池上下文装饰器。
 *
 * 提交任务时捕获租户/部门/用户上下文，执行时恢复，结束后清理。
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        RbacContextSnapshot snapshot = RbacContextSnapshot.capture();
        return snapshot.wrap(runnable);
    }
}
