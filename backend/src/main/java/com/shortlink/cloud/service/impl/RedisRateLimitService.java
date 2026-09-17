package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.service.RateLimitService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.LongCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Redis + Lua 固定窗口限流实现。
 *
 * <p>Lua 脚本在启动时读取并缓存 SHA，之后用 {@code EVALSHA} 执行，
 * 避免每次请求都把脚本文本传给 Redis。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
public class RedisRateLimitService implements RateLimitService {

    private static final String SCRIPT_PATH = "lua/rate_limit.lua";

    private final RedissonClient redissonClient;

    private String scriptSha;

    public RedisRateLimitService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 预热脚本。
     *
     * <p>失败不阻断启动：限流服务会在首次调用时懒加载，
     * 且 Redis 故障时整体走 fail-open。
     */
    @PostConstruct
    void loadScript() {
        try {
            RScript script = redissonClient.getScript(LongCodec.INSTANCE);
            scriptSha = script.scriptLoad(readScript());
            log.info("限流 Lua 脚本加载完成 sha={}", scriptSha);
        } catch (Exception ex) {
            log.warn("限流 Lua 脚本预热失败（首次调用时将重试）: {}", ex.getMessage());
        }
    }

    @Override
    public RateLimitResult tryAcquire(String key, int windowSeconds, int limit) {
        if (windowSeconds <= 0 || limit <= 0) {
            return RateLimitResult.pass(0, Math.max(limit, 1));
        }
        try {
            RScript script = redissonClient.getScript(LongCodec.INSTANCE);
            String sha = scriptSha;
            List<Object> keys = Collections.singletonList(key);
            List<Object> args = Collections.singletonList(String.valueOf(windowSeconds));

            Long current;
            if (sha != null) {
                current = eval(script, sha, keys, args);
            } else {
                // 脚本尚未加载（或 Redis 曾重启导致缓存丢失），直接按脚本内容执行
                current = script.eval(RScript.Mode.READ_WRITE, readScript(),
                        RScript.ReturnType.INTEGER, keys, args.toArray());
                scriptSha = script.scriptLoad(readScript());
            }
            if (current == null) {
                return RateLimitResult.failOpen(limit);
            }
            return current <= limit
                    ? RateLimitResult.pass(current, limit)
                    : RateLimitResult.reject(current, limit);
        } catch (Exception ex) {
            log.warn("限流执行失败，按放行处理 key={} err={}", key, ex.getMessage());
            return RateLimitResult.failOpen(limit);
        }
    }

    private Long eval(RScript script, String sha, List<Object> keys, List<Object> args) {
        try {
            return script.evalSha(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.INTEGER, keys, args.toArray());
        } catch (Exception ex) {
            // NOSCRIPT：Redis 重启或脚本被清理，重新加载后重试一次
            log.debug("EVALSHA 失败，重新加载脚本: {}", ex.getMessage());
            scriptSha = script.scriptLoad(readScript());
            return script.evalSha(RScript.Mode.READ_WRITE, scriptSha,
                    RScript.ReturnType.INTEGER, keys, args.toArray());
        }
    }

    private String readScript() {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取限流脚本 " + SCRIPT_PATH, ex);
        }
    }
}
