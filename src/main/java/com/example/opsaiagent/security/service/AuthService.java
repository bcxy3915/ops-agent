package com.example.opsaiagent.security.service;

import com.example.opsaiagent.dto.ErrorCode;
import com.example.opsaiagent.exception.BusinessException;
import com.example.opsaiagent.security.dto.LoginRequest;
import com.example.opsaiagent.security.dto.LoginResponse;
import com.example.opsaiagent.security.entity.OpsUserEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final OpsUserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 登录
     * @param request 登录请求
     * @return 登录响应
     */
    public LoginResponse login(LoginRequest request) {
        OpsUserEntity user = userService.findByUsername(request.getUsername())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PARAMETER, "用户名或密码错误"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败: username={}, 密码错误", request.getUsername());
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "用户名或密码错误");
        }

        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "账号已禁用");
        }

        String token = jwtService.generateToken(user.getUsername(), user.getRole());
        log.info("登录成功: username={}, role={}", user.getUsername(), user.getRole());

        return LoginResponse.builder()
                .token(token)
                .type("Bearer")
                .username(user.getUsername())
                .role(user.getRole())
                .expiresIn(jwtService.getExpirationSeconds())
                .build();
    }
}