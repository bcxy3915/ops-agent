package com.example.opsaiagent.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本地 OpenAI 协议夹具验证框架边界，不调用真实模型、数据库、SMTP 或业务服务。
 * 脚本响应只证明协议与工具循环兼容，不证明真实模型的自主规划正确率。
 */
class LangChain4jCompatibilityPocTest {

    private final ObjectMapper json = new ObjectMapper();
    private final LinkedBlockingQueue<String> responses = new LinkedBlockingQueue<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private boolean streaming;

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                requests.add(json.readTree(exchange.getRequestBody()));
                String response = responses.poll();
                if (response == null) {
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopFixture() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenAiChatModel model() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey("poc-placeholder-not-a-secret")
                .modelName("poc-scripted-model").temperature(0.0)
                .timeout(Duration.ofSeconds(5)).maxRetries(0).build();
    }

    @Test
    void synchronousChatUsesConfiguredOpenAiEndpoint() throws Exception {
        responses.add(completion("{\"role\":\"assistant\",\"content\":\"服务健康\"}", "stop"));

        assertThat(model().chat("检查测试服务")).isEqualTo("服务健康");
        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("poc-scripted-model");
        assertThat(requests.getFirst().path("messages").get(0).path("content").asText())
                .isEqualTo("检查测试服务");
    }

    interface DiagnosisAssistant {
        HealthSummary diagnose(String question);
    }

    public record HealthSummary(String service, String status) { }

    @Test
    void structuredOutputMapsToBusinessDtoWithExistingJacksonDependencies() throws Exception {
        String dto = json.writeValueAsString(new HealthSummary("fixture-service", "DOWN"));
        responses.add(completion(json.writeValueAsString(
                java.util.Map.of("role", "assistant", "content", dto)), "stop"));

        DiagnosisAssistant assistant = AiServices.create(DiagnosisAssistant.class, model());

        assertThat(assistant.diagnose("返回测试服务状态"))
                .isEqualTo(new HealthSummary("fixture-service", "DOWN"));
        assertThat(requests).hasSize(1);
    }

    interface OpsAssistant {
        String ask(String question);
    }

    public static class ReadOnlyTools {
        private final List<String> calls = new ArrayList<>();

        @Tool("Read the health of the fixture service")
        public String queryServiceHealth(@P("Registered service name") String serviceName) {
            calls.add("health:" + serviceName);
            return "{\"status\":\"DOWN\"}";
        }

        @Tool("Read a metric of the fixture service")
        public String queryMetric(@P("Registered service name") String serviceName,
                                  @P("Metric name") String metricName) {
            calls.add("metric:" + serviceName + ":" + metricName);
            return "{\"value\":0.91}";
        }
    }

    @Test
    void consecutiveToolRoundsFeedRealToolResultsBackIntoModelContext() throws Exception {
        responses.add(toolCall("health-1", "queryServiceHealth",
                "{\"serviceName\":\"fixture-service\"}"));
        responses.add(toolCall("metric-1", "queryMetric",
                "{\"serviceName\":\"fixture-service\",\"metricName\":\"system.cpu.usage\"}"));
        responses.add(completion("{\"role\":\"assistant\",\"content\":\"DOWN，CPU为0.91，根因尚未证实\"}", "stop"));
        ReadOnlyTools tools = new ReadOnlyTools();
        OpsAssistant assistant = AiServices.builder(OpsAssistant.class).chatModel(model()).tools(tools)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();

        assertThat(assistant.ask("先查健康，DOWN时再查CPU，只读"))
                .isEqualTo("DOWN，CPU为0.91，根因尚未证实");

        assertThat(tools.calls).containsExactly("health:fixture-service",
                "metric:fixture-service:system.cpu.usage");
        assertThat(requests).hasSize(3);
        assertThat(toolResult(requests.get(1), "health-1")).isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(toolResult(requests.get(2), "metric-1")).isEqualTo("{\"value\":0.91}");
        assertThat(responses).isEmpty();
    }

    @Test
    void streamingDeliversChineseTokensAndCompletion() throws Exception {
        streaming = true;
        responses.add("data: " + chunk("{\"role\":\"assistant\",\"content\":\"服务\"}", null)
                + "\n\ndata: " + chunk("{\"content\":\"健康\"}", null)
                + "\n\ndata: " + chunk("{}", "stop") + "\n\ndata: [DONE]\n\n");
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder tokens = new StringBuilder();
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey("poc-placeholder-not-a-secret")
                .modelName("poc-scripted-model").timeout(Duration.ofSeconds(5)).build();

        model.chat("检查服务", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String text) { tokens.append(text); }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                result.complete(response.aiMessage().text());
            }

            @Override
            public void onError(Throwable error) { result.completeExceptionally(error); }
        });

        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("服务健康");
        assertThat(tokens.toString()).isEqualTo("服务健康");
        assertThat(requests.getFirst().path("stream").asBoolean()).isTrue();
    }

    private String toolResult(JsonNode request, String callId) {
        for (JsonNode message : request.path("messages")) {
            if (callId.equals(message.path("tool_call_id").asText())) {
                return message.path("content").asText();
            }
        }
        throw new AssertionError("Missing tool result: " + callId);
    }

    private String toolCall(String id, String name, String arguments) throws Exception {
        return completion(json.writeValueAsString(java.util.Map.of(
                "role", "assistant", "tool_calls", List.of(java.util.Map.of(
                        "id", id, "type", "function", "function", java.util.Map.of(
                                "name", name, "arguments", arguments))))), "tool_calls");
    }

    private String completion(String message, String finishReason) {
        return "{\"id\":\"poc\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"poc-scripted-model\","
                + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finishReason
                + "\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    private String chunk(String delta, String finishReason) throws Exception {
        return "{\"id\":\"poc\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"poc-scripted-model\","
                + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + json.writeValueAsString(finishReason) + "}]}";
    }
}
