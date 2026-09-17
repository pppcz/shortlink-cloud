-- =====================================================================
-- V3: 访问日志与统计表
--
-- 设计说明：
--   * 跳转接口只投递 MQ 消息，由消费者批量落库，写放大被集中到消费侧。
--   * t_link_access_log 是明细表，数据量最大；按 (short_code, access_date, hour)
--     建索引，支撑按天/按小时的报表查询。
--   * t_link_stats 是按天聚合表，用 (short_code, stat_date) 唯一键做幂等 upsert，
--     消费者重复投递不会把 PV 算重。
--   * t_link_uv_log 解决「日 UV 去重」：聚合表只有一个当天 UV 数字，
--     无法判断某个 IP 今天是否已经来过。这里靠唯一键 (short_code, stat_date, ip_hash)
--     做一次「首次访问」判定，只有插入成功才给 UV +1。每日数据量可控，
--     由保留策略定期清理。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 访问明细日志
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_link_access_log`
(
    `id`            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `short_code`    VARCHAR(16)  NOT NULL COMMENT '短码',
    `link_id`       BIGINT                DEFAULT NULL COMMENT '短链 ID',
    `original_url`  VARCHAR(2048)         DEFAULT NULL COMMENT '访问时的原始链接快照',
    `client_ip`     VARCHAR(64)           DEFAULT NULL COMMENT '客户端 IP',
    `ip_hash`       CHAR(32)              DEFAULT NULL COMMENT 'IP 的 MD5，用于 UV 去重且不落明文',
    `user_agent`    VARCHAR(512)          DEFAULT NULL COMMENT 'User-Agent',
    `referer`       VARCHAR(1024)         DEFAULT NULL COMMENT '来源页',
    `access_time`   DATETIME     NOT NULL COMMENT '访问时间',
    `access_date`   DATE         NOT NULL COMMENT '访问日期（冗余，报表按天扫描）',
    `hour`          TINYINT      NOT NULL COMMENT '访问小时 0-23（冗余，按小时聚合用）',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (`id`),
    KEY `idx_code_date_hour` (`short_code`, `access_date`, `hour`),
    KEY `idx_date` (`access_date`),
    KEY `idx_access_time` (`access_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='短链访问明细日志';

-- ---------------------------------------------------------------------
-- 按天聚合统计
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_link_stats`
(
    `id`          BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    `short_code`  VARCHAR(16) NOT NULL COMMENT '短码',
    `link_id`     BIGINT               DEFAULT NULL COMMENT '短链 ID',
    `stat_date`   DATE        NOT NULL COMMENT '统计日期',
    `pv`          BIGINT      NOT NULL DEFAULT 0 COMMENT '当日访问量',
    `uv`          BIGINT      NOT NULL DEFAULT 0 COMMENT '当日独立访客',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date` (`short_code`, `stat_date`),
    KEY `idx_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='短链按天统计';

-- ---------------------------------------------------------------------
-- 每日 UV 去重记录
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_link_uv_log`
(
    `id`         BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    `short_code` VARCHAR(16) NOT NULL COMMENT '短码',
    `stat_date`  DATE        NOT NULL COMMENT '统计日期',
    `ip_hash`    CHAR(32)    NOT NULL COMMENT 'IP 的 MD5',
    `create_time` DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次访问时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code_date_ip` (`short_code`, `stat_date`, `ip_hash`),
    KEY `idx_date` (`stat_date`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='短链每日 UV 去重明细';
