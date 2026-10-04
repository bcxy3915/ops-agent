package com.example.opsaiagent.security.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import com.example.opsaiagent.security.mapper.OpsUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OpsUserService {

    private final OpsUserMapper userMapper;

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户实体
     */
    public Optional<OpsUserEntity> findByUsername(String username) {
        return Optional.ofNullable(
                userMapper.selectOne(new LambdaQueryWrapper<OpsUserEntity>()
                        .eq(OpsUserEntity::getUsername, username))
        );
    }

    /**
     * 判断用户名是否存在
      * @param username 用户名
      * @return 是否存在
     */
    public boolean existsByUsername(String username) {
        return userMapper.exists(new LambdaQueryWrapper<OpsUserEntity>()
                .eq(OpsUserEntity::getUsername, username));
    }
}