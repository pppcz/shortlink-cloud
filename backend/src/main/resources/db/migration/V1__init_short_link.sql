-- =====================================================================
-- V1: 短链平台核心表
-- 说明：
--   1. 所有时间字段统一使用 DATETIME，由应用侧写入（避免时区歧义）。
--   2. t_short_link 使用逻辑删除（deleted 字段），unique 索引不含 deleted，
--      因此短码一经分配即永久占用，防止逻辑删除后短码被复用导致串链。
--   3. 跳转热路径只按 short_code 查询，故 short_code 唯一索引是核心索引。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 用户表：仅做基础登录 / JWT，不含复杂权限体系
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_user`
(
    `id`          BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `username`    VARCHAR(64)  NOT NULL COMMENT '登录名',
    `password`    VARCHAR(128) NOT NULL COMMENT '密码哈希，格式 pbkdf2-sha256$iters$salt$hash',
    `nickname`    VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `email`       VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
    `role`        VARCHAR(32)  NOT NULL DEFAULT 'ADMIN' COMMENT '角色：ADMIN / USER',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `last_login`  DATETIME              DEFAULT NULL COMMENT '最近登录时间',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常 1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='平台用户';

-- ---------------------------------------------------------------------
-- 短链表
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_short_link`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `short_code`    VARCHAR(16)  NOT NULL COMMENT '短码，Base62',
    `original_url`  VARCHAR(2048) NOT NULL COMMENT '原始长链接',
    `title`         VARCHAR(256)          DEFAULT NULL COMMENT '标题 / 备注',
    `creator_id`    BIGINT                DEFAULT NULL COMMENT '创建人 ID，匿名创建为 NULL',
    `creator_ip`    VARCHAR(64)           DEFAULT NULL COMMENT '创建人 IP',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    `expire_time`   DATETIME              DEFAULT NULL COMMENT '过期时间，NULL 表示永久',
    `pv`            BIGINT       NOT NULL DEFAULT 0 COMMENT '累计访问次数（冗余，异步累加）',
    `uv`            BIGINT       NOT NULL DEFAULT 0 COMMENT '累计独立访客（冗余，异步累加）',
    `last_access`   DATETIME              DEFAULT NULL COMMENT '最近一次访问时间',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-正常 1-删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_short_code` (`short_code`),
    KEY `idx_creator_create` (`creator_id`, `create_time`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_status_expire` (`status`, `expire_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='短链映射';
