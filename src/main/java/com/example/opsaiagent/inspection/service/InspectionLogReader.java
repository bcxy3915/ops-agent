package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.inspection.config.InspectionLogProperties.Source;
import com.example.opsaiagent.inspection.dto.LogEvidenceReport;
import com.example.opsaiagent.inspection.dto.LogEvidenceReport.Entry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 仅从绑定文件读取末尾 256 KiB，不扫描目录、不执行命令、不回传原始路径。 */
@Component
@RequiredArgsConstructor
public class InspectionLogReader {
    private static final int MAX_BYTES = 262_144;
    private static final int MAX_MESSAGE_CHARS = 2000;
    private static final int MAX_OUTPUT_CHARS = 32_000;
    private static final Pattern SPRING = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2})?)\\s+.*?\\b(TRACE|DEBUG|INFO|WARN|ERROR)\\b(.*)$");
    private final ObjectMapper mapper;
    private final InspectionLogRedactor redactor;

    /**
     * 读取已授权文件的时间窗错误日志，文件轮转或预算不足时如实标记不完整。
     * @param serviceId 已授权服务 ID
     * @param sourceId 已绑定日志源
     * @param source 固定文件定义
     * @param start 查询起点，包含
     * @param end 查询终点，不包含
     * @param limit 输出条数上限，调用方保证 1～200
     * @return 脱敏后的证据，读取失败时返回无原始异常内容的失败原因
     */
    public LogEvidenceReport read(String serviceId, String sourceId, Source source,
                                   long start, long end, int limit) {
        try {
            Path configuredRoot = Path.of(source.getRoot());
            if (!configuredRoot.isAbsolute()) {
                return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
            }
            Path root = configuredRoot.toRealPath();
            Path relative = Path.of(source.getFile());
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) {
                return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
            }
            // 拒绝路径中任一符号链接，并核对真实路径没有越出绑定根目录。
            Path checked = root;
            for (Path part : root.relativize(file)) {
                checked = checked.resolve(part);
                if (Files.isSymbolicLink(checked)) {
                    return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
                }
            }
            if (!file.toRealPath().startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
            }
            ZoneId zone = ZoneId.of(source.getZoneId());
            if (!List.of("SPRING", "JSONL").contains(source.getFormat())) {
                return unavailable(serviceId, sourceId, start, end, "INVALID_SOURCE");
            }
            byte[] bytes;
            boolean truncated;
            boolean tailCut;
            Object originalFileKey = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                long size = channel.size();
                long offset = Math.max(0, size - MAX_BYTES);
                truncated = offset > 0;
                tailCut = offset > 0;
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, MAX_BYTES));
                channel.position(offset);
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // 只填充固定预算缓冲区，文件增长不会扩展本次扫描范围。
                }
                bytes = new byte[buffer.position()];
                buffer.flip();
                buffer.get(bytes);
                truncated |= channel.size() != size;
            }
            try {
                Object currentFileKey = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
                truncated |= !Objects.equals(originalFileKey, currentFileKey);
            } catch (Exception rotatedFile) {
                truncated = true;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (tailCut) {
                int newline = text.indexOf('\n');
                text = newline < 0 ? "" : text.substring(newline + 1);
            }
            // 不返回未完成的最后一行，避免半条日志或半个凭据被误识别。
            int lastNewline = text.lastIndexOf('\n');
            if (!text.isEmpty() && lastNewline != text.length() - 1) {
                truncated = true;
                text = lastNewline < 0 ? "" : text.substring(0, lastNewline + 1);
            }
            List<Entry> matches = new ArrayList<>();
            int skipped = 0;
            for (String line : text.split("\\n")) {
                if (line.isBlank()) continue;
                Entry entry;
                try {
                    entry = parse(line.stripTrailing(), source.getFormat(), zone);
                } catch (Exception invalidLine) {
                    skipped++;
                    continue;
                }
                if (entry.timestamp() >= start && entry.timestamp() < end
                        && ("WARN".equals(entry.level()) || "ERROR".equals(entry.level()))) {
                    String message = redactor.redact(entry.message());
                    if (message.length() > MAX_MESSAGE_CHARS) {
                        message = message.substring(0, MAX_MESSAGE_CHARS);
                        truncated = true;
                    }
                    matches.add(new Entry(entry.timestamp(), entry.level(), message));
                }
            }
            matches.sort(Comparator.comparingLong(Entry::timestamp).reversed());
            List<Entry> output = new ArrayList<>();
            int chars = 0;
            for (Entry entry : matches) {
                if (output.size() >= limit || chars + entry.message().length() > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
                output.add(entry);
                chars += entry.message().length();
            }
            output.sort(Comparator.comparingLong(Entry::timestamp));
            boolean partial = truncated || skipped > 0;
            return new LogEvidenceReport(serviceId, sourceId, start, end, partial ? "PARTIAL" : "AVAILABLE",
                    partial ? "INCOMPLETE_SCAN" : output.isEmpty() ? "NO_MATCHES" : "READ_COMPLETED",
                    bytes.length, skipped, truncated, List.copyOf(output));
        } catch (Exception failure) {
            return unavailable(serviceId, sourceId, start, end, "FILE_UNAVAILABLE");
        }
    }

    /**
     * 解析已声明格式的完整日志行；不对没有时间戳的堆栈行猜测时间。
     * @param line 完整日志行
     * @param format 固定格式
     * @param zone 无时区时间的解释规则
     * @return 原始条目，仅在本组件内部短暂使用
     * @throws Exception 格式不符合声明
     */
    private Entry parse(String line, String format, ZoneId zone) throws Exception {
        if ("JSONL".equals(format)) {
            var node = mapper.readTree(line);
            if (node == null || !node.path("timestamp").isTextual() || !node.path("level").isTextual()
                    || !node.path("message").isTextual()) {
                throw new IllegalArgumentException("日志字段缺失");
            }
            return new Entry(time(node.path("timestamp").asText(), zone),
                    node.path("level").asText(), node.path("message").asText());
        }
        var match = SPRING.matcher(line);
        if (!match.matches()) throw new IllegalArgumentException("日志格式不符");
        return new Entry(time(match.group(1), zone), match.group(2), match.group(3).strip());
    }

    /**
     * 按日志显式偏移或源配置时区解释时间。
     * @param timestamp 日志时间
     * @param zone 源配置时区
     * @return 毫秒时间戳
     */
    private long time(String timestamp, ZoneId zone) {
        String value = timestamp.replace(' ', 'T');
        try {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (Exception noOffset) {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant().toEpochMilli();
        }
    }

    /**
     * 返回不泄露文件路径与系统异常的不可用结果。
     * @param serviceId 服务 ID
     * @param sourceId 日志源标识
     * @param start 窗口起点
     * @param end 窗口终点
     * @param reason 固定原因码
     * @return 不可用证据
     */
    private LogEvidenceReport unavailable(String serviceId, String sourceId, long start, long end, String reason) {
        return new LogEvidenceReport(serviceId, sourceId, start, end, "UNAVAILABLE", reason, 0, 0, false, List.of());
    }
}
