package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.example.opsaiagent.security.dto.LoginResponse;
import com.example.opsaiagent.security.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AuthController 单元测试
 * 覆盖：登录 / 获取当前用户
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("认证接口")
class AuthControllerTest {

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController controller;

    @Test
    @DisplayName("login：透传认证服务结果")
    void login_success() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        LoginResponse response = LoginResponse.builder()
                .token("jwt-token")
                .type("Bearer")
                .username("admin")
                .role("ADMIN")
                .build();
        when(authService.login(request)).thenReturn(response);

        ApiResponse<LoginResponse> result = controller.login(request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getToken()).isEqualTo("jwt-token");
        assertThat(result.getData().getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("me：返回用户名与权限")
    void me_returnsUserInfo() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("admin");
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .when(authentication).getAuthorities();

        ApiResponse<Map<String, Object>> result = controller.me(authentication);

        assertThat(result.getData()).containsEntry("username", "admin");
        assertThat(result.getData()).containsKey("authorities");
    }
}
