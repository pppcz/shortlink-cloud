package com.shortlink.cloud.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.Constants;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.service.LinkConverter;
import com.shortlink.cloud.service.LocalSequenceShortCodeGenerator;
import com.shortlink.cloud.service.RedirectResult;
import com.shortlink.cloud.service.ShortCodeGenerator;
import com.shortlink.cloud.service.ShortLinkService;
import com.shortlink.cloud.util.UrlValidator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 短链服务实现。
 *
 * <p>阶段 1：短码由 Redis 发号（不可用时退化为本地序列），跳转直接查库。
 * 阶段 2 会在 {@link #resolve} 前增加 Redis 缓存与布隆过滤器，
 * 阶段 3 会把访问日志投递到 MQ 异步统计。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
public class ShortLinkServiceImpl implements ShortLinkService {

    /** 自定义短码只允许数字与大小写字母。 */
    private static final Pattern CUSTOM_CODE_PATTERN = Pattern.compile("^[0-9a-zA-Z]{4,16}$");

    private final ShortLinkMapper shortLinkMapper;
    private final LinkConverter linkConverter;
    private final ShortCodeGenerator redisCodeGenerator;
    private final LocalSequenceShortCodeGenerator fallbackCodeGenerator;

    public ShortLinkServiceImpl(ShortLinkMapper shortLinkMapper,
                                LinkConverter linkConverter,
                                ShortCodeGenerator redisCodeGenerator,
                                LocalSequenceShortCodeGenerator fallbackCodeGenerator) {
        this.shortLinkMapper = shortLinkMapper;
        this.linkConverter = linkConverter;
        this.redisCodeGenerator = redisCodeGenerator;
        this.fallbackCodeGenerator = fallbackCodeGenerator;
    }

    @Override
    public CreateLinkResponse createLink(CreateLinkRequest request, Long creatorId, String creatorIp) {
        String originalUrl = UrlValidator.validateAndNormalize(request.getOriginalUrl());
        request.setOriginalUrl(originalUrl);

        // 自定义短码先查重，给出明确错误而不是让唯一索引抛异常
        if (StringUtils.isNotBlank(request.getCustomCode())) {
            String customCode = request.getCustomCode().trim();
            if (!CUSTOM_CODE_PATTERN.matcher(customCode).matches()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "自定义短码只能包含字母和数字，长度 4-16");
            }
            if (existsByCode(customCode)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "该自定义短码已被占用：" + customCode);
            }
            return persist(request, customCode, creatorId, creatorIp, true);
        }

        return persist(request, null, creatorId, creatorIp, false);
    }

    @Override
    public RedirectResult resolve(String shortCode, String clientIp, String userAgent) {
        if (StringUtils.isBlank(shortCode)) {
            return RedirectResult.notFound();
        }
        ShortLink link = findByCode(shortCode);
        if (link == null) {
            return RedirectResult.notFound();
        }
        LocalDateTime now = LocalDateTime.now();
        if (!link.isRedirectable(now)) {
            return RedirectResult.gone(link);
        }
        // 阶段 1：直接记录最近访问时间，保证后台有实时反馈。
        // 阶段 3 起改为投递 MQ 消息，由消费者批量累加 PV/UV。
        try {
            shortLinkMapper.updateLastAccess(link.getId(), now);
        } catch (Exception ex) {
            // 统计失败不能影响跳转本身
            log.warn("更新最近访问时间失败 linkId={} err={}", link.getId(), ex.getMessage());
        }
        return RedirectResult.found(link);
    }

    @Override
    public void disable(Long id) {
        ShortLink existing = getByIdOrThrow(id);
        if (existing.getStatus() != null && existing.getStatus() == Constants.STATUS_DISABLED) {
            return;
        }
        shortLinkMapper.updateStatus(id, Constants.STATUS_DISABLED);
        log.info("短链已禁用 id={} code={}", id, existing.getShortCode());
    }

    @Override
    public ShortLink getByIdOrThrow(Long id) {
        if (id == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "短链 ID 不能为空");
        }
        ShortLink link = shortLinkMapper.selectById(id);
        if (link == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }
        return link;
    }

    /**
     * 按短码查询（唯一索引命中）。
     *
     * @param shortCode 短码
     * @return 短链实体，不存在返回 null
     */
    private ShortLink findByCode(String shortCode) {
        return shortLinkMapper.selectOne(
                Wrappers.<ShortLink>lambdaQuery().eq(ShortLink::getShortCode, shortCode));
    }

    private boolean existsByCode(String shortCode) {
        return shortLinkMapper.exists(
                Wrappers.<ShortLink>lambdaQuery().eq(ShortLink::getShortCode, shortCode));
    }

    /**
     * 落库，必要时重试短码冲突。
     *
     * @param request   创建请求
     * @param fixedCode 自定义短码，null 表示需要生成
     * @param creatorId 创建人 ID
     * @param creatorIp 创建人 IP
     * @param custom    是否为自定义短码（自定义短码冲突不重试，直接报错）
     * @return 创建结果
     */
    private CreateLinkResponse persist(CreateLinkRequest request, String fixedCode,
                                       Long creatorId, String creatorIp, boolean custom) {
        int maxAttempts = custom ? 1 : Constants.SHORT_CODE_MAX_RETRY;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String shortCode = custom ? fixedCode : nextCode();
            ShortLink entity = linkConverter.toEntity(request, shortCode, creatorId, creatorIp);
            entity.setId(IdWorker.getId());
            try {
                shortLinkMapper.insert(entity);
                log.info("短链创建成功 code={} id={} generator={}",
                        shortCode, entity.getId(), custom ? "custom" : "auto");
                return linkConverter.toCreateResponse(entity);
            } catch (DuplicateKeyException ex) {
                // 唯一索引兜底：并发下不同实例可能生成相同短码
                log.warn("短码冲突 code={} attempt={}/{}", shortCode, attempt, maxAttempts);
                if (custom) {
                    throw new BizException(ErrorCode.PARAM_INVALID, "该自定义短码已被占用：" + shortCode);
                }
            }
        }
        log.error("短码生成重试 {} 次仍冲突", maxAttempts);
        throw new BizException(ErrorCode.SHORT_CODE_EXHAUSTED);
    }

    /** 取下一个短码，Redis 不可用时降级到本地序列。 */
    private String nextCode() {
        try {
            return redisCodeGenerator.nextCode();
        } catch (Exception ex) {
            log.warn("Redis 发号失败，降级为本地序列生成器: {}", ex.getMessage());
            return fallbackCodeGenerator.nextCode();
        }
    }

    /**
     * 校验短码字符集（供 Controller 在做重定向前置判断时使用）。
     *
     * @param shortCode 短码
     * @return 是否为合法短码格式
     */
    public static boolean isWellFormedCode(String shortCode) {
        if (StringUtils.isBlank(shortCode) || shortCode.length() > 32) {
            return false;
        }
        for (int i = 0; i < shortCode.length(); i++) {
            char c = shortCode.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 规范化短码（仅保留字母数字，用于日志防注入）。 */
    static String normalizeForLog(String shortCode) {
        if (shortCode == null) {
            return "";
        }
        String cleaned = shortCode.replaceAll("[^0-9a-zA-Z]", "");
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned.toLowerCase(Locale.ROOT);
    }
}
