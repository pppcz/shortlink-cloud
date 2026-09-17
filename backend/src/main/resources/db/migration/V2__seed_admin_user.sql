-- =====================================================================
-- V2: 初始化默认管理员
--
-- 账号：admin
-- 密码：admin123
--
-- 哈希算法：PBKDF2-HMAC-SHA256，210000 次迭代，16 字节盐，32 字节派生密钥。
-- 选型说明：PBKDF2-HMAC-SHA256 由 JDK 原生提供
-- （javax.crypto.SecretKeyFactory），无需引入额外依赖，且本项目在离线环境
-- 下已用 Python hashlib.pbkdf2_hmac 复现完全相同的字节并验证通过。
-- 详见 docs/progress.md 的「与任务书的偏差」小节。
--
-- 存储格式：pbkdf2-sha256$<iterations>$<base64(salt)>$<base64(hash)>
-- 校验逻辑见 com.shortlink.cloud.util.Pbkdf2PasswordEncoder。
-- =====================================================================

INSERT INTO `t_user` (`id`, `username`, `password`, `nickname`, `email`, `role`, `status`)
SELECT 1,
       'admin',
       'pbkdf2-sha256$210000$Wks8LR4PECEyQ1RldoeYqQ==$RfAgy57mSojqBpfe9GZYHy5atqpysHk3JOmMagNFkKc=',
       '超级管理员',
       'admin@shortlink.local',
       'ADMIN',
       1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `t_user` WHERE `username` = 'admin');
