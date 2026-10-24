package com.example.opsaiagent.rbac.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色 Mapper。
 *
 * 复合主键表（ops_user_role）使用注解 SQL 查询，避免 MyBatis-Plus 复合主键限制。
 */
@Mapper
public interface UserRoleMapper {

    /**
     * 查询用户拥有的角色 ID。
     *
     * @param userId 用户 ID
     * @return 角色 ID 列表；无角色时返回空列表
     */
    @Select("SELECT role_id FROM ops_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserId(@Param("userId") String userId);
}
