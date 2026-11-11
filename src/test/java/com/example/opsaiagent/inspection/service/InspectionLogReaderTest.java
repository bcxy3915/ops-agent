package com.example.opsaiagent.inspection.service;

import com.example.opsaiagent.inspection.config.InspectionLogProperties.Source;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用临时文件验证日志解析、字节预算、时间边界及脱敏，不访问商城日志。 */
class InspectionLogReaderTest {
    @TempDir
    Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final InspectionLogReader reader = new InspectionLogReader(mapper, new InspectionLogRedactor());
    private final long start = Instant.parse("2026-11-11T02:00:00Z").toEpochMilli();
    private final long end = start + 60_000;
    private Source source;

    /**
     * 创建仅测试使用的固定日志源。
     * 无入参。
     * @return 无返回值
     */
    @BeforeEach
    void setUp() {
        source = new Source();
        source.setServiceId("mall-id");
        source.setRoot(directory.toString());
        source.setFile("mall.log");
        source.setFormat("JSONL");
    }

    /**
     * 创建测试 JSON 日志行。
     * @param timestamp 时间文本
     * @param level 日志级别
     * @param message 测试消息
     * @return 换行终止的完整 JSON 日志
     * @throws IOException 序列化失败
     */
    private String line(String timestamp, String level, String message) throws IOException {
        return mapper.writeValueAsString(Map.of("timestamp", timestamp, "level", level, "message", message)) + "\n";
    }

    /**
     * 验证半开窗口、级别筛选及输出不泄漏路径。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void filtersWindowAndLevelsWithoutExposingSourcePath() throws IOException {
        Files.writeString(directory.resolve("mall.log"), line("2026-11-11T02:00:00Z", "ERROR", "connection timeout")
                + line("2026-11-11T02:00:10Z", "INFO", "normal")
                + line("2026-11-11T02:01:00Z", "ERROR", "outside window"));
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().message()).isEqualTo("connection timeout");
        assertThat(result.toString()).doesNotContain(directory.toString(), "mall.log");
    }

    /**
     * 验证默认 Spring 单行格式与源时区，堆栈行不猜测归属。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void parsesSpringLogWithExplicitTimezoneAndReportsUnparsedLines() throws IOException {
        source.setFormat("SPRING");
        Files.writeString(directory.resolve("mall.log"),
                "2026-11-11 10:00:00.000 ERROR 123 --- [main] OrderService : connection timeout\n"
                        + "    at com.mall.OrderService.query(OrderService.java:10)\n");
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.skippedLines()).isEqualTo(1);
        assertThat(result.entries().getFirst().timestamp()).isEqualTo(start);
    }

    /**
     * 截取最新条目后按时间升序返回，并保留截断标志。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void returnsNewestBoundedEntriesInChronologicalOrder() throws IOException {
        Files.writeString(directory.resolve("mall.log"), line("2026-11-11T02:00:00Z", "WARN", "first")
                + line("2026-11-11T02:00:10Z", "ERROR", "second")
                + line("2026-11-11T02:00:20Z", "ERROR", "third"));
        var result = reader.read("mall-id", "main", source, start, end, 2);
        assertThat(result.entries()).extracting(entry -> entry.message()).containsExactly("second", "third");
        assertThat(result.truncated()).isTrue();
    }

    /**
     * 有界扫描不加载整个大文件，末尾有效行仍可解析。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void scansOnlyBoundedTail() throws IOException {
        Files.writeString(directory.resolve("mall.log"), "x".repeat(300_000) + "\n"
                + line("2026-11-11T02:00:10Z", "ERROR", "tail error"));
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.scannedBytes()).isEqualTo(262_144);
        assertThat(result.truncated()).isTrue();
        assertThat(result.entries()).hasSize(1);
    }

    /**
     * 没有完成的最后一行不作为证据返回。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void dropsIncompleteFinalLine() throws IOException {
        Files.writeString(directory.resolve("mall.log"), line("2026-11-11T02:00:10Z", "ERROR", "complete")
                + "{\"timestamp\":\"2026-11-11T02:00:20Z\",\"message\":\"password=half");
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.entries()).hasSize(1);
        assertThat(result.truncated()).isTrue();
        assertThat(result.toString()).doesNotContain("half");
    }

    /**
     * 输出预算优先于请求条数，防止向模型返回无限长日志。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void limitsTotalOutputCharacters() throws IOException {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            content.append(line("2026-11-11T02:00:10Z", "ERROR", "x".repeat(2200)));
        }
        Files.writeString(directory.resolve("mall.log"), content);
        var result = reader.read("mall-id", "main", source, start, end, 200);
        assertThat(result.truncated()).isTrue();
        assertThat(result.entries().stream().mapToInt(entry -> entry.message().length()).sum()).isLessThanOrEqualTo(32_000);
    }

    /**
     * 确认路径穿越被拒绝，不尝试读取根目录外目标。
     * @param file 非法相对路径
     * @return 无返回值
     */
    @ParameterizedTest
    @ValueSource(strings = {"../other.log", "../../other.log"})
    void rejectsTraversal(String file) {
        source.setFile(file);
        assertThat(reader.read("mall-id", "main", source, start, end, 100).reason()).isEqualTo("INVALID_SOURCE");
    }

    /**
     * 缺失文件只提供固定原因，不泄漏操作系统异常与路径。
     * 无入参。
     * @return 无返回值
     */
    @Test
    void unavailableFileDoesNotLeakException() {
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.reason()).isEqualTo("FILE_UNAVAILABLE");
        assertThat(result.toString()).doesNotContain(directory.toString());
    }

    /**
     * 空文件只有无匹配证据，不推导健康状态。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void emptyFileIsNoMatchesRatherThanHealthy() throws IOException {
        Files.writeString(directory.resolve("mall.log"), "");
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.reason()).isEqualTo("NO_MATCHES");
        assertThat(result.entries()).isEmpty();
    }

    /**
     * 凭据先隐藏再截断，保留无凭据异常描述。
     * 无入参。
     * @return 无返回值
     * @throws IOException 写入临时夹具失败
     */
    @Test
    void redactsMessagesBeforeReturningEvidence() throws IOException {
        Files.writeString(directory.resolve("mall.log"), line("2026-11-11T02:00:10Z", "ERROR", "password=secret-value")
                + line("2026-11-11T02:00:20Z", "ERROR", "mail tester@example.com phone 13800138000 connection timeout"));
        var result = reader.read("mall-id", "main", source, start, end, 100);
        assertThat(result.toString()).doesNotContain("secret-value", "tester@example.com", "13800138000");
        assertThat(result.entries().getLast().message()).contains("connection timeout");
    }
}
