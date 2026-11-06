package com.example.opsaiagent.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.opsaiagent.common.error.ErrorCode;
import com.example.opsaiagent.crypto.util.AesGcmUtil;
import com.example.opsaiagent.crypto.util.HashUtil;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.rbac.entity.RoleEntity;
import com.example.opsaiagent.rbac.entity.UserIdentityEntity;
import com.example.opsaiagent.rbac.mapper.RoleMapper;
import com.example.opsaiagent.rbac.mapper.UserIdentityMapper;
import com.example.opsaiagent.rbac.mapper.UserRoleMapper;
import com.example.opsaiagent.rbac.service.DeptService;
import com.example.opsaiagent.rbac.service.PermissionService;
import com.example.opsaiagent.security.dto.UserCreateRequest;
import com.example.opsaiagent.security.dto.UserUpdateRequest;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import com.example.opsaiagent.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * 用户服务。
 *
 * 用户同时维护三层信息：
 *  {@code ops_user}：账号、密码、租户、部门、历史角色；
 *  {@code ops_user_role}：新权限模型下的多角色；
 *  {@code ops_user_identity}：邮箱等登录身份，支撑后续微信/OIDC/SSO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsUserService {

    private final OpsUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AesGcmUtil aesGcmUtil;
    private final HashUtil hashUtil;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final UserIdentityMapper userIdentityMapper;
    private final DeptService deptService;
    private final PermissionService permissionService;

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
     * 判断租户内用户名是否已存在。
     *
     * @param tenantId 租户 ID；为空时退化为全局判断
     * @param username 用户名
     * @return true=已存在
     */
    public boolean existsByUsername(String tenantId, String username) {
        LambdaQueryWrapper<OpsUserEntity> wrapper = new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getUsername, username);
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(OpsUserEntity::getTenantId, tenantId);
        }
        return userMapper.exists(wrapper);
    }

    /**
     * 按 ID 查询。
     *
     * @param id 用户 ID
     * @return 用户
     */
    public Optional<OpsUserEntity> findById(String id) {
        return Optional.ofNullable(userMapper.selectById(id));
    }

    /**
     * 按 ID 查询，并校验当前用户是否有权访问。
     *
     * @param id 用户 ID
     * @return 用户
     */
    public OpsUserEntity getAccessibleUser(String id) {
        OpsUserEntity user = findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        checkTenantAccess(user);
        return user;
    }

    /**
     * 按条件查询用户列表（兼容旧调用，不限制租户/部门）。
     *
     * @param keyword 关键字
     * @param role    历史角色
     * @param enabled 是否启用
     * @return 用户列表
     */
    public List<OpsUserEntity> listByConditions(String keyword, String role, Boolean enabled) {
        return listByConditions(keyword, role, enabled, null, null);
    }

    /**
     * 按条件查询用户列表。
     *
     * @param keyword  关键字（用户名/ID）
     * @param role     历史角色
     * @param enabled  是否启用
     * @param tenantId 租户 ID；为空不限制
     * @param deptIds  可见部门 ID；null=不限制，空列表=无可见部门
     * @return 用户列表
     */
    public List<OpsUserEntity> listByConditions(String keyword, String role, Boolean enabled,
                                                String tenantId, List<Long> deptIds) {
        if (deptIds != null && deptIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<OpsUserEntity> wrapper = new LambdaQueryWrapper<>();
        if (tenantId != null && !tenantId.isBlank()) {
            wrapper.eq(OpsUserEntity::getTenantId, tenantId);
        }
        if (deptIds != null) {
            wrapper.in(OpsUserEntity::getDeptId, deptIds);
        }
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
        if (isDeptAdminOnly()) {
            // 部门管理员只能看到本部门及下级中的普通成员，不能看到企业管理员/平台管理员
            list = list.stream()
                    .filter(user -> getRoleCodes(user.getId()).stream()
                            .noneMatch(code -> "TENANT_ADMIN".equals(code) || "PLATFORM_ADMIN".equals(code)))
                    .toList();
        }
        // 逐条解密
        return list.stream().peek(this::decryptSensitiveFields).toList();
    }

    /**
     * 查询用户的角色编码。
     *
     * @param userId 用户 ID
     * @return 角色编码列表
     */
    public List<String> getRoleCodes(String userId) {
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<RoleEntity> roles = roleMapper.selectBatchIds(roleIds);
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        return roles.stream()
                .map(RoleEntity::getCode)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
    }

    /**
     * 创建用户。
     *
     * @param request 创建请求
     * @return 创建后的用户
     */
    @Transactional
    public OpsUserEntity create(UserCreateRequest request) {
        String tenantId = resolveCreateTenant(request.getTenantId());
        Long deptId = resolveCreateDept(tenantId, request.getDeptId());
        assertDeptInScope(tenantId, deptId);

        // 1. 校验租户内用户名唯一
        if (existsByUsername(tenantId, request.getUsername())) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS,
                    "用户名已存在: " + request.getUsername());
        }

        // 2. 构造实体
        OpsUserEntity user = new OpsUserEntity();
        user.setUsername(request.getUsername());
        user.setDisplayName(request.getDisplayName());
        // 3. BCrypt 加密密码（存储哈希值，不是明文）
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        // role 列为 NOT NULL：仅传 roleIds 时先给默认值，replaceRoles 会按新角色反推回写
        user.setRole(request.getRole() != null && !request.getRole().isBlank()
                ? request.getRole() : "VIEWER");
        user.setTenantId(tenantId);
        user.setDeptId(deptId);
        user.setEnabled(true);
        user.setPhone(request.getPhone());
        user.setEmail(request.getEmail());
        // 加密敏感字段（如手机号、邮箱）
        encryptSensitiveFields(user);

        // 4. 插入
        userMapper.insert(user);

        // 5. 绑定角色与登录身份
        replaceRoles(user, request.getRoleIds(), request.getRole(), tenantId, true);
        // 新角色可能反推出历史角色，需要回写，保证旧 hasRole 接口可用
        userMapper.updateById(user);
        createLocalIdentity(user, request.getEmail());
        log.info("创建用户成功: username={}, tenant={}, dept={}", user.getUsername(), tenantId, deptId);

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
    @Transactional
    public OpsUserEntity update(String id, UserUpdateRequest request) {
        OpsUserEntity user = findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        checkTenantAccess(user);
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
        if (request.getDeptId() != null) {
            deptService.requireDept(user.getTenantId(), request.getDeptId());
            assertDeptInScope(user.getTenantId(), request.getDeptId());
            user.setDeptId(request.getDeptId());
        }

        // 敏感字段：先解密当前明文再按请求更新
        decryptSensitiveFields(user);
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        // 重新加密并刷新 enc/hash
        user.setPhoneEnc(null);
        user.setPhoneHash(null);
        user.setEmailEnc(null);
        user.setEmailHash(null);
        encryptSensitiveFields(user);

        // 角色：roleIds 显式传入（含空数组）时覆盖；否则仅在传了历史 role 时映射
        boolean rolesChanged = false;
        if (request.getRoleIds() != null) {
            replaceRoles(user, request.getRoleIds(), request.getRole(), user.getTenantId(), false);
            rolesChanged = true;
        } else if (request.getRole() != null) {
            replaceRoles(user, null, request.getRole(), user.getTenantId(), false);
            rolesChanged = true;
        }
        if (rolesChanged) {
            permissionService.evict(user.getId());
        }

        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        // updateById 默认忽略 null，清除手机号/邮箱时需要显式 SET NULL
        if (request.getPhone() != null && request.getPhone().isBlank()) {
            userMapper.update(null, new LambdaUpdateWrapper<OpsUserEntity>()
                    .eq(OpsUserEntity::getId, user.getId())
                    .set(OpsUserEntity::getPhoneEnc, null)
                    .set(OpsUserEntity::getPhoneHash, null));
        }
        if (request.getEmail() != null && request.getEmail().isBlank()) {
            userMapper.update(null, new LambdaUpdateWrapper<OpsUserEntity>()
                    .eq(OpsUserEntity::getId, user.getId())
                    .set(OpsUserEntity::getEmailEnc, null)
                    .set(OpsUserEntity::getEmailHash, null));
        }

        // 邮箱变化时同步登录身份
        if (request.getEmail() != null) {
            syncLocalIdentity(user, request.getEmail());
        }

        log.info("更新用户成功: id={}, username={}", user.getId(), user.getUsername());
        return user;
    }

    /**
     * 删除用户
     * 防护：不能删除自己、内置 admin；系统至少保留一个 ADMIN
     */
    @Transactional
    public void delete(String id) {
        OpsUserEntity user = findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + id));
        checkTenantAccess(user);
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
        userRoleMapper.deleteByUserId(id);
        userIdentityMapper.delete(new LambdaQueryWrapper<UserIdentityEntity>()
                .eq(UserIdentityEntity::getUserId, id));
        userMapper.deleteById(id);
        permissionService.evict(id);
        log.info("删除用户成功: id={}, username={}", id, user.getUsername());
    }

    /**
     * 覆盖用户角色。
     *
     * @param user       用户
     * @param roleIds    角色 ID；null 表示未传
     * @param legacyRole 历史角色
     * @param tenantId   租户 ID
     * @param create     是否创建场景（无角色时给默认 VIEWER）
     */
    private void replaceRoles(OpsUserEntity user, List<Long> roleIds, String legacyRole,
                              String tenantId, boolean create) {
        LinkedHashSet<Long> target = new LinkedHashSet<>();
        boolean explicitRoleIds = roleIds != null && !roleIds.isEmpty();
        if (roleIds != null) {
            target.addAll(roleIds);
        } else {
            target.addAll(resolveLegacyRoleIds(legacyRole, tenantId));
        }
        if (target.isEmpty() && create) {
            // 新用户至少给一个只读角色
            resolveRoleByCode("VIEWER").ifPresent(role -> target.add(role.getId()));
        } else if (target.isEmpty()) {
            // 显式清空角色时降级为 VIEWER；历史角色映射失败时保留原角色，避免误降权
            user.setRole(legacyRole != null && !legacyRole.isBlank() ? legacyRole : "VIEWER");
        }

        if (!target.isEmpty()) {
            List<RoleEntity> roles = roleMapper.selectBatchIds(target);
            if (roles == null || roles.size() != target.size()) {
                throw new BusinessException(ErrorCode.ROLE_NOT_FOUND, "存在不合法的角色 ID");
            }
            // 非平台租户不能授予平台管理员；部门管理员只能授予部门级角色
            if (!isPlatformTenant(tenantId)
                    && roles.stream().anyMatch(r -> "PLATFORM_ADMIN".equals(r.getCode()))) {
                throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能授予平台管理员角色");
            }
            if (isDeptAdminOnly()
                    && roles.stream().anyMatch(r -> !"DEPT".equals(r.getScope()))) {
                throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "部门管理员只能授予部门级角色");
            }
            // 显式传 roleIds 时以新角色反推历史角色；否则以历史角色为准
            user.setRole(explicitRoleIds || legacyRole == null || legacyRole.isBlank()
                    ? toLegacyRole(roles) : legacyRole);
        }

        userRoleMapper.deleteByUserId(user.getId());
        for (Long roleId : target) {
            userRoleMapper.insert(user.getId(), roleId);
        }
    }

    /**
     * 将历史角色映射为新角色 ID。
     *
     * @param legacyRole ADMIN / OPERATOR / VIEWER
     * @param tenantId   租户 ID
     * @return 角色 ID 列表
     */
    private List<Long> resolveLegacyRoleIds(String legacyRole, String tenantId) {
        if (legacyRole == null || legacyRole.isBlank()) {
            return List.of();
        }
        String code;
        switch (legacyRole) {
            case "ADMIN" -> code = isPlatformTenant(tenantId) ? "PLATFORM_ADMIN" : "TENANT_ADMIN";
            case "OPERATOR" -> code = "CONTRIBUTOR";
            case "VIEWER" -> code = "VIEWER";
            default -> {
                return List.of();
            }
        }
        return resolveRoleByCode(code)
                .map(role -> List.of(role.getId()))
                .orElse(List.of());
    }

    /**
     * 按编码查询角色。
     *
     * @param code 角色编码
     * @return 角色
     */
    private Optional<RoleEntity> resolveRoleByCode(String code) {
        return Optional.ofNullable(roleMapper.selectOne(new LambdaQueryWrapper<RoleEntity>()
                .eq(RoleEntity::getCode, code)));
    }

    /**
     * 新角色反推历史角色，保证旧 hasRole 接口仍可用。
     *
     * @param roles 角色列表
     * @return ADMIN / OPERATOR / VIEWER
     */
    private String toLegacyRole(List<RoleEntity> roles) {
        boolean admin = roles.stream().anyMatch(r ->
                "PLATFORM_ADMIN".equals(r.getCode()) || "TENANT_ADMIN".equals(r.getCode()));
        if (admin) {
            return "ADMIN";
        }
        boolean operator = roles.stream().anyMatch(r ->
                "DEPT_ADMIN".equals(r.getCode()) || "CONTRIBUTOR".equals(r.getCode()));
        return operator ? "OPERATOR" : "VIEWER";
    }

    /**
     * 创建邮箱登录身份。
     *
     * @param user  用户
     * @param email 邮箱
     */
    private void createLocalIdentity(OpsUserEntity user, String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        Long exists = userIdentityMapper.selectCount(new LambdaQueryWrapper<UserIdentityEntity>()
                .eq(UserIdentityEntity::getProvider, "LOCAL")
                .eq(UserIdentityEntity::getIdentifier, email));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "邮箱已被使用: " + email);
        }
        UserIdentityEntity identity = new UserIdentityEntity();
        identity.setUserId(user.getId());
        identity.setProvider("LOCAL");
        identity.setIdentifier(email);
        identity.setCredentialHash(user.getPassword());
        identity.setNeedBindEmail(false);
        identity.setStatus("ACTIVE");
        identity.setCreatedAt(LocalDateTime.now());
        userIdentityMapper.insert(identity);
    }

    /**
     * 同步邮箱登录身份。
     *
     * @param user  用户
     * @param email 新邮箱；空串表示解绑
     */
    private void syncLocalIdentity(OpsUserEntity user, String email) {
        UserIdentityEntity identity = userIdentityMapper.selectOne(
                new LambdaQueryWrapper<UserIdentityEntity>()
                        .eq(UserIdentityEntity::getUserId, user.getId())
                        .eq(UserIdentityEntity::getProvider, "LOCAL"));

        if (email == null || email.isBlank()) {
            if (identity != null) {
                userIdentityMapper.deleteById(identity.getId());
            }
            return;
        }

        Long exists = userIdentityMapper.selectCount(new LambdaQueryWrapper<UserIdentityEntity>()
                .eq(UserIdentityEntity::getProvider, "LOCAL")
                .eq(UserIdentityEntity::getIdentifier, email)
                .ne(identity != null, UserIdentityEntity::getId, identity == null ? null : identity.getId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "邮箱已被使用: " + email);
        }

        if (identity == null) {
            createLocalIdentity(user, email);
        } else {
            identity.setIdentifier(email);
            identity.setCredentialHash(user.getPassword());
            identity.setNeedBindEmail(false);
            identity.setStatus("ACTIVE");
            userIdentityMapper.updateById(identity);
        }
    }

    /**
     * 解析创建用户的租户。
     *
     * @param requested 请求中的租户
     * @return 租户 ID
     */
    private String resolveCreateTenant(String requested) {
        String current = SecurityUtils.getCurrentTenantId();
        // 兼容旧 Token / 单租户调用：没有租户上下文时回退到引导租户
        if (current == null || current.isBlank()) {
            return requested == null || requested.isBlank() ? "t-default" : requested;
        }
        if ("0".equals(current)) {
            if (requested == null || requested.isBlank()) {
                throw new BusinessException(ErrorCode.TENANT_REQUIRED, "平台创建用户必须指定 tenantId");
            }
            return requested;
        }
        if (requested != null && !requested.isBlank() && !requested.equals(current)) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能跨租户创建用户");
        }
        return current;
    }

    /**
     * 解析创建用户的部门。
     *
     * @param tenantId  租户 ID
     * @param requested 请求中的部门
     * @return 部门 ID
     */
    private Long resolveCreateDept(String tenantId, Long requested) {
        Long deptId = requested;
        if (deptId == null && !isPlatformTenant(tenantId)) {
            deptId = SecurityUtils.getCurrentDeptId();
        }
        if (deptId != null) {
            deptService.requireDept(tenantId, deptId);
        }
        return deptId;
    }

    /**
     * 校验当前用户是否有权操作目标用户。
     *
     * @param user 目标用户
     */
    private void checkTenantAccess(OpsUserEntity user) {
        String current = SecurityUtils.getCurrentTenantId();
        if (isPlatformTenant(current)) {
            return;
        }
        if (current == null || !current.equals(user.getTenantId())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + user.getId());
        }

        List<String> targetRoles = getRoleCodes(user.getId());
        boolean targetPlatformAdmin = targetRoles.contains("PLATFORM_ADMIN");
        boolean targetTenantAdmin = targetRoles.contains("TENANT_ADMIN");

        if (SecurityUtils.hasRole("TENANT_ADMIN")) {
            if (targetPlatformAdmin) {
                throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能操作平台管理员");
            }
            return;
        }

        if (!isDeptAdminOnly()) {
            // 非部门管理员（如企业管理员/平台管理员）已在上方校验租户，不再限制部门
            return;
        }
        Long currentDept = SecurityUtils.getCurrentDeptId();
        if (currentDept == null || user.getDeptId() == null
                || !deptService.subtreeIds(current, currentDept).contains(user.getDeptId())) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在: " + user.getId());
        }
        if (targetPlatformAdmin || targetTenantAdmin) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "部门管理员不能操作企业管理员");
        }
    }

    /**
     * 是否仅部门管理员（不含企业管理员/平台管理员）。
     *
     * @return true=仅部门管理员
     */
    private boolean isDeptAdminOnly() {
        return !isPlatformTenant(SecurityUtils.getCurrentTenantId())
                && SecurityUtils.hasRole("DEPT_ADMIN")
                && !SecurityUtils.hasRole("TENANT_ADMIN");
    }

    /**
     * 校验目标部门是否在当前部门管理员的数据范围内。
     *
     * @param tenantId 租户 ID
     * @param deptId   目标部门 ID
     */
    private void assertDeptInScope(String tenantId, Long deptId) {
        if (!isDeptAdminOnly() || deptId == null) {
            return;
        }
        Long currentDept = SecurityUtils.getCurrentDeptId();
        if (currentDept == null || !deptService.subtreeIds(tenantId, currentDept).contains(deptId)) {
            throw new BusinessException(ErrorCode.USER_OPERATION_FORBIDDEN, "不能操作其他部门的用户");
        }
    }

    private boolean isPlatformTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() || "0".equals(tenantId);
    }
}
