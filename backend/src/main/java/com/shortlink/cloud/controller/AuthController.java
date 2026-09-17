package com.shortlink.cloud.controller;

import com.shortlink.cloud.common.Result;
import com.shortlink.cloud.common.RequireLogin;
import com.shortlink.cloud.dto.LoginRequest;
import com.shortlink.cloud.dto.LoginResponse;
import com.shortlink.cloud.dto.UserVO;
import com.shortlink.cloud.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.shortlink.cloud.common.UserContext;

/**
 * 认证接口。
 *
 * @author shortlink-cloud
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录与当前用户")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "登录", description = "校验用户名密码并签发 JWT")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @GetMapping("/me")
    @RequireLogin
    @Operation(summary = "当前登录用户")
    public Result<UserVO> me() {
        return Result.success(authService.currentUser(UserContext.require().userId()));
    }
}
