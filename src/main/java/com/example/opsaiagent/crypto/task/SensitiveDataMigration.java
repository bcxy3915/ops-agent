package com.example.opsaiagent.crypto.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.crypto.config.CryptoProperties;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量敏感数据迁移
 * 执行条件：ops-agent.crypto.migrate-on-startup=true
 * 幂等性：只处理 phone_enc IS NULL 且 phone IS NOT NULL 的记录
 * 分批：每批 200 条，避免大事务
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "ops-agent.crypto.migrate-on-startup",
        havingValue = "true"
)
public class SensitiveDataMigration implements ApplicationRunner {

    private static final int BATCH_SIZE = 200;

    private final OpsUserMapper opsUserMapper;
    private final AesGcmUtil aesGcmUtil;
    private final HashUtil hashUtil;
    private final CryptoProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[迁移] ==================== 开始存量敏感数据加密迁移 ====================");
        long start = System.currentTimeMillis();

        int phoneCount = migratePhone();
        int emailCount = migrateEmail();

        long cost = System.currentTimeMillis() - start;
        log.info("[迁移] 完成: phone={} 条, email={} 条, 耗时={} ms",
                phoneCount, emailCount, cost);
        log.info("[迁移] ★ 请手动将 ops-agent.crypto.migrate-on-startup 改为 false 后重启");
    }

    private int migratePhone() {
        int total = 0;
        while (true) {
            List<OpsUserEntity> batch = opsUserMapper.selectList(
                    new LambdaQueryWrapper<OpsUserEntity>()
                            .isNotNull(OpsUserEntity::getPhone)
                            .ne(OpsUserEntity::getPhone, "")
                            .isNull(OpsUserEntity::getPhoneEnc)
                            .last("LIMIT " + BATCH_SIZE)
            );
            if (batch.isEmpty()) break;

            for (OpsUserEntity user : batch) {
                user.setPhoneEnc(aesGcmUtil.encrypt(user.getPhone()));
                user.setPhoneHash(hashUtil.sha256(user.getPhone()));
                opsUserMapper.updateById(user);
            }
            total += batch.size();
            log.info("[迁移] phone 已处理 {} 条", total);
        }
        return total;
    }

    private int migrateEmail() {
        int total = 0;
        while (true) {
            List<OpsUserEntity> batch = opsUserMapper.selectList(
                    new LambdaQueryWrapper<OpsUserEntity>()
                            .isNotNull(OpsUserEntity::getEmail)
                            .ne(OpsUserEntity::getEmail, "")
                            .isNull(OpsUserEntity::getEmailEnc)
                            .last("LIMIT " + BATCH_SIZE)
            );
            if (batch.isEmpty()) break;

            for (OpsUserEntity user : batch) {
                user.setEmailEnc(aesGcmUtil.encrypt(user.getEmail()));
                user.setEmailHash(hashUtil.sha256(user.getEmail()));
                opsUserMapper.updateById(user);
            }
            total += batch.size();
            log.info("[迁移] email 已处理 {} 条", total);
        }
        return total;
    }
}