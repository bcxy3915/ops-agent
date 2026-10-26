package com.example.opsaiagent.rbac.service;

import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import com.example.opsaiagent.rbac.mapper.UserIdentityMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UserIdentityService 单元测试。
 *
 * 覆盖：LOCAL 身份查询 / 空参数 / 不存在。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录身份服务")
class UserIdentityServiceTest {

    @Mock
    private UserIdentityMapper userIdentityMapper;

    @InjectMocks
    private UserIdentityService userIdentityService;

    @Test
    @DisplayName("findLocal：空标识返回空，不查库")
    void findLocal_blank() {
        assertThat(userIdentityService.findLocal(null)).isEmpty();
        assertThat(userIdentityService.findLocal(" ")).isEmpty();
        verifyNoInteractions(userIdentityMapper);
    }

    @Test
    @DisplayName("findLocal：命中邮箱身份")
    void findLocal_found() {
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserId("u-1");
        identity.setProvider("LOCAL");
        identity.setIdentifier("zhangwei@xinghai.com");
        when(userIdentityMapper.selectOne(any())).thenReturn(identity);

        Optional<UserIdentityEntity> result = userIdentityService.findLocal("zhangwei@xinghai.com");

        assertThat(result).contains(identity);
    }

    @Test
    @DisplayName("findLocal：未命中返回空")
    void findLocal_notFound() {
        when(userIdentityMapper.selectOne(any())).thenReturn(null);

        assertThat(userIdentityService.findLocal("nobody@xinghai.com")).isEmpty();
    }
}
