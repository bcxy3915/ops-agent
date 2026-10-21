package com.example.opsaiagent.oncall.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.oncall.dto.OncallMemberCreateRequest;
import com.example.opsaiagent.oncall.dto.OncallMemberResponse;
import com.example.opsaiagent.oncall.entity.OncallMemberEntity;
import com.example.opsaiagent.oncall.mapper.OncallMemberMapper;
import com.example.opsaiagent.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 值班管理服务（P1 规格 7）
 * 极简全局值班人列表：主值班 PRIMARY / 备值班 BACKUP（可多人），不做排班轮换。
 * resolveRecipients 用于通知收件人解析：规则显式 toUsers 优先；L1→备值班、L2→ADMIN；表为空回退角色兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OncallMemberService {

    public static final String ROLE_PRIMARY = "PRIMARY";
    public static final String ROLE_BACKUP = "BACKUP";

    private final OncallMemberMapper memberMapper;

    /**
     * 添加值班人；同 user + role 幂等。
     */
    public OncallMemberResponse add(OncallMemberCreateRequest request) {
        Long exists = memberMapper.selectCount(new LambdaQueryWrapper<OncallMemberEntity>()
                .eq(OncallMemberEntity::getUserId, request.getUserId())
                .eq(OncallMemberEntity::getDutyRole, request.getDutyRole()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ErrorCode.USER_ALREADY_EXISTS,
                    "该用户已是" + request.getDutyRole() + "值班人");
        }
        OncallMemberEntity entity = new OncallMemberEntity();
        entity.setUserId(request.getUserId());
        entity.setDutyRole(request.getDutyRole());
        entity.setAddedBy(SecurityUtils.getCurrentUsername());
        entity.setAddedAt(LocalDateTime.now());
        memberMapper.insert(entity);
        return toResponse(entity);
    }

    /**
     * 值班人列表；可按角色筛选。
     */
    public List<OncallMemberResponse> list(String dutyRole) {
        LambdaQueryWrapper<OncallMemberEntity> wrapper = new LambdaQueryWrapper<OncallMemberEntity>()
                .orderByAsc(OncallMemberEntity::getDutyRole)
                .orderByAsc(OncallMemberEntity::getId);
        if (dutyRole != null && !dutyRole.isBlank()) {
            wrapper.eq(OncallMemberEntity::getDutyRole, dutyRole);
        }
        return memberMapper.selectList(wrapper).stream().map(this::toResponse).toList();
    }

    /**
     * 移除值班人
     */
    public void remove(Long id) {
        if (memberMapper.deleteById(id) == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "值班人不存在: " + id);
        }
    }

    /**
     * 通知收件人解析（P1 规格 7 联动）
     * 优先级：规则显式 toUsers 优先 → 按升级级别/角色取值班人 → 表为空返回空（由调用方按角色兜底）。
     *
     * @param toUsers     规则显式指定用户（逗号分隔，可空）
     * @param toRole      规则/升级目标角色（可空）
     * @param levelNo     升级级别（L1→BACKUP、L2→ADMIN、其他→PRIMARY；可空）
     * @return 收件人 user_id 列表（Long）
     */
    public List<Long> resolveRecipients(String toUsers, String toRole, Integer levelNo) {
        if (toUsers != null && !toUsers.isBlank()) {
            // 规则显式 toUsers 优先：逗号分隔的用户 ID 列表
            return java.util.Arrays.stream(toUsers.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return Long.parseLong(s);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }
        String role = resolveRoleByLevel(levelNo, toRole);
        if (role == null) {
            return List.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<OncallMemberEntity>()
                        .eq(OncallMemberEntity::getDutyRole, role))
                .stream().map(OncallMemberEntity::getUserId).toList();
    }

    /**
     * 升级级别 → 值班角色映射：L1→BACKUP、L2→ADMIN、其他→PRIMARY；无级别用规则角色。
     */
    private String resolveRoleByLevel(Integer levelNo, String toRole) {
        if (levelNo != null) {
            if (levelNo >= 2) {
                return "ADMIN";
            }
            if (levelNo == 1) {
                return ROLE_BACKUP;
            }
        }
        if ("ADMIN".equalsIgnoreCase(toRole)) {
            return "ADMIN";
        }
        if ("OPERATOR".equalsIgnoreCase(toRole)) {
            return ROLE_PRIMARY;
        }
        return ROLE_PRIMARY;
    }

    private OncallMemberResponse toResponse(OncallMemberEntity entity) {
        return OncallMemberResponse.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .dutyRole(entity.getDutyRole())
                .addedBy(entity.getAddedBy())
                .addedAt(entity.getAddedAt())
                .build();
    }
}
