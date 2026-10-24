package com.example.opsaiagent.security.controller;

import com.example.opsaiagent.common.api.ApiResponse;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.service.OpsUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserController 单元测试
 * 覆盖：用户列表 / 单用户查询 / 创建用户 / 查询不存在抛业务异常
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户管理接口")
class UserControllerTest {

    @Mock
    private OpsUserService userService;

    @InjectMocks
    private UserController controller;

    private OpsUserEntity user(String username) {
        OpsUserEntity u = new OpsUserEntity();
        u.setId("u-" + username);
        u.setUsername(username);
        u.setPassword("encoded");
        u.setRole("OPERATOR");
        u.setEnabled(true);
        u.setPhone("13812345678");
        u.setEmail("admin@example.com");
        u.setCreatedAt(LocalDateTime.now());
        u.setUpdatedAt(LocalDateTime.now());
        return u;
    }

    @Test
    @DisplayName("list：返回转换后的用户响应列表")
    void list_success() {
        when(userService.listByConditions("adm", "ADMIN", true))
                .thenReturn(List.of(user("admin")));

        ApiResponse<List<UserResponse>> result = controller.list("adm", "ADMIN", true);

        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getUsername()).isEqualTo("admin");
        assertThat(result.getData().get(0).getRole()).isEqualTo("OPERATOR");
    }

    @Test
    @DisplayName("get：查询成功并解密敏感字段")
    void get_success() {
        when(userService.findById("u-admin")).thenReturn(Optional.of(user("admin")));

        ApiResponse<UserResponse> result = controller.get("u-admin");

        assertThat(result.getData().getUsername()).isEqualTo("admin");
        verify(userService).decryptSensitiveFields(org.mockito.ArgumentMatchers.any(OpsUserEntity.class));
    }

    @Test
    @DisplayName("get：用户不存在抛业务异常")
    void get_notFound_throws() {
        when(userService.findById("u-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get("u-missing"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("create：创建成功返回用户响应")
    void create_success() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("operator1");
        request.setPassword("admin123");
        request.setRole("OPERATOR");
        when(userService.create(request)).thenReturn(user("operator1"));

        ApiResponse<UserResponse> result = controller.create(request);

        assertThat(result.getData().getUsername()).isEqualTo("operator1");
    }
}
