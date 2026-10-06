package com.example.opsaiagent.metrics;

import com.example.opsaiagent.metrics.dto.MetricPoint;
import com.example.opsaiagent.metrics.dto.MetricSnapshotResponse;
import com.example.opsaiagent.registry.ServiceInfo;
import com.example.opsaiagent.registry.ServiceRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 *
 * 每 10 秒轮询所有注册服务，采样关键指标，存入内存环形队列。
 * 保留最近 6 小时的采样数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricSampler {

    // 6 小时 * 360 点/小时
    private static final int MAX_POINTS = 2160;
    // 10 秒
    private static final long SAMPLE_INTERVAL_MS = 10_000;

    private final ServiceRegistry serviceRegistry;
    private final ActuatorMetricFetcher fetcher;

    // 每个服务一份历史数据：{serviceName -> {metricName -> deque}}
    private final Map<String, Map<String, Deque<MetricPoint>>> history = new ConcurrentHashMap<>();

    // 每个服务一份最新快照
    private final Map<String, MetricSnapshotResponse.Snapshot> latestSnapshots = new ConcurrentHashMap<>();

    private final String[] SAMPLED_METRICS = {
            "cpu", "memory", "threadCount", "gcPause", "hikariActive"
    };

    /**
     * 定时采样
     */
    @Scheduled(fixedRate = SAMPLE_INTERVAL_MS, initialDelay = 5_000)
    public void sample() {
        List<ServiceInfo> services = serviceRegistry.listAll();
        if (services.isEmpty()) return;

        for (ServiceInfo service : services) {
            try {
                sampleService(service);
            } catch (Exception e) {
                log.warn("采样服务失败: {} - {}", service.getName(), e.getMessage());
            }
        }
    }

    /**
     * 采样单个服务
     * @param service 服务信息
     */
    private void sampleService(ServiceInfo service) {
        String name = service.getName();
        String baseUrl = service.getBaseUrl();
        long now = System.currentTimeMillis();

        // 1. 拉取各指标
        ActuatorMetricFetcher.MetricValue cpuMetric = fetcher.fetch(baseUrl, "process.cpu.usage");
        ActuatorMetricFetcher.MetricValue memUsedMetric = fetcher.fetch(baseUrl, "jvm.memory.used");
        ActuatorMetricFetcher.MetricValue memMaxMetric = fetcher.fetch(baseUrl, "jvm.memory.max");
        ActuatorMetricFetcher.MetricValue threadsMetric = fetcher.fetch(baseUrl, "jvm.threads.live");
        ActuatorMetricFetcher.MetricValue gcMetric = fetcher.fetch(baseUrl, "jvm.gc.pause");
        ActuatorMetricFetcher.MetricValue httpMetric = fetcher.fetch(baseUrl, "http.server.requests");
        ActuatorMetricFetcher.MetricValue hikariActive = fetcher.fetch(baseUrl, "hikaricp.connections.active");
        ActuatorMetricFetcher.MetricValue hikariPending = fetcher.fetch(baseUrl, "hikaricp.connections.pending");

        // 2. 计算派生值
        double cpu = cpuMetric != null ? cpuMetric.value() : 0;
        double memoryUsed = memUsedMetric != null ? memUsedMetric.value() : 0;
        long memoryMax = memMaxMetric != null ? (long) memMaxMetric.value() : 1024L * 1024 * 1024;
        int threadCount = threadsMetric != null ? (int) threadsMetric.value() : 0;

        // GC 平均暂停时间（毫秒）
        double gcPause = 0;
        if (gcMetric != null && gcMetric.count() > 0) {
            gcPause = gcMetric.totalTime() / gcMetric.count() * 1000;
        }

        // HTTP 请求速率：从上次采样到现在，每分钟多少请求
        long httpCount = 0;
        double httpErrorRate = 0;
        double httpAvgDuration = 0;
        if (httpMetric != null) {
            httpAvgDuration = httpMetric.count() > 0
                    ? httpMetric.totalTime() / httpMetric.count()
                    : 0;
            // 简化：直接用累计 count 除以运行时间估算每分钟
            // 更精确的做法是保存上一次的 count 做差值——这里先简化
            httpCount = (long) httpMetric.count();
        }

        int hikariActiveVal = hikariActive != null ? (int) hikariActive.value() : 0;
        int hikariPendingVal = hikariPending != null ? (int) hikariPending.value() : 0;

        // 3. 构造快照
        MetricSnapshotResponse.Snapshot snapshot = MetricSnapshotResponse.Snapshot.builder()
                .cpu(cpu)
                .memoryUsed((long) memoryUsed)
                .memoryMax(memoryMax)
                .httpCount(httpCount)
                .httpErrorRate(httpErrorRate)
                .httpAvgDuration(httpAvgDuration)
                .threadCount(threadCount)
                .gcPause(gcPause)
                .hikariActive(hikariActiveVal)
                .hikariPending(hikariPendingVal)
                .build();

        latestSnapshots.put(name, snapshot);

        // 4. 存储时序点
        appendPoint(name, "cpu", now, cpu);
        appendPoint(name, "memory", now, memoryUsed / 1024 / 1024);  // MB
        appendPoint(name, "threadCount", now, threadCount);
        appendPoint(name, "gcPause", now, gcPause);
        appendPoint(name, "hikariActive", now, hikariActiveVal);
    }

    /**
     * 追加一个数据点到环形队列
     * @param service 服务名
     * @param metric 指标名
     * @param time 时间戳
     * @param value 指标值
     */
    private void appendPoint(String service, String metric, long time, double value) {
        Map<String, Deque<MetricPoint>> serviceHistory =
                history.computeIfAbsent(service, k -> new ConcurrentHashMap<>());

        Deque<MetricPoint> deque = serviceHistory.computeIfAbsent(
                metric, k -> new LinkedBlockingDeque<>(MAX_POINTS));

        if (deque.size() >= MAX_POINTS) {
            deque.pollFirst();
        }
        deque.addLast(new MetricPoint(time, value));
    }

    /**
     * 获取最新快照
     * @param serviceName 服务名
     */
    public MetricSnapshotResponse.Snapshot getLatestSnapshot(String serviceName) {
        return latestSnapshots.get(serviceName);
    }

    /**
     * 获取指定时间范围内的历史数据
     * @param serviceName 服务名
     * @param metric 指标名
     * @param startMs 开始时间戳
     * @param endMs 结束时间戳
     */
    public List<MetricPoint> getHistory(String serviceName, String metric, long startMs, long endMs) {
        Map<String, Deque<MetricPoint>> serviceHistory = history.get(serviceName);
        if (serviceHistory == null) return List.of();

        Deque<MetricPoint> deque = serviceHistory.get(metric);
        if (deque == null) return List.of();

        List<MetricPoint> result = new ArrayList<>();
        for (MetricPoint p : deque) {
            if (p.getTimestamp() >= startMs && p.getTimestamp() <= endMs) {
                result.add(p);
            }
        }
        return result;
    }
}