package com.example.opsaiagent.crypto.task;

import com.example.opsaiagent.crypto.config.CryptoProperties;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SensitiveDataMigration 单元测试
 * 覆盖：启动时存量手机号/邮箱的加密迁移，分批直至无待处理记录
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("存量敏感数据迁移")
class SensitiveDataMigrationTest {

    @Mock
    private OpsUserMapper opsUserMapper;

    @Mock
    private AesGcmUtil aesGcmUtil;

    @Mock
    private HashUtil hashUtil;

    @Mock
    private CryptoProperties properties;

    @InjectMocks
    private SensitiveDataMigration migration;

    private OpsUserEntity user(String phone, String email) {
        OpsUserEntity u = new OpsUserEntity();
        u.setId("u1");
        u.setUsername("admin");
        u.setPhone(phone);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("run：迁移手机号和邮箱并写入加密字段")
    void run_migratesPhoneAndEmail() {
        OpsUserEntity u1 = user("13812345678", "admin@example.com");
        // phone 两次查询：第一次有 1 条，第二次空；email 同理
        when(opsUserMapper.selectList(any())).thenReturn(
                List.of(u1), List.of(),
                List.of(u1), List.of());
        when(aesGcmUtil.encrypt("13812345678")).thenReturn("ENC-PHONE");
        when(aesGcmUtil.encrypt("admin@example.com")).thenReturn("ENC-EMAIL");
        when(hashUtil.sha256("13812345678")).thenReturn("HASH-PHONE");
        when(hashUtil.sha256("admin@example.com")).thenReturn("HASH-EMAIL");

        assertThatCode(() -> migration.run(null)).doesNotThrowAnyException();

        verify(opsUserMapper, times(2)).updateById(u1);
    }

    @Test
    @DisplayName("run：无待迁移数据时直接完成")
    void run_noData() {
        when(opsUserMapper.selectList(any())).thenReturn(List.of(), List.of());

        assertThatCode(() -> migration.run(null)).doesNotThrowAnyException();

        verify(opsUserMapper, org.mockito.Mockito.never()).updateById(any(OpsUserEntity.class));
    }

    @Test
    @DisplayName("run：查询异常时向上抛出（当前实现不捕获）")
    void run_queryExceptionSwallowed() {
        when(opsUserMapper.selectList(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> migration.run(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB error");
    }
}
