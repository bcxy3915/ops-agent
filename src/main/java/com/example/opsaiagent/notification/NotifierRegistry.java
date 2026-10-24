package com.example.opsaiagent.notification;

import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 通知渠道适配器注册表（T09）
 * 按渠道类型路由到对应 Notifier 实现。
 */
@Component
public class NotifierRegistry {

    private final Map<NotificationChannelType, Notifier> notifiers;

    public NotifierRegistry(List<Notifier> notifierList) {
        this.notifiers = notifierList.stream()
                .collect(Collectors.toMap(Notifier::type, Function.identity(), (a, b) -> b));
    }

    /**
     * 按类型获取适配器，无对应实现时抛异常
     */
    public Notifier get(NotificationChannelType type) {
        Notifier notifier = notifiers.get(type);
        if (notifier == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "不支持的渠道类型: " + type);
        }
        return notifier;
    }

    /**
     * 该类型是否有已注册的适配器
     */
    public boolean supports(NotificationChannelType type) {
        return notifiers.containsKey(type);
    }
}
