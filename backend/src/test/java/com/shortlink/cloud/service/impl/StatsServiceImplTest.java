package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.dto.StatsSummaryVO;
import com.shortlink.cloud.dto.LinkStatsVO;
import com.shortlink.cloud.dto.TrendPointVO;
import com.shortlink.cloud.entity.LinkAccessLog;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.LinkStatsMapper;
import com.shortlink.cloud.mapper.LinkUvLogMapper;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.mq.AccessLogBatchWriter;
import com.shortlink.cloud.mq.LinkAccessMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StatsServiceImpl} 单元测试。
 *
 * <p>重点验证三件事：
 * <ol>
 *   <li>PV/UV 是否按 (短码, 日期) 正确聚合，而不是逐条累加</li>
 *   <li>UV 是否只统计「当天首次访问」（依赖 INSERT IGNORE 的返回值）</li>
 *   <li>明细日志的时间冗余列（accessDate / hour）是否正确</li>
 * </ol>
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatsServiceImplTest {

    @Mock
    private LinkStatsMapper statsMapper;

    @Mock
    private LinkUvLogMapper uvLogMapper;

    @Mock
    private ShortLinkMapper shortLinkMapper;

    @Mock
    private AccessLogBatchWriter accessLogBatchWriter;

    @InjectMocks
    private StatsServiceImpl statsService;

    private final LocalDateTime accessTime = LocalDateTime.of(2026, 2, 14, 10, 30, 0);

    @BeforeEach
    void setUp() {
        when(statsMapper.sumByCode(anyString())).thenReturn(new StatsSummaryVO());
        when(statsMapper.selectTrend(anyString(), any(LocalDate.class))).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // 消费落库
    // ------------------------------------------------------------------

    @Test
    @DisplayName("空批次直接返回 0，不做任何写操作")
    void shouldSkipEmptyBatch() {
        assertThat(statsService.recordAccess(List.of())).isZero();
        assertThat(statsService.recordAccess(null)).isZero();
        verify(accessLogBatchWriter, never()).writeBatch(any());
    }

    @Test
    @DisplayName("同一短码同一天的多次访问聚合为一次 upsert（PV=3）")
    void shouldAggregateSameCodeSameDay() {
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), anyString())).thenReturn(1);
        List<LinkAccessMessage> messages = List.of(
                message("abc1234", 1L, "hash-a", accessTime),
                message("abc1234", 1L, "hash-b", accessTime.plusMinutes(1)),
                message("abc1234", 1L, "hash-c", accessTime.plusMinutes(2)));

        int saved = statsService.recordAccess(messages);

        assertThat(saved).isEqualTo(3);
        // 关键：按 (code, date) 聚合后只 upsert 一次，PV 为批量总数
        verify(statsMapper, times(1)).upsertStats(anyLong(), eq("abc1234"), eq(1L),
                eq(LocalDate.of(2026, 2, 14)), eq(3L), eq(3L));
        verify(shortLinkMapper, times(1)).accumulateStats(eq(1L), eq(3L), eq(3L), any());
    }

    @Test
    @DisplayName("UV 只统计当天首次访问：重复 IP 不重复计数")
    void shouldCountUvOnlyOncePerIpPerDay() {
        // hash-a 首次插入成功；hash-b 已存在（返回 0）
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), eq("hash-a"))).thenReturn(1);
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), eq("hash-b"))).thenReturn(0);
        List<LinkAccessMessage> messages = List.of(
                message("abc1234", 1L, "hash-a", accessTime),
                message("abc1234", 1L, "hash-b", accessTime.plusMinutes(1)));

        statsService.recordAccess(messages);

        // PV=2（访问次数），UV=1（只有一个新访客）
        verify(statsMapper).upsertStats(anyLong(), eq("abc1234"), eq(1L),
                any(LocalDate.class), eq(2L), eq(1L));
    }

    @Test
    @DisplayName("缺少 ipHash 时不计 UV（宁少不错）")
    void shouldNotCountUvWithoutIpHash() {
        List<LinkAccessMessage> messages = List.of(message("abc1234", 1L, null, accessTime));

        statsService.recordAccess(messages);

        verify(uvLogMapper, never()).tryInsertUv(anyLong(), anyString(), any(), any());
        verify(statsMapper).upsertStats(anyLong(), eq("abc1234"), eq(1L),
                any(LocalDate.class), eq(1L), eq(0L));
    }

    @Test
    @DisplayName("不同短码 / 不同日期分别聚合")
    void shouldGroupByCodeAndDate() {
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), anyString())).thenReturn(1);
        List<LinkAccessMessage> messages = List.of(
                message("code001", 1L, "h1", accessTime),
                message("code002", 2L, "h2", accessTime),
                message("code001", 1L, "h3", accessTime.plusDays(1)));

        statsService.recordAccess(messages);

        verify(statsMapper, times(3)).upsertStats(anyLong(), anyString(), any(), any(LocalDate.class),
                anyLong(), anyLong());
    }

    @Test
    @DisplayName("明细日志冗余出 accessDate 与 hour，支撑按天按小时查询走索引")
    void shouldFillAccessDateAndHour() {
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), anyString())).thenReturn(1);

        statsService.recordAccess(List.of(message("abc1234", 1L, "hash-a", accessTime)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LinkAccessLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(accessLogBatchWriter).writeBatch(captor.capture());
        LinkAccessLog log = captor.getValue().get(0);
        assertThat(log.getAccessDate()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(log.getHour()).isEqualTo(10);
        assertThat(log.getShortCode()).isEqualTo("abc1234");
        assertThat(log.getId()).isNotNull();
    }

    @Test
    @DisplayName("超长 UA / Referer 被截断到列宽以内，避免插入失败")
    void shouldTruncateOverlongFields() {
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), anyString())).thenReturn(1);
        LinkAccessMessage message = message("abc1234", 1L, "hash-a", accessTime);
        message.setUserAgent("u".repeat(2000));
        message.setReferer("r".repeat(3000));

        statsService.recordAccess(List.of(message));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LinkAccessLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(accessLogBatchWriter).writeBatch(captor.capture());
        LinkAccessLog log = captor.getValue().get(0);
        assertThat(log.getUserAgent()).hasSize(512);
        assertThat(log.getReferer()).hasSize(1024);
    }

    @Test
    @DisplayName("shortCode 为空的消息被跳过，不参与聚合")
    void shouldSkipMalformedMessages() {
        LinkAccessMessage broken = new LinkAccessMessage();
        broken.setAccessTime(accessTime);

        statsService.recordAccess(List.of(broken));

        verify(statsMapper, never()).upsertStats(anyLong(), anyString(), any(), any(), anyLong(), anyLong());
        // 明细侧仍会调用写入器，但内容为空（被过滤掉了），写入器自身对空列表是空操作
        verify(accessLogBatchWriter).writeBatch(List.of());
    }

    @Test
    @DisplayName("linkId 缺失时仍写聚合表，但跳过冗余字段累加")
    void shouldSkipRedundantUpdateWhenLinkIdMissing() {
        when(uvLogMapper.tryInsertUv(anyLong(), anyString(), any(), anyString())).thenReturn(1);

        statsService.recordAccess(List.of(message("abc1234", null, "hash-a", accessTime)));

        verify(statsMapper).upsertStats(anyLong(), eq("abc1234"), eq(null), any(LocalDate.class),
                eq(1L), eq(1L));
        verify(shortLinkMapper, never()).accumulateStats(any(), anyLong(), anyLong(), any());
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Test
    @DisplayName("统计详情返回冗余字段与按天汇总，二者可交叉校验")
    void shouldReturnStatsDetail() {
        ShortLink link = new ShortLink();
        link.setId(1L);
        link.setShortCode("abc1234");
        link.setOriginalUrl("https://example.com");
        link.setStatus(1);
        link.setPv(100L);
        link.setUv(50L);
        link.setCreateTime(accessTime);
        when(shortLinkMapper.selectOne(any())).thenReturn(link);

        StatsSummaryVO summary = new StatsSummaryVO();
        summary.setPv(90L);
        summary.setUv(45L);
        when(statsMapper.sumByCode("abc1234")).thenReturn(summary);

        TrendPointVO point = new TrendPointVO();
        point.setStatDate("2026-02-14");
        point.setPv(10L);
        point.setUv(5L);
        when(statsMapper.selectTrend(eq("abc1234"), any(LocalDate.class))).thenReturn(List.of(point));

        LinkStatsVO vo = statsService.statsOf("abc1234", 7);

        assertThat(vo.getShortCode()).isEqualTo("abc1234");
        assertThat(vo.getTotalPv()).isEqualTo(100L);
        assertThat(vo.getTotalUv()).isEqualTo(50L);
        assertThat(vo.getStatsPvSum()).isEqualTo(90L);
        assertThat(vo.getStatsUvSum()).isEqualTo(45L);
        assertThat(vo.getTrend()).hasSize(1);
        assertThat(vo.getCreateTime()).isEqualTo("2026-02-14 10:30:00");
        // 7 天窗口：起止日期相差 6 天
        assertThat(vo.getEndDate().toEpochDay() - vo.getStartDate().toEpochDay()).isEqualTo(6);
    }

    @Test
    @DisplayName("趋势天数被限制在 1-90 之间")
    void shouldClampTrendDays() {
        ShortLink link = new ShortLink();
        link.setShortCode("abc1234");
        when(shortLinkMapper.selectOne(any())).thenReturn(link);

        LinkStatsVO tooMany = statsService.statsOf("abc1234", 999);
        assertThat(tooMany.getEndDate().toEpochDay() - tooMany.getStartDate().toEpochDay()).isEqualTo(89);

        LinkStatsVO tooFew = statsService.statsOf("abc1234", 0);
        assertThat(tooFew.getEndDate().toEpochDay() - tooFew.getStartDate().toEpochDay()).isEqualTo(6);
    }

    @Test
    @DisplayName("短码不存在时查询抛 404，不返回空对象")
    void shouldThrowWhenCodeMissing() {
        when(shortLinkMapper.selectOne(any())).thenReturn(null);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                com.shortlink.cloud.common.BizException.class,
                () -> statsService.statsOf("nope123", 7)).getErrorCode())
                .isEqualTo(com.shortlink.cloud.common.ErrorCode.LINK_NOT_FOUND);
    }

    @Test
    @DisplayName("汇总接口查询全部短码合计趋势")
    void shouldReturnGlobalTrend() {
        statsService.trendAll(30);
        verify(statsMapper).selectTrendAll(any(LocalDate.class));
    }

    private LinkAccessMessage message(String code, Long linkId, String ipHash, LocalDateTime time) {
        LinkAccessMessage message = new LinkAccessMessage();
        message.setShortCode(code);
        message.setLinkId(linkId);
        message.setOriginalUrl("https://example.com/target");
        message.setClientIp("1.2.3.4");
        message.setIpHash(ipHash);
        message.setUserAgent("curl/8.0");
        message.setAccessTime(time);
        return message;
    }
}
