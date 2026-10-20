package com.example.opsaiagent.oncall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 值班人（oncall_member 表，P1 规格 7）
 * 极简全局值班：主值班 PRIMARY / 备值班 BACKUP，可多人；不做排班轮换。
 * 联动：首报→主值班、升级 L1（5min）→备值班、L2→ADMIN；规则显式 toUsers 优先；值班表为空回退角色兜底。
 */
@Data
@TableName("oncall_member")
public class OncallMemberEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 值班人用户 ID（对应用户表主键） */
    @TableField("user_id")
    private Long userId;

    /** 值班角色：PRIMARY（主值班）/ BACKUP（备值班） */
    @TableField("duty_role")
    private String dutyRole;

    /** 添加人（当前登录用户名） */
    @TableField("added_by")
    private String addedBy;

    @TableField("added_at")
    private LocalDateTime addedAt;
}
