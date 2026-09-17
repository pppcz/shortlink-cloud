-- =====================================================================
-- 由 docker-compose 挂载到 /docker-entrypoint-initdb.d，仅在数据卷首次
-- 初始化时执行。业务表结构一律由 Flyway 管理（backend/src/main/resources
-- /db/migration），此处只做数据库与账号层面的准备。
-- =====================================================================

-- 保证库存在且使用 utf8mb4
CREATE DATABASE IF NOT EXISTS `shortlink`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- 业务账号（compose 的 MYSQL_USER 已创建，这里兜底授权）
CREATE USER IF NOT EXISTS 'shortlink'@'%' IDENTIFIED BY 'shortlink123';
GRANT ALL PRIVILEGES ON `shortlink`.* TO 'shortlink'@'%';
FLUSH PRIVILEGES;
