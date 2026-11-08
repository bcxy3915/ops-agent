package com.example.opsaiagent.migration;

import com.example.opsaiagent.ai.api.ChatRequest;
import com.example.opsaiagent.ai.api.ChatStreamEvent;
import com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jMemoryId;
import com.example.opsaiagent.ai.adapter.langchain4j.OpsAssistant;
import com.example.opsaiagent.tools.HealthCheckTools;
import com.example.opsaiagent.tools.MetricDiscoveryTools;
import com.example.opsaiagent.tools.MetricQueryTools;
import com.example.opsaiagent.tools.ServiceDiscoveryTools;
import com.example.opsaiagent.tools.langchain4j.LangChain4jReadOnlyToolBindings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void synchronousGatewayMapsProjectRequestAndResponse() throws Exception {
        responses.add(completion("{\"role\":\"assistant\",\"content\":\"网关可用\"}", "stop"));
        LangChain4jChatGateway gateway = new LangChain4jChatGateway(model());

        var response = gateway.chat(new ChatRequest("session-1", "检查服务", "你是运维助手", null));

        assertThat(response.answer()).isEqualTo("网关可用");
        assertThat(response.provider()).isEqualTo("langchain4j");
        assertThat(requests.getFirst().path("messages").get(0).path("role").asText())
                .isEqualTo("system");
        assertThat(requests.getFirst().path("messages").get(1).path("content").asText())
                .isEqualTo("检查服务");
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

    @Test
    void structuredOutputGatewayMapsJsonAndRejectsMalformedOutput() throws Exception {
        String dto = json.writeValueAsString(new HealthSummary("fixture-service", "DOWN"));
        responses.add(completion(json.writeValueAsString(
                java.util.Map.of("role", "assistant", "content", dto)), "stop"));
        LangChain4jStructuredOutputGateway gateway = new LangChain4jStructuredOutputGateway(model(), json);

        assertThat(gateway.generate(new ChatRequest("diag-1", "诊断故障", "输出 JSON", null),
                HealthSummary.class)).isEqualTo(new HealthSummary("fixture-service", "DOWN"));

        responses.add(completion("{\"role\":\"assistant\",\"content\":\"not-json\"}", "stop"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.generate(
                        new ChatRequest("diag-2", "诊断故障", "输出 JSON", null), HealthSummary.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模型返回的结构化结果格式非法");
    }

    @Test
    void consecutiveToolRoundsFeedRealToolResultsBackIntoModelContext() throws Exception {
        responses.add(toolCall("health-1", "queryServiceHealth",
                "{\"serviceName\":\"fixture-service\"}"));
        responses.add(toolCall("metric-1", "queryMetric",
                "{\"serviceName\":\"fixture-service\",\"metricName\":\"system.cpu.usage\"}"));
        responses.add(completion("{\"role\":\"assistant\",\"content\":\"DOWN，CPU为0.91，根因尚未证实\"}", "stop"));
        HealthCheckTools healthTools = mock(HealthCheckTools.class);
        MetricQueryTools metricTools = mock(MetricQueryTools.class);
        when(healthTools.queryServiceHealth("fixture-service")).thenReturn("{\"status\":\"DOWN\"}");
        when(metricTools.queryMetric("fixture-service", "system.cpu.usage"))
                .thenReturn("{\"value\":0.91}");
        LangChain4jReadOnlyToolBindings tools = new LangChain4jReadOnlyToolBindings(
                healthTools, metricTools, mock(MetricDiscoveryTools.class), mock(ServiceDiscoveryTools.class));
        OpsAssistant assistant = AiServices.builder(OpsAssistant.class).chatModel(model()).tools(tools)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(20).build())
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();

        assertThat(assistant.ask(LangChain4jMemoryId.of("alice", "session-1"),
                "先查健康，DOWN时再查CPU，只读", "你是运维助手"))
                .isEqualTo("DOWN，CPU为0.91，根因尚未证实");

        verify(healthTools).queryServiceHealth("fixture-service");
        verify(metricTools).queryMetric("fixture-service", "system.cpu.usage");
        assertThat(requests).hasSize(3);
        assertThat(toolResult(requests.get(1), "health-1")).isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(toolResult(requests.get(2), "metric-1")).isEqualTo("{\"value\":0.91}");
        assertThat(responses).isEmpty();
    }

    @Test
    void allReadOnlyBindingsDelegateToExistingProjectTools() throws Exception {
        responses.add(toolCall("health-1", "queryServiceHealth", "{\"serviceName\":\"fixture-service\"}"));
        responses.add(toolCall("metric-1", "queryMetric",
                "{\"serviceName\":\"fixture-service\",\"metricName\":\"system.cpu.usage\"}"));
        responses.add(toolCall("metrics-1", "listMetrics", "{\"serviceName\":\"fixture-service\"}"));
        responses.add(toolCall("services-1", "listServicesByStatus", "{\"status\":\"DOWN\"}"));
        responses.add(completion("{\"role\":\"assistant\",\"content\":\"只读工具调用完成\"}", "stop"));

        HealthCheckTools healthTools = mock(HealthCheckTools.class);
        MetricQueryTools metricTools = mock(MetricQueryTools.class);
        MetricDiscoveryTools metricDiscoveryTools = mock(MetricDiscoveryTools.class);
        ServiceDiscoveryTools serviceDiscoveryTools = mock(ServiceDiscoveryTools.class);
        when(healthTools.queryServiceHealth("fixture-service")).thenReturn("health-result");
        when(metricTools.queryMetric("fixture-service", "system.cpu.usage")).thenReturn("metric-result");
        when(metricDiscoveryTools.listMetrics("fixture-service")).thenReturn("metrics-result");
        when(serviceDiscoveryTools.listServicesByStatus("DOWN")).thenReturn("services-result");

        LangChain4jReadOnlyToolBindings tools = new LangChain4jReadOnlyToolBindings(
                healthTools, metricTools, metricDiscoveryTools, serviceDiscoveryTools);
        OpsAssistant assistant = AiServices.builder(OpsAssistant.class).chatModel(model()).tools(tools)
                .chatMemoryProvider(id -> MessageWindowChatMemory.builder().id(id).maxMessages(20).build())
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocationUnlessVisibleToLlm())
                .build();

        assertThat(assistant.ask(LangChain4jMemoryId.of("alice", "session-1"),
                "依次调用四个只读工具", "你是运维助手")).isEqualTo("只读工具调用完成");
        verify(healthTools).queryServiceHealth("fixture-service");
        verify(metricTools).queryMetric("fixture-service", "system.cpu.usage");
        verify(metricDiscoveryTools).listMetrics("fixture-service");
        verify(serviceDiscoveryTools).listServicesByStatus("DOWN");
        assertThat(requests).hasSize(5);
        assertThat(toolResult(requests.get(4), "services-1")).isEqualTo("services-result");
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

    @Test
    void streamingGatewayMapsTokensAndAppendsDoneEvent() throws Exception {
        streaming = true;
        responses.add("data: " + chunk("{\"role\":\"assistant\",\"content\":\"流式\"}", null)
                + "\n\ndata: " + chunk("{\"content\":\"网关\"}", null)
                + "\n\ndata: " + chunk("{}", "stop") + "\n\ndata: [DONE]\n\n");
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey("poc-placeholder-not-a-secret")
                .modelName("poc-scripted-model").timeout(Duration.ofSeconds(5)).build();
        com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway gateway =
                new com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway(model);

        List<ChatStreamEvent> events = Flux.from(gateway.stream(
                        new ChatRequest("session-1", "检查服务", "你是运维助手", null)))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(events).extracting(ChatStreamEvent::type)
                .containsExactly(ChatStreamEvent.Type.TOKEN, ChatStreamEvent.Type.TOKEN,
                        ChatStreamEvent.Type.DONE);
        assertThat(events).extracting(ChatStreamEvent::data).containsExactly("流式", "网关", null);
        assertThat(requests.getFirst().path("messages").get(0).path("role").asText())
                .isEqualTo("system");
    }

    @Test
    void streamingGatewayPropagatesModelFailureWithoutEmittingDone() {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl).apiKey("poc-placeholder-not-a-secret")
                .modelName("poc-scripted-model").timeout(Duration.ofSeconds(5)).build();
        com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway gateway =
                new com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jStreamingChatGateway(model);

        assertThatThrownBy(() -> Flux.from(gateway.stream(
                        new ChatRequest("session-1", "检查服务", "你是运维助手", null)))
                .collectList().block(Duration.ofSeconds(10)))
                .isNotNull();
        assertThat(requests).hasSize(1);
    }

    /**
     * 验证生产流式 Agent 的 RAG、连续工具轮次和异步权限上下文。
     * @return 无返回值
     * @throws Exception 夹具或模型调用异常
     */
    @Test
    void streamingAgentRetrievesKnowledgeAndExecutesToolsWithRequestContext() throws Exception {
        streaming = true;
        responses.add(streamingToolCall("health-1", "queryServiceHealth",
                "{\"serviceName\":\"fixture-service\"}"));
        responses.add(streamingToolCall("metric-1", "queryMetric",
                "{\"serviceName\":\"fixture-service\",\"metricName\":\"cpu\"}"));
        responses.add("data: " + chunk("{\"role\":\"assistant\",\"content\":\"只读诊断完成\"}", null)
                + "\n\ndata: " + chunk("{}", "stop") + "\n\ndata: [DONE]\n\n");
        var health = mock(HealthCheckTools.class);
        var metric = mock(MetricQueryTools.class);
        when(health.queryServiceHealth("fixture-service")).thenAnswer(call -> {
            assertThat(com.example.opsaiagent.rbac.context.TenantContext.get()).isEqualTo("fixture-tenant");
            return "DOWN";
        });
        when(metric.queryMetric("fixture-service", "cpu")).thenAnswer(call -> {
            assertThat(com.example.opsaiagent.rbac.context.TenantContext.get()).isEqualTo("fixture-tenant");
            return "0.91";
        });
        var model = OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey("test-key")
                .modelName("poc-scripted-model").timeout(Duration.ofSeconds(5)).build();
        var retriever = mock(com.example.opsaiagent.retrieval.HybridRetriever.class);
        when(retriever.retrieve("检查测试服务", 5)).thenReturn(List.of(
                new com.example.opsaiagent.knowledge.model.Document("测试运维知识")));
        var assistant = new com.example.opsaiagent.chat.config.LangChain4jConfig()
                .langChain4jOpsStreamingAssistant(model,
                        new com.example.opsaiagent.ai.adapter.langchain4j.ConversationChatMemoryStore(
                                mock(com.example.opsaiagent.chat.service.ConversationService.class), 20),
                        new LangChain4jReadOnlyToolBindings(health, metric,
                                mock(MetricDiscoveryTools.class), mock(ServiceDiscoveryTools.class)),
                        new com.example.opsaiagent.ai.adapter.langchain4j.LangChain4jKnowledgeContentRetriever(
                                retriever, 5));
        CompletableFuture<String> result = new CompletableFuture<>();
        var tokens = new StringBuilder();
        com.example.opsaiagent.rbac.context.TenantContext.set("fixture-tenant");
        try {
            assistant.askStream(LangChain4jMemoryId.of("alice", "stream-session"), "检查测试服务", "运维助手")
                    .onPartialResponseWithContext((response, context) -> tokens.append(response.text()))
                    .onCompleteResponse(response -> result.complete(response.aiMessage().text()))
                    .onError(result::completeExceptionally).start();
        } finally {
            com.example.opsaiagent.rbac.context.TenantContext.clear();
        }
        assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo("只读诊断完成");
        assertThat(tokens.toString()).isEqualTo("只读诊断完成");
        assertThat(requests).hasSize(3);
        assertThat(requests.getFirst().toString()).contains("测试运维知识");
        assertThat(toolResult(requests.get(1), "health-1")).isEqualTo("DOWN");
        assertThat(toolResult(requests.get(2), "metric-1")).isEqualTo("0.91");
    }

    /**
     * 构造只用于本地测试的流式工具调用响应。
     * @param id 工具调用标识
     * @param name 工具名称
     * @param arguments 参数 JSON
     * @return SSE 测试响应
     * @throws Exception JSON 序列化异常
     */
    private String streamingToolCall(String id, String name, String arguments) throws Exception {
        String delta = json.writeValueAsString(java.util.Map.of("role", "assistant", "tool_calls",
                List.of(java.util.Map.of("index", 0, "id", id, "type", "function", "function",
                        java.util.Map.of("name", name, "arguments", arguments)))));
        return "data: " + chunk(delta, null) + "\n\ndata: " + chunk("{}", "tool_calls")
                + "\n\ndata: [DONE]\n\n";
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
