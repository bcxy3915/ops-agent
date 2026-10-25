package com.example.opsaiagent.rbac.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户-角色 Mapper。
 *
 * 复合主键表（ops_user_role）使用注解 SQL 查询/维护，避免 MyBatis-Plus 复合主键限制。
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

    /**
     * 删除用户全部角色关系。
     *
     * @param userId 用户 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM ops_user_role WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") String userId);

    /**
     * 新增用户-角色关系。
     *
     * @param userId 用户 ID
     * @param roleId 角色 ID
     * @return 影响行数
     */
    @Insert("INSERT INTO ops_user_role(user_id, role_id) VALUES(#{userId}, #{roleId})")
    int insert(@Param("userId") String userId, @Param("roleId") Long roleId);
}
