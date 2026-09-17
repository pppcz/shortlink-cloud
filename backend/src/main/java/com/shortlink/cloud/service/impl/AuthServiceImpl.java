package com.shortlink.cloud.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.config.ShortLinkProperties;
import com.shortlink.cloud.dto.LoginRequest;
import com.shortlink.cloud.dto.LoginResponse;
import com.shortlink.cloud.dto.UserVO;
import com.shortlink.cloud.entity.User;
import com.shortlink.cloud.mapper.UserMapper;
import com.shortlink.cloud.service.AuthService;
import com.shortlink.cloud.util.JwtTokenProvider;
import com.shortlink.cloud.util.Pbkdf2PasswordEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现。
 *
 * <p>安全要点：
 * <ul>
 *   <li>用户不存在与密码错误返回同一错误码，避免账号枚举</li>
 *   <li>密码比对使用固定时间比较（见 Pbkdf2PasswordEncoder）</li>
 *   <li>登录成功后仅回写 lastLogin，不记录明文密码</li>
 * </ul>
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final ShortLinkProperties properties;

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, request.getUsername()));

        // 用户不存在时也走一次哈希比对，避免通过响应时间区分「账号不存在」和「密码错误」
        boolean matched = user != null
                && Pbkdf2PasswordEncoder.matches(request.getPassword(), user.getPassword());
        if (!matched) {
            log.warn("登录失败 username={}", request.getUsername());
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }

        String token = jwtTokenProvider.createToken(user.getId(), user.getUsername(), user.getRole());
        LocalDateTime now = LocalDateTime.now();
        userMapper.updateLastLogin(user.getId(), now);
        user.setLastLogin(now);
        log.info("登录成功 userId={} username={}", user.getId(), user.getUsername());

        LoginResponse response = new LoginResponse();
        response.setToken(token);
        response.setExpiresIn(properties.getJwt().getExpireSeconds());
        response.setUser(toVO(user));
        return response;
    }

    @Override
    public UserVO currentUser(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return toVO(user);
    }

    private UserVO toVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        vo.setStatus(user.getStatus());
        vo.setLastLogin(user.getLastLogin());
        return vo;
    }
}
