package com.example.opsaiagent.rbac.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import com.example.opsaiagent.rbac.mapper.UserIdentityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 登录身份服务。
 *
 * 登录方式与用户解耦：邮箱密码使用 provider=LOCAL；
 * 后续微信/OIDC 只需新增 provider，不修改用户与权限模型。
 */
@Service
@RequiredArgsConstructor
public class UserIdentityService {

    /** 本地邮箱密码登录 */
    public static final String PROVIDER_LOCAL = "LOCAL";

    private final UserIdentityMapper userIdentityMapper;

    /**
     * 按登录方式 + 标识查询身份。
     *
     * @param provider   登录方式，如 LOCAL
     * @param identifier 登录标识，如邮箱
     * @return 身份；不存在时返回空
     */
    public Optional<UserIdentityEntity> findByProviderAndIdentifier(String provider, String identifier) {
        if (provider == null || provider.isBlank() || identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(userIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserIdentityEntity>()
                        .eq(UserIdentityEntity::getProvider, provider)
                        .eq(UserIdentityEntity::getIdentifier, identifier)));
    }

    /**
     * 查询本地邮箱密码身份。
     *
     * @param identifier 邮箱（或存量迁移账号的 username@local）
     * @return 身份；不存在时返回空
     */
    public Optional<UserIdentityEntity> findLocal(String identifier) {
        return findByProviderAndIdentifier(PROVIDER_LOCAL, identifier);
    }
}
