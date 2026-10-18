package com.example.opsaiagent.security.service;

import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpsUserService 单元测试
 * 覆盖：用户查询 / 条件列表与解密 / 创建用户加密 / 手机号邮箱精确查询
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户服务")
class OpsUserServiceTest {

    @Mock
    private OpsUserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AesGcmUtil aesGcmUtil;

    @Mock
    private HashUtil hashUtil;

    @InjectMocks
    private OpsUserService userService;

    private OpsUserEntity user(String username) {
        OpsUserEntity u = new OpsUserEntity();
        u.setId("u-" + username);
        u.setUsername(username);
        u.setPassword("encoded");
        u.setRole("OPERATOR");
        u.setEnabled(true);
        return u;
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("findByUsername：存在返回用户")
    void findByUsername_found() {
        when(userMapper.selectOne(any())).thenReturn(user("admin"));

        Optional<OpsUserEntity> result = userService.findByUsername("admin");

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("findByUsername：不存在返回空")
    void findByUsername_notFound() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThat(userService.findByUsername("nobody")).isEmpty();
    }

    @Test
    @DisplayName("existsByUsername：透传 mapper 结果")
    void existsByUsername() {
        when(userMapper.exists(any())).thenReturn(true);

        assertThat(userService.existsByUsername("admin")).isTrue();
    }

    @Test
    @DisplayName("findById：返回用户或空")
    void findById() {
        when(userMapper.selectById("u-admin")).thenReturn(user("admin"));
        when(userMapper.selectById("u-missing")).thenReturn(null);

        assertThat(userService.findById("u-admin")).isPresent();
        assertThat(userService.findById("u-missing")).isEmpty();
    }

    @Test
    @DisplayName("listByConditions：带 keyword/role/enabled 筛选并解密")
    void listByConditions_filters() {
        OpsUserEntity u = user("admin");
        u.setPhoneEnc("ENC-PHONE");
        u.setEmailEnc("ENC-EMAIL");
        when(userMapper.selectList(any())).thenReturn(List.of(u));
        when(aesGcmUtil.decrypt("ENC-PHONE")).thenReturn("13812345678");
        when(aesGcmUtil.decrypt("ENC-EMAIL")).thenReturn("admin@example.com");

        List<OpsUserEntity> result = userService.listByConditions("adm", "ADMIN", true);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPhone()).isEqualTo("13812345678");
        assertThat(result.get(0).getEmail()).isEqualTo("admin@example.com");
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("create：用户名已存在抛业务异常")
    void create_duplicateUsername_throws() {
        when(userMapper.exists(any())).thenReturn(true);
        UserCreateRequest request = createRequest("admin");

        assertThatThrownBy(() -> userService.create(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("create：成功创建并加密敏感字段")
    void create_success() {
        when(userMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("admin123")).thenReturn("bcrypt-hash");
        when(aesGcmUtil.encrypt("13812345678")).thenReturn("ENC-PHONE");
        when(aesGcmUtil.encrypt("admin@example.com")).thenReturn("ENC-EMAIL");
        when(hashUtil.sha256("13812345678")).thenReturn("HASH-PHONE");
        when(hashUtil.sha256("admin@example.com")).thenReturn("HASH-EMAIL");

        OpsUserEntity created = userService.create(createRequest("admin"));

        assertThat(created.getPassword()).isEqualTo("bcrypt-hash");
        assertThat(created.getEnabled()).isTrue();
        assertThat(created.getPhoneEnc()).isEqualTo("ENC-PHONE");
        assertThat(created.getPhoneHash()).isEqualTo("HASH-PHONE");
        assertThat(created.getEmailEnc()).isEqualTo("ENC-EMAIL");
        assertThat(created.getEmailHash()).isEqualTo("HASH-EMAIL");
        verify(userMapper).insert(created);
    }

    // ==================== 精确查询 ====================

    @Test
    @DisplayName("findByPhone：用哈希精确查询")
    void findByPhone_usesHash() {
        when(hashUtil.sha256("13812345678")).thenReturn("HASH-PHONE");
        when(userMapper.selectOne(any())).thenReturn(user("admin"));

        OpsUserEntity result = userService.findByPhone("13812345678");

        assertThat(result).isNotNull();
        verify(userMapper).selectOne(any());
    }

    @Test
    @DisplayName("findByEmail：用哈希精确查询")
    void findByEmail_usesHash() {
        when(hashUtil.sha256("admin@example.com")).thenReturn("HASH-EMAIL");
        when(userMapper.selectOne(any())).thenReturn(user("admin"));

        OpsUserEntity result = userService.findByEmail("admin@example.com");

        assertThat(result).isNotNull();
        verify(userMapper).selectOne(any());
    }

    // ==================== 解密 ====================

    @Test
    @DisplayName("decryptSensitiveFields：解密手机号和邮箱")
    void decryptSensitiveFields() {
        OpsUserEntity u = user("admin");
        u.setPhoneEnc("ENC-PHONE");
        u.setEmailEnc("ENC-EMAIL");
        when(aesGcmUtil.decrypt("ENC-PHONE")).thenReturn("13812345678");
        when(aesGcmUtil.decrypt("ENC-EMAIL")).thenReturn("admin@example.com");

        userService.decryptSensitiveFields(u);

        assertThat(u.getPhone()).isEqualTo("13812345678");
        assertThat(u.getEmail()).isEqualTo("admin@example.com");
    }

    @Test
    @DisplayName("decryptSensitiveFields：无加密字段时不做解密")
    void decryptSensitiveFields_noEncrypted() {
        OpsUserEntity u = user("admin");

        userService.decryptSensitiveFields(u);

        assertThat(u.getPhone()).isNull();
        assertThat(u.getEmail()).isNull();
    }

    private UserCreateRequest createRequest(String username) {
        UserCreateRequest req = new UserCreateRequest();
        req.setUsername(username);
        req.setPassword("admin123");
        req.setRole("OPERATOR");
        req.setPhone("13812345678");
        req.setEmail("admin@example.com");
        return req;
    }
}
