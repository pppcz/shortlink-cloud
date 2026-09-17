package com.shortlink.cloud.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 Controller 类或方法上，表示该接口需要登录。
 *
 * <p>由 {@code JwtAuthInterceptor} 读取；未标注的接口一律放行（例如短链跳转）。
 *
 * @author shortlink-cloud
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireLogin {

    /**
     * 是否要求 ADMIN 角色。
     *
     * @return true 表示仅管理员可访问
     */
    boolean admin() default false;
}
