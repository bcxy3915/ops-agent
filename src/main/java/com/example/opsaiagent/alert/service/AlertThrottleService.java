package com.example.opsaiagent.alert.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警静默与通知节流。
 *   静默：规则触发后进入 silence_seconds 静默期，期内不再重复告警
 *   节流：同一服务每小时最多产生 N 条告警事件，超限事件以 SILENCED 落库
 * 状态保存在内存中（重启清零），生产环境可替换为 Redis。
 */
@Service
public class AlertThrottleService {

    /** 每服务每小时告警事件上限 */
    public static final int HOURLY_EVENT_LIMIT = 50;

    /** ruleId -> 上次触发时间戳（ms），用于静默期检查 */
    private final Map<Long, Long> lastFiredAt = new ConcurrentHashMap<>();

    /** service -> 小时窗口计数 */
    private final Map<String, HourWindow> hourlyCount = new ConcurrentHashMap<>();

    /**
     * 是否处于规则静默期
     *
     * @param ruleId          规则 ID
     * @param silenceSeconds  静默时长（秒），小于等于0 表示不静默
     * @param nowMs           当前时间戳
     */
    public boolean shouldSilence(Long ruleId, Integer silenceSeconds, long nowMs) {
        if (silenceSeconds == null || silenceSeconds <= 0) {
            return false;
        }
        Long last = lastFiredAt.get(ruleId);
        return last != null && (nowMs - last) < silenceSeconds * 1000L;
    }

    /**
     * 记录规则触发时间（用于静默期）
     */
    public void recordFired(Long ruleId, long nowMs) {
        lastFiredAt.put(ruleId, nowMs);
    }

    /**
     * 是否允许继续产生告警事件（未超每小时上限）
     */
    public boolean allowEvent(String service, long nowMs) {
        HourWindow window = hourlyWindow(service, nowMs);
        window.slide(nowMs);
        return window.count < HOURLY_EVENT_LIMIT;
    }

    /**
     * 记录产生一条告警事件
     */
    public void recordEvent(String service, long nowMs) {
        HourWindow window = hourlyWindow(service, nowMs);
        window.slide(nowMs);
        window.count++;
    }

    /**
     * 重置全部节流状态（测试隔离用）
     */
    public void reset() {
        lastFiredAt.clear();
        hourlyCount.clear();
    }

    private HourWindow hourlyWindow(String service, long nowMs) {
        return hourlyCount.computeIfAbsent(service, k -> new HourWindow(nowMs));
    }

    /** 滚动小时窗口 */
    private static final class HourWindow {
        long windowStart;
        int count;

        HourWindow(long windowStart) {
            this.windowStart = windowStart;
        }

        void slide(long nowMs) {
            if (nowMs - windowStart >= 3_600_000L) {
                windowStart = nowMs;
                count = 0;
            }
        }
    }
}
