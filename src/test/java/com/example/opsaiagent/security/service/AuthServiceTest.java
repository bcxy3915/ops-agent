package com.example.opsaiagent.security.service;

import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.dto.UserAuthorities;
import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import com.example.opsaiagent.rbac.service.PermissionService;
import com.example.opsaiagent.rbac.service.TenantService;
import com.example.opsaiagent.rbac.service.UserIdentityService;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.example.opsaiagent.security.dto.LoginResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试。
 *
 * <p>覆盖：旧用户名登录兼容 / 邮箱身份登录 / 用户不存在 / 密码错误 / 账号禁用 /
 * 租户停用 / 身份停用 / 角色与权限返回。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("认证服务")
class AuthServiceTest {

    @Mock
    private OpsUserService userService;

    @Mock
    private UserIdentityService userIdentityService;

    @Mock
    private TenantService tenantService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private LoginRequest validRequest;
    private OpsUserEntity enabledUser;

    @BeforeEach
    void setUp() {
        validRequest = new LoginRequest();
        validRequest.setUsername("admin");
        validRequest.setPassword("admin123");

        enabledUser = new OpsUserEntity();
        enabledUser.setId("u-admin");
        enabledUser.setUsername("admin");
        enabledUser.setPassword("$2a$10$hashed-password");
        enabledUser.setRole("ADMIN");
        enabledUser.setEnabled(true);
        enabledUser.setTenantId("t-default");
        enabledUser.setDeptId(10L);
    }

    // ==================== 成功 ====================

    @Test
    @DisplayName("login：旧用户名登录成功，返回租户/角色/权限")
    void login_legacyUsername_success() {
        mockLegacyUser("admin");
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);
        when(tenantService.isActive("t-default")).thenReturn(true);
        when(permissionService.loadAuthorities("u-admin"))
                .thenReturn(new UserAuthorities(Set.of("ADMIN"), Set.of("knowledge:read")));
        when(jwtService.generateToken("admin", "ADMIN", "u-admin", "t-default", 10L, List.of("ADMIN")))
                .thenReturn("mock-jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(validRequest);

        assertThat(response.getToken()).isEqualTo("mock-jwt-token");
        assertThat(response.getType()).isEqualTo("Bearer");
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getTenantId()).isEqualTo("t-default");
        assertThat(response.getDeptId()).isEqualTo(10L);
        assertThat(response.getRoles()).containsExactly("ADMIN");
        assertThat(response.getPermissions()).containsExactly("knowledge:read");
        assertThat(response.getNeedBindEmail()).isTrue();
        assertThat(response.getExpiresIn()).isEqualTo(7200L);

        verify(jwtService).generateToken("admin", "ADMIN", "u-admin", "t-default", 10L, List.of("ADMIN"));
    }

    @Test
    @DisplayName("login：邮箱身份登录成功，返回 needBindEmail 标记")
    void login_emailIdentity_success() {
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setId(1L);
        identity.setUserId("u-admin");
        identity.setProvider("LOCAL");
        identity.setIdentifier("zhangwei@xinghai.com");
        identity.setCredentialHash("$2a$10$identity-hash");
        identity.setNeedBindEmail(true);
        identity.setStatus("ACTIVE");

        LoginRequest request = new LoginRequest();
        request.setUsername("zhangwei@xinghai.com");
        request.setPassword("admin123");

        when(userIdentityService.findLocal("zhangwei@xinghai.com")).thenReturn(Optional.of(identity));
        when(userService.findById("u-admin")).thenReturn(Optional.of(enabledUser));
        when(passwordEncoder.matches("admin123", identity.getCredentialHash())).thenReturn(true);
        when(tenantService.isActive("t-default")).thenReturn(true);
        when(permissionService.loadAuthorities("u-admin"))
                .thenReturn(new UserAuthorities(Set.of("TENANT_ADMIN"), Set.of("knowledge:review")));
        when(jwtService.generateToken("admin", "ADMIN", "u-admin", "t-default", 10L, List.of("TENANT_ADMIN", "ADMIN")))
                .thenReturn("email-token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("email-token");
        assertThat(response.getRoles()).containsExactlyInAnyOrder("TENANT_ADMIN", "ADMIN");
        assertThat(response.getPermissions()).containsExactly("knowledge:review");
        assertThat(response.getNeedBindEmail()).isTrue();
    }

    @Test
    @DisplayName("login：enabled=null 时视为未禁用，放行")
    void login_nullEnabled_allowed() {
        enabledUser.setEnabled(null);
        mockLegacyUser("admin");
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);
        when(tenantService.isActive("t-default")).thenReturn(true);
        when(permissionService.loadAuthorities("u-admin"))
                .thenReturn(new UserAuthorities(Set.of("ADMIN"), Set.of()));
        when(jwtService.generateToken("admin", "ADMIN", "u-admin", "t-default", 10L, List.of("ADMIN")))
                .thenReturn("token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(validRequest);

        assertThat(response.getToken()).isEqualTo("token");
    }

    // ==================== 失败 ====================

    @Test
    @DisplayName("login：用户不存在抛 BusinessException")
    void login_userNotFound() {
        when(userIdentityService.findLocal("admin")).thenReturn(Optional.empty());
        when(userService.findByUsername("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString(), anyString(), eq(10L), anyList());
    }

    @Test
    @DisplayName("login：密码错误抛 BusinessException")
    void login_wrongPassword() {
        mockLegacyUser("admin");
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString(), anyString(), eq(10L), anyList());
    }

    @Test
    @DisplayName("login：账号已禁用抛 BusinessException")
    void login_disabledAccount() {
        enabledUser.setEnabled(false);
        mockLegacyUser("admin");
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已禁用");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString(), anyString(), eq(10L), anyList());
    }

    @Test
    @DisplayName("login：租户停用抛 BusinessException")
    void login_tenantDisabled() {
        mockLegacyUser("admin");
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);
        when(tenantService.isActive("t-default")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("所属企业已停用");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString(), anyString(), eq(10L), anyList());
    }

    @Test
    @DisplayName("login：身份状态非 ACTIVE 抛 BusinessException")
    void login_identityDisabled() {
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserId("u-admin");
        identity.setCredentialHash("$2a$10$identity-hash");
        identity.setStatus("DISABLED");

        LoginRequest request = new LoginRequest();
        request.setUsername("zhangwei@xinghai.com");
        request.setPassword("admin123");

        when(userIdentityService.findLocal("zhangwei@xinghai.com")).thenReturn(Optional.of(identity));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录方式已停用");
    }

    /**
     * 模拟旧用户名登录：邮箱身份不存在，回退 ops_user.username。
     *
     * @param username 用户名
     */
    private void mockLegacyUser(String username) {
        when(userIdentityService.findLocal(username)).thenReturn(Optional.empty());
        when(userService.findByUsername(username)).thenReturn(Optional.of(enabledUser));
    }
}
