package com.example.opsaiagent.crypto.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 敏感字段加密配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ops-agent.crypto")
public class CryptoProperties {

    /**
     * AES-256-GCM 密钥（Base64，32 字节）
     */
    private String aesKey;

    /**
     * 密钥版本，用于未来轮换
     */
    private String keyVersion = "v1";

    /**
     * 是否在启动时自动迁移存量明文
     */
    private boolean migrateOnStartup = false;
}