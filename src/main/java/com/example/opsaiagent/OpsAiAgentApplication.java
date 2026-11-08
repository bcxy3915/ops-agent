package com.example.opsaiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** 统一使用 LangChain4j 的运维智能体应用入口。 */
@SpringBootApplication(exclude = {
        UserDetailsServiceAutoConfiguration.class
})
public class OpsAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsAiAgentApplication.class, args);
    }

}
