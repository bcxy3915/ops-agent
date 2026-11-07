package com.example.opsaiagent.ai.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiBoundaryContractTest {

    @Test
    void requestAndContextAreImmutableAtTheBoundary() {
        var request = new ChatRequest("s1", "健康吗", "system", null);
        var context = new ToolExecutionContext("t1", "u1", "trace", null);

        assertEquals("s1", request.sessionId());
        assertTrue(request.attributes().isEmpty());
        assertTrue(context.attributes().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> request.attributes().put("x", "y"));
    }

    @Test
    void streamContractExposesTheRequiredTerminalTypes() {
        var events = List.of(ChatStreamEvent.token("答案"),
                new ChatStreamEvent(ChatStreamEvent.Type.CITATIONS, "[]", null),
                ChatStreamEvent.done());

        assertEquals(List.of(ChatStreamEvent.Type.TOKEN,
                ChatStreamEvent.Type.CITATIONS, ChatStreamEvent.Type.DONE),
                events.stream().map(ChatStreamEvent::type).toList());
    }

    @Test
    void indexDocumentRejectsMissingEmbedding() {
        assertThrows(IllegalArgumentException.class,
                () -> new IndexDocument("id", "text", new float[0], null));
    }
}
