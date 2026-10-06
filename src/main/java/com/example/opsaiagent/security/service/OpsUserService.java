package com.example.opsaiagent.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

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
        // ★ 解密
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
        // ★ BCrypt 加密密码（存储哈希值，不是明文）
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setEnabled(true);
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        // 加密敏感字段（如手机号、邮箱）
        encryptSensitiveFields(user);

        // 3. 插入
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
}