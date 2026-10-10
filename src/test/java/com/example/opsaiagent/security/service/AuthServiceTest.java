package com.example.opsaiagent.security.service;

import com.example.opsaiagent.exception.BusinessException;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试
 * 覆盖：登录成功 / 用户不存在 / 密码错误 / 账号禁用 / enabled=null 放行
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("认证服务")
class AuthServiceTest {

    @Mock
    private OpsUserService userService;

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
        enabledUser.setUsername("admin");
        enabledUser.setPassword("$2a$10$hashed-password");
        enabledUser.setRole("ADMIN");
        enabledUser.setEnabled(true);
    }

    // ==================== 成功 ====================

    @Test
    @DisplayName("login：成功返回带 token 的响应")
    void login_success() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(enabledUser));
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("mock-jwt-token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(validRequest);

        assertThat(response.getToken()).isEqualTo("mock-jwt-token");
        assertThat(response.getType()).isEqualTo("Bearer");
        assertThat(response.getUsername()).isEqualTo("admin");
        assertThat(response.getRole()).isEqualTo("ADMIN");
        assertThat(response.getExpiresIn()).isEqualTo(7200L);

        verify(jwtService).generateToken("admin", "ADMIN");
    }

    @Test
    @DisplayName("login：enabled=null 时视为未禁用，放行")
    void login_nullEnabled_allowed() {
        enabledUser.setEnabled(null);
        when(userService.findByUsername("admin")).thenReturn(Optional.of(enabledUser));
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);
        when(jwtService.generateToken("admin", "ADMIN")).thenReturn("token");
        when(jwtService.getExpirationSeconds()).thenReturn(7200L);

        LoginResponse response = authService.login(validRequest);

        assertThat(response.getToken()).isEqualTo("token");
    }

    // ==================== 失败 ====================

    @Test
    @DisplayName("login：用户名不存在抛 BusinessException")
    void login_userNotFound() {
        when(userService.findByUsername("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");

        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    @DisplayName("login：密码错误抛 BusinessException")
    void login_wrongPassword() {
        when(userService.findByUsername("admin")).thenReturn(Optional.of(enabledUser));
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");

        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    @Test
    @DisplayName("login：账号已禁用抛 BusinessException")
    void login_disabledAccount() {
        enabledUser.setEnabled(false);
        when(userService.findByUsername("admin")).thenReturn(Optional.of(enabledUser));
        when(passwordEncoder.matches("admin123", enabledUser.getPassword())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(validRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号已禁用");

        verify(jwtService, never()).generateToken(anyString(), anyString());
    }
}