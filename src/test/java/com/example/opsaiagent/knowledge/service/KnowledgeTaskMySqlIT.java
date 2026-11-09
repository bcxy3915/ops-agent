package com.example.opsaiagent.knowledge.service;

import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Assumptions;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.example.opsaiagent.knowledge.mapper.KnowledgeIndexTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/** 使用真实 MySQL 验证任务 UPSERT、生成列唯一约束、SKIP LOCKED 和旧令牌隔离。 */
@EnabledIfSystemProperty(named = "storage.live-test", matches = "true")
class KnowledgeTaskMySqlIT {
    private JdbcTemplate jdbc;
    private KnowledgeIndexTaskMapper tasks;
    private TransactionTemplate tx;
    private final long docId = -Math.abs(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));

    /**
     * 直接连接本地 MySQL 并配置生产 Mapper。
     * @return 无返回值，无入参
     * @throws Exception Mapper 初始化异常
     */
    @BeforeEach
    void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:mysql://localhost:23306/opsdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "root", System.getenv("DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        // 不在存在真实待执行任务时运行全局领取测试，避免测试消费业务任务。
        Assumptions.assumeTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_index_task WHERE status IN ('PENDING','RUNNING')", Long.class) == 0);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(KnowledgeIndexTaskMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        tasks = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeIndexTaskMapper.class);
    }

    /**
     * 仅清理测试使用的随机负文档 ID 所关联任务。
     * @return 无返回值，无入参
     */
    @AfterEach
    void cleanup() { if (jdbc != null) jdbc.update("DELETE FROM knowledge_index_task WHERE doc_id=?", docId); }

    /**
     * 验证重复入队、过期令牌拒绝、任务重领与历史任务保留。
     * @return 无返回值，无入参
     */
    @Test
    void enqueueClaimAndFenceStaleWorkers() {
        tasks.enqueue(docId, 1, "task-it:" + docId);
        tasks.enqueue(docId, 2, "task-it:" + docId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_task WHERE doc_id=?", Long.class, docId)).isEqualTo(1);
        var old = tx.execute(status -> tasks.claimOne(30));
        assertThat(old).isNotNull();
        assertThat(old.getDocVersion()).isEqualTo(2);
        assertThat(old.getClaimToken()).isNotBlank();
        tasks.enqueue(docId, 3, "task-it:" + docId);
        var current = tx.execute(status -> tasks.claimOne(30));
        assertThat(current.getClaimToken()).isNotEqualTo(old.getClaimToken());
        assertThat(tasks.finish(old.getId(), "SUCCESS", null, old.getClaimToken())).isZero();
        assertThat(tasks.retry(current.getId(), "模拟失败", 0, current.getClaimToken())).isEqualTo(1);
        var retry = tx.execute(status -> tasks.claimOne(30));
        assertThat(retry.getAttempts()).isEqualTo(2);
        assertThat(tasks.finish(retry.getId(), "SUCCESS", null, retry.getClaimToken())).isEqualTo(1);
        tasks.enqueue(docId, 4, "task-it:" + docId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_task WHERE doc_id=?", Long.class, docId)).isEqualTo(2);
    }
}
