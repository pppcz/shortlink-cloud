package com.shortlink.cloud.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务错误码。
 *
 * <p>约定：0 表示成功；1xxxx 参数类；2xxxx 资源类；3xxxx 鉴权类；4xxxx 限流类；5xxxx 系统类。
 *
 * @author shortlink-cloud
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "success", 200),

    // ---------------- 参数 ----------------
    PARAM_INVALID(10001, "参数校验失败", 400),
    URL_INVALID(10002, "原始链接格式不合法", 400),
    URL_BLOCKED(10003, "该域名被禁止创建短链", 400),

    // ---------------- 资源 ----------------
    LINK_NOT_FOUND(20001, "短链不存在", 404),
    LINK_DISABLED(20002, "短链已被禁用", 410),
    LINK_EXPIRED(20003, "短链已过期", 410),
    SHORT_CODE_EXHAUSTED(20004, "短码生成失败，请重试", 500),
    USER_NOT_FOUND(20005, "用户不存在", 404),
    USER_DISABLED(20006, "用户已被禁用", 403),

    // ---------------- 鉴权 ----------------
    UNAUTHORIZED(30001, "未登录或登录已过期", 401),
    FORBIDDEN(30002, "无权限访问", 403),
    LOGIN_FAILED(30003, "用户名或密码错误", 401),

    // ---------------- 限流 ----------------
    RATE_LIMITED(40001, "请求过于频繁，请稍后再试", 429),
    CREATE_LIMITED(40002, "今日创建短链数量已达上限", 429),

    // ---------------- 系统 ----------------
    SYSTEM_ERROR(50000, "系统繁忙，请稍后再试", 500);

    private final int code;
    private final String message;
    private final int httpStatus;
}
