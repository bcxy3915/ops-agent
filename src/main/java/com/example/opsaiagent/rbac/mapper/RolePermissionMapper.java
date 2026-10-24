package com.example.opsaiagent.rbac.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 角色-权限 Mapper。
 *
 * 复合主键表（ops_role_permission）使用注解 SQL 查询，避免 MyBatis-Plus 复合主键限制。
 */
@Mapper
public interface RolePermissionMapper {

    /**
     * 按角色 ID 批量查询权限 ID。
     *
     * @param roleIds 角色 ID 集合（不能为空）
     * @return 权限 ID 列表
     */
    @Select("""
            <script>
            SELECT permission_id
            FROM ops_role_permission
            WHERE role_id IN
            <foreach collection="roleIds" item="roleId" open="(" separator="," close=")">
                #{roleId}
            </foreach>
            </script>
            """)
    List<Long> selectPermissionIdsByRoleIds(@Param("roleIds") Collection<Long> roleIds);
}
