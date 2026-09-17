package com.shortlink.cloud.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.config.ShortLinkProperties;
import com.shortlink.cloud.dto.CreateLinkRequest;
import com.shortlink.cloud.dto.CreateLinkResponse;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.service.LinkConverter;
import com.shortlink.cloud.service.LocalSequenceShortCodeGenerator;
import com.shortlink.cloud.service.RedirectResult;
import com.shortlink.cloud.service.ShortCodeGenerator;
import com.shortlink.cloud.service.ShortLinkCacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ShortLinkServiceImpl} 单元测试。
 *
 * <p>Mapper、发号器、缓存均为 mock，测试只覆盖业务编排逻辑
 * （校验、重试、状态判定、缓存穿透防护顺序），不依赖 MySQL / Redis。
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShortLinkServiceImplTest {

    private static final String DOMAIN = "http://short.test";

    @Mock
    private ShortLinkMapper shortLinkMapper;

    @Mock
    private ShortCodeGenerator redisCodeGenerator;

    @Mock
    private LocalSequenceShortCodeGenerator fallbackCodeGenerator;

    @Mock
    private ShortLinkCacheManager cacheManager;

    @Mock
    private com.shortlink.cloud.mq.LinkAccessProducer accessProducer;

    private ShortLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        ShortLinkProperties properties = new ShortLinkProperties();
        properties.setDomain(DOMAIN);
        LinkConverter converter = new LinkConverter(properties);
        service = new ShortLinkServiceImpl(shortLinkMapper, converter, redisCodeGenerator,
                fallbackCodeGenerator, cacheManager, accessProducer);
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自动生成短码：落库成功并返回完整短链")
    void shouldCreateLinkWithGeneratedCode() {
        when(redisCodeGenerator.nextCode()).thenReturn("Ab12Cd3");
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com/a/b");
        request.setTitle("示例");

        CreateLinkResponse response = service.createLink(request, 100L, "1.2.3.4");

        assertThat(response.getShortCode()).isEqualTo("Ab12Cd3");
        assertThat(response.getShortUrl()).isEqualTo(DOMAIN + "/Ab12Cd3");
        assertThat(response.getOriginalUrl()).isEqualTo("https://example.com/a/b");
        assertThat(response.getTitle()).isEqualTo("示例");

        ArgumentCaptor<ShortLink> captor = ArgumentCaptor.forClass(ShortLink.class);
        verify(shortLinkMapper).insert(captor.capture());
        ShortLink saved = captor.getValue();
        assertThat(saved.getCreatorId()).isEqualTo(100L);
        assertThat(saved.getCreatorIp()).isEqualTo("1.2.3.4");
        assertThat(saved.getStatus()).isEqualTo(1);
        assertThat(saved.getPv()).isZero();
        assertThat(saved.getUv()).isZero();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getExpireTime()).isNull();
    }

    @Test
    @DisplayName("新短码立即写入布隆过滤器，避免下次访问被误判为不存在")
    void shouldAddNewCodeToBloomFilter() {
        when(redisCodeGenerator.nextCode()).thenReturn("bloom01");
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");

        service.createLink(request, null, "1.2.3.4");

        verify(cacheManager).addToBloom("bloom01");
    }

    @Test
    @DisplayName("expireDays=30 时写入 30 天后的过期时间")
    void shouldSetExpireTimeFromExpireDays() {
        when(redisCodeGenerator.nextCode()).thenReturn("exp1234");
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");
        request.setExpireDays(30);

        LocalDateTime before = LocalDateTime.now().plusDays(30).minusSeconds(5);
        CreateLinkResponse response = service.createLink(request, null, "1.2.3.4");

        assertThat(response.getExpireTime()).isNotNull();
        assertThat(response.getExpireTime()).isAfter(before);
        assertThat(response.getExpireTime()).isBefore(LocalDateTime.now().plusDays(30).plusSeconds(5));
    }

    @Test
    @DisplayName("expireDays=0 视为永久有效")
    void shouldTreatZeroExpireDaysAsPermanent() {
        when(redisCodeGenerator.nextCode()).thenReturn("perm123");
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");
        request.setExpireDays(0);

        assertThat(service.createLink(request, null, "1.2.3.4").getExpireTime()).isNull();
    }

    @Test
    @DisplayName("自定义短码被占用时抛出参数异常，且不写库")
    void shouldRejectTakenCustomCode() {
        when(shortLinkMapper.exists(any(Wrapper.class))).thenReturn(true);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");
        request.setCustomCode("taken1");

        assertThatThrownBy(() -> service.createLink(request, null, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被占用");
    }

    @Test
    @DisplayName("自定义短码含非法字符时抛出参数异常")
    void shouldRejectMalformedCustomCode() {
        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");
        request.setCustomCode("bad code!");

        assertThatThrownBy(() -> service.createLink(request, null, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("自定义短码可用时按其落库")
    void shouldCreateWithAvailableCustomCode() {
        when(shortLinkMapper.exists(any(Wrapper.class))).thenReturn(false);
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");
        request.setCustomCode("myLink");

        CreateLinkResponse response = service.createLink(request, null, "1.2.3.4");

        assertThat(response.getShortCode()).isEqualTo("myLink");
        assertThat(response.getShortUrl()).isEqualTo(DOMAIN + "/myLink");
    }

    @Test
    @DisplayName("自动发号连续冲突时重试并最终成功")
    void shouldRetryOnDuplicateKey() {
        when(redisCodeGenerator.nextCode()).thenReturn("dup0001", "dup0002", "good003");
        when(shortLinkMapper.insert(any(ShortLink.class)))
                .thenThrow(new DuplicateKeyException("uk_short_code"))
                .thenThrow(new DuplicateKeyException("uk_short_code"))
                .thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");

        CreateLinkResponse response = service.createLink(request, null, "1.2.3.4");

        assertThat(response.getShortCode()).isEqualTo("good003");
        verify(shortLinkMapper, times(3)).insert(any(ShortLink.class));
    }

    @Test
    @DisplayName("重试次数耗尽后抛出短码耗尽错误")
    void shouldFailAfterMaxRetries() {
        when(redisCodeGenerator.nextCode()).thenReturn("dup0001");
        when(shortLinkMapper.insert(any(ShortLink.class)))
                .thenThrow(new DuplicateKeyException("uk_short_code"));

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");

        assertThatThrownBy(() -> service.createLink(request, null, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SHORT_CODE_EXHAUSTED);
    }

    @Test
    @DisplayName("Redis 发号异常时降级为本地序列生成器")
    void shouldFallbackWhenRedisUnavailable() {
        when(redisCodeGenerator.nextCode()).thenThrow(new RuntimeException("connection refused"));
        when(fallbackCodeGenerator.nextCode()).thenReturn("local01");
        when(shortLinkMapper.insert(any(ShortLink.class))).thenReturn(1);

        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("https://example.com");

        assertThat(service.createLink(request, null, "1.2.3.4").getShortCode()).isEqualTo("local01");
    }

    @Test
    @DisplayName("非法原始链接直接拒绝，不写库")
    void shouldRejectInvalidUrl() {
        CreateLinkRequest request = new CreateLinkRequest();
        request.setOriginalUrl("javascript:alert(1)");

        assertThatThrownBy(() -> service.createLink(request, null, "1.2.3.4"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.URL_INVALID);

        verify(shortLinkMapper, never()).insert(any(ShortLink.class));
    }

    // ------------------------------------------------------------------
    // 跳转：缓存 / 布隆过滤器 / 回源 三级链路
    // ------------------------------------------------------------------

    @Test
    @DisplayName("布隆过滤器判定不存在时直接 404，不查缓存也不回源")
    void shouldShortCircuitWhenBloomFilterMisses() {
        when(cacheManager.mightContain("ghost01")).thenReturn(false);

        RedirectResult result = service.resolve("ghost01", "1.2.3.4", "curl/8.0");

        assertThat(result.httpStatus()).isEqualTo(404);
        verify(cacheManager, never()).get(any());
        verify(shortLinkMapper, never()).selectOne(any(Wrapper.class));
    }

    @Test
    @DisplayName("缓存命中直接 302，不回源数据库")
    void shouldServeFromCache() {
        when(cacheManager.mightContain("hot1234")).thenReturn(true);
        when(cacheManager.get("hot1234")).thenReturn(link(1L, "hot1234", 1, null));

        RedirectResult result = service.resolve("hot1234", "1.2.3.4", "curl/8.0");

        assertThat(result.isFound()).isTrue();
        assertThat(result.targetUrl()).isEqualTo("https://example.com/target");
        verify(shortLinkMapper, never()).selectOne(any(Wrapper.class));
        verify(shortLinkMapper, never()).insert(any(ShortLink.class));
    }

    @Test
    @DisplayName("缓存未命中时回源数据库并回写缓存")
    void shouldLoadFromDatabaseAndPopulateCache() {
        when(cacheManager.mightContain("cold123")).thenReturn(true);
        when(cacheManager.get("cold123")).thenReturn(null);
        ShortLink fromDb = link(7L, "cold123", 1, null);
        when(shortLinkMapper.selectOne(any(Wrapper.class))).thenReturn(fromDb);

        RedirectResult result = service.resolve("cold123", "1.2.3.4", "curl/8.0");

        assertThat(result.isFound()).isTrue();
        verify(cacheManager).put(fromDb);
    }

    @Test
    @DisplayName("数据库未命中时写入空值标记，防缓存穿透")
    void shouldCacheNullWhenDatabaseMisses() {
        when(cacheManager.mightContain("void123")).thenReturn(true);
        when(cacheManager.get("void123")).thenReturn(null);
        when(shortLinkMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        RedirectResult result = service.resolve("void123", "1.2.3.4", "curl/8.0");

        assertThat(result.httpStatus()).isEqualTo(404);
        verify(cacheManager).putNull("void123");
    }

    @Test
    @DisplayName("缓存中的禁用短链返回 410")
    void shouldReturnGoneForDisabledLinkFromCache() {
        when(cacheManager.mightContain("dis1234")).thenReturn(true);
        when(cacheManager.get("dis1234")).thenReturn(link(2L, "dis1234", 0, null));

        assertThat(service.resolve("dis1234", "1.2.3.4", "curl/8.0").httpStatus()).isEqualTo(410);
    }

    @Test
    @DisplayName("缓存中的过期短链返回 410")
    void shouldReturnGoneForExpiredLinkFromCache() {
        when(cacheManager.mightContain("exp1234")).thenReturn(true);
        when(cacheManager.get("exp1234"))
                .thenReturn(link(3L, "exp1234", 1, LocalDateTime.now().minusSeconds(1)));

        assertThat(service.resolve("exp1234", "1.2.3.4", "curl/8.0").httpStatus()).isEqualTo(410);
    }

    @Test
    @DisplayName("命中短链时投递访问日志消息（不再同步写库）")
    void shouldPublishAccessMessageOnHit() {
        when(cacheManager.mightContain("abc1234")).thenReturn(true);
        when(cacheManager.get("abc1234")).thenReturn(link(5L, "abc1234", 1, null));

        service.resolve("abc1234", "1.2.3.4", "curl/8.0", "https://ref.example");

        verify(accessProducer).publish(any(ShortLink.class), eq("1.2.3.4"),
                anyString(), eq("curl/8.0"), eq("https://ref.example"));
        // 阶段 3 起跳转热路径不再直连数据库写统计
        verify(shortLinkMapper, never()).updateLastAccess(any(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("未命中或已失效的短链不投递访问日志")
    void shouldNotPublishWhenNotRedirectable() {
        when(cacheManager.mightContain("dis1234")).thenReturn(true);
        when(cacheManager.get("dis1234")).thenReturn(link(2L, "dis1234", 0, null));

        service.resolve("dis1234", "1.2.3.4", "curl/8.0");

        verify(accessProducer, never()).publish(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("投递异常不影响跳转结果")
    void shouldStillRedirectWhenPublishFails() {
        when(cacheManager.mightContain("ok12345")).thenReturn(true);
        when(cacheManager.get("ok12345")).thenReturn(link(4L, "ok12345", 1, null));
        doThrow(new RuntimeException("mq down"))
                .when(accessProducer).publish(any(), anyString(), anyString(), any(), any());
        RedirectResult result = service.resolve("ok12345", "1.2.3.4", "curl/8.0");

        assertThat(result.isFound()).isTrue();
        assertThat(result.targetUrl()).isEqualTo("https://example.com/target");
    }

    @Test
    @DisplayName("空短码直接 404，不触碰缓存与数据库")
    void shouldNotHitAnythingForBlankCode() {
        assertThat(service.resolve("  ", "1.2.3.4", "curl/8.0").httpStatus()).isEqualTo(404);
        verify(cacheManager, never()).mightContain(any());
        verify(shortLinkMapper, never()).selectOne(any(Wrapper.class));
    }

    // ------------------------------------------------------------------
    // 禁用
    // ------------------------------------------------------------------

    @Test
    @DisplayName("禁用短链写入 status=0 并立即失效缓存")
    void shouldDisableLinkAndInvalidateCache() {
        when(shortLinkMapper.selectById(9L)).thenReturn(link(9L, "disable1", 1, null));
        when(shortLinkMapper.updateStatus(9L, 0)).thenReturn(1);

        service.disable(9L);

        verify(shortLinkMapper).updateStatus(9L, 0);
        // 不失效缓存的话，禁用后仍会跳到 TTL 到期为止
        verify(cacheManager).invalidate("disable1");
    }

    @Test
    @DisplayName("短链不存在时禁用抛 404")
    void shouldThrowWhenDisablingMissingLink() {
        when(shortLinkMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.disable(404L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LINK_NOT_FOUND);
    }

    private ShortLink link(Long id, String code, int status, LocalDateTime expireTime) {
        ShortLink link = new ShortLink();
        link.setId(id);
        link.setShortCode(code);
        link.setOriginalUrl("https://example.com/target");
        link.setStatus(status);
        link.setExpireTime(expireTime);
        link.setPv(0L);
        link.setUv(0L);
        return link;
    }
}
