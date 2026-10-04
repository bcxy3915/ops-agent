package com.example.opsaiagent;

import com.example.opsaiagent.retrieval.bm25.ChineseTokenizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OpsAiAgentApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void testTokenize() {
        ChineseTokenizer t = new ChineseTokenizer();
        System.out.println(t.tokenize("内存泄漏怎么排查"));
        System.out.println(t.tokenize("CPU 使用率过高"));
    }
}
