package com.example.opsaiagent.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserUpdateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import com.example.opsaiagent.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsUserService {

    private final OpsUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AesGcmUtil aesGcmUtil;
    private final HashUtil hashUtil;

    public Optional<OpsUserEntity> findByUsername(String username) {
        return Optional.ofNullable(
                userMapper.selectOne(new LambdaQueryWrapper<OpsUserEntity>()
                        .eq(OpsUserEntity::getUsername, username))
        );
    }

    public boolean existsByUsername(String username) {
        return userMapper.exists(new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getUsername, username));
    }

    /**
     * 按 ID 查询
     */
    public Optional<OpsUserEntity> findById(String id) {
        return Optional.ofNullable(userMapper.selectById(id));
    }

    /**
     * 按条件查询用户列表
     */
    public List<OpsUserEntity> listByConditions(String keyword, String role, Boolean enabled) {
        LambdaQueryWrapper<OpsUserEntity> wrapper = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(OpsUserEntity::getUsername, kw)
                    .or()
                    .like(OpsUserEntity::getId, kw)
            );
        }

        if (role != null && !role.isBlank()) {
            wrapper.eq(OpsUserEntity::getRole, role);
        }

        if (enabled != null) {
            wrapper.eq(OpsUserEntity::getEnabled, enabled);
        }

        wrapper.orderByDesc(OpsUserEntity::getCreatedAt);
        List<OpsUserEntity> list = userMapper.selectList(wrapper);
        // 逐条解密
        return list.stream().peek(this::decryptSensitiveFields).toList();
    }

    /**
     * 创建用户
     */
    public OpsUserEntity create(UserCreateRequest request) {
        // 1. 校验用户名唯一
        if (existsByUsername(request.getUsername())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS,
                    "用户名已存在: " + request.getUsername());
        }

        // 2. 构造实体
        OpsUserEntity user = new OpsUserEntity();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        // 3. BCrypt 加密密码（存储哈希值，不是明文）
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setEnabled(true);
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        // 加密敏感字段（如手机号、邮箱）
        encryptSensitiveFields(user);

        // 4. 插入
        userMapper.insert(user);
        log.info("创建用户成功: username={}, role={}", user.getUsername(), user.getRole());

        return user;
    }

    /**
     * 按手机号精确查询
     */
    public OpsUserEntity findByPhone(String phone) {
        String hash = hashUtil.sha256(phone);
        return userMapper.selectOne(new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getPhoneHash, hash));
    }

    /**
     * 按邮箱精确查询
     */
    public OpsUserEntity findByEmail(String email) {
        String hash = hashUtil.sha256(email);
        return userMapper.selectOne(new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getEmailHash, hash));
    }

    /**
     * 加密敏感字段（写库前调用）
     */
    private void encryptSensitiveFields(OpsUserEntity entity) {
        if (entity.getPhone() != null && !entity.getPhone().isEmpty()) {
            entity.setPhoneEnc(aesGcmUtil.encrypt(entity.getPhone()));
            entity.setPhoneHash(hashUtil.sha256(entity.getPhone()));
        }
        if (entity.getEmail() != null && !entity.getEmail().isEmpty()) {
            entity.setEmailEnc(aesGcmUtil.encrypt(entity.getEmail()));
            entity.setEmailHash(hashUtil.sha256(entity.getEmail()));
        }
    }

    /**
     * 解密敏感字段（返回给前端前调用）
     */
    public void decryptSensitiveFields(OpsUserEntity entity) {
        if (entity.getPhoneEnc() != null) {
            entity.setPhone(aesGcmUtil.decrypt(entity.getPhoneEnc()));
        }
        if (entity.getEmailEnc() != null) {
            entity.setEmail(aesGcmUtil.decrypt(entity.getEmailEnc()));
        }
    }

    /**
     * 更新用户（只更新非空字段；phone/email 传空串表示清除）
     * 防护：不能禁用当前登录账号
     */
    public OpsUserEntity update(String id, UserUpdateRequest request) {
        OpsUserEntity user = findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        String currentUsername = SecurityUtils.getCurrentUsername();

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getRole() != null) {
            user.setRole(request.getRole());
        }
        if (request.getEnabled() != null) {
            if (Boolean.FALSE.equals(request.getEnabled()) && user.getUsername().equals(currentUsername)) {
                throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能禁用当前登录账号");
            }
            user.setEnabled(request.getEnabled());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }

        // 敏感字段：先解密当前明文再按请求更新
        decryptSensitiveFields(user);
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        // 重新加密并刷新 enc/hash（updateById 只更新非 null 字段，必须先清空旧的）
        user.setPhoneEnc(null);
        user.setPhoneHash(null);
        user.setEmailEnc(null);
        user.setEmailHash(null);
        encryptSensitiveFields(user);

        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("更新用户成功: id={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * 删除用户
     * 防护：不能删除自己、内置 admin；系统至少保留一个 ADMIN
     */
    public void delete(String id) {
        OpsUserEntity user = findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        String currentUsername = SecurityUtils.getCurrentUsername();
        if (user.getUsername().equals(currentUsername)) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能删除当前登录账号");
        }
        if ("admin".equals(user.getUsername())) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "内置 admin 账号不可删除");
        }
        if ("ADMIN".equals(user.getRole())) {
            Long adminCount = userMapper.selectCount(
                    new LambdaQueryWrapper<OpsUserEntity>().eq(OpsUserEntity::getRole, "ADMIN"));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "系统至少保留一个 ADMIN 账号");
            }
        }
        userMapper.deleteById(id);
        log.info("删除用户成功: id={}, username={}", id, user.getUsername());
    }
}
