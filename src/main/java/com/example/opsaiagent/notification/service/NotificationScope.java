package com.example.opsaiagent.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.notification.entity.NotificationChannelConfigEntity;
import com.example.opsaiagent.notification.entity.NotificationRecordEntity;
import com.example.opsaiagent.rbac.service.DataScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通知渠道 / 记录查询范围。
 *
 * 通知配置按租户隔离；无租户上下文保持全量兼容。
 */
@Component
@RequiredArgsConstructor
public class NotificationScope {

    private final DataScope dataScope;

    /**
     * 当前查询范围。
     *
     * @return 范围
     */
    public Scope current() {
        if (dataScope == null) {
            return unrestricted();
        }
        String tenantId = dataScope.tenantId();
        if (!StringUtils.hasText(tenantId) || dataScope.isPlatformTenant()) {
            return unrestricted();
        }
        return new Scope(tenantId);
    }

    public Scope unrestricted() {
        return new Scope(null);
    }

    /**
     * 具体查询范围。
     */
    public static class Scope {

        private final String tenantId;

        Scope(String tenantId) {
            this.tenantId = tenantId;
        }

        public boolean isUnrestricted() {
            return !StringUtils.hasText(tenantId);
        }

        public LambdaQueryWrapper<NotificationChannelConfigEntity> channels() {
            LambdaQueryWrapper<NotificationChannelConfigEntity> wrapper = new LambdaQueryWrapper<>();
            if (!isUnrestricted()) {
                wrapper.eq(NotificationChannelConfigEntity::getTenantId, tenantId);
            }
            return wrapper;
        }

        public LambdaQueryWrapper<NotificationRecordEntity> records() {
            LambdaQueryWrapper<NotificationRecordEntity> wrapper = new LambdaQueryWrapper<>();
            if (!isUnrestricted()) {
                wrapper.eq(NotificationRecordEntity::getTenantId, tenantId);
            }
            return wrapper;
        }

        public boolean canAccess(NotificationChannelConfigEntity channel) {
            return isUnrestricted() || (channel != null && tenantId.equals(channel.getTenantId()));
        }
    }
}
