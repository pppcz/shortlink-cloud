package com.shortlink.cloud.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.shortlink.cloud.common.BizException;
import com.shortlink.cloud.common.ErrorCode;
import com.shortlink.cloud.dto.LinkStatsVO;
import com.shortlink.cloud.dto.StatsSummaryVO;
import com.shortlink.cloud.dto.TrendPointVO;
import com.shortlink.cloud.entity.LinkAccessLog;
import com.shortlink.cloud.entity.ShortLink;
import com.shortlink.cloud.mapper.LinkStatsMapper;
import com.shortlink.cloud.mapper.LinkUvLogMapper;
import com.shortlink.cloud.mapper.ShortLinkMapper;
import com.shortlink.cloud.mq.AccessLogBatchWriter;
import com.shortlink.cloud.mq.LinkAccessMessage;
import com.shortlink.cloud.service.StatsService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计服务实现。
 *
 * <p>落库策略：
 * <ol>
 *   <li>明细日志用 MyBatis BATCH 执行器批量插入（一次网络往返写一整批）</li>
 *   <li>UV 逐条 INSERT IGNORE，靠唯一键判定「当天首次访问」</li>
 *   <li>PV/UV 按 (短码, 日期) 聚合后一次性 upsert，避免单批内重复累加</li>
 *   <li>同步累加 t_short_link 的冗余 pv/uv 与 last_access</li>
 * </ol>
 *
 * @author shortlink-cloud
 */
@Slf4j
@Service
public class StatsServiceImpl implements StatsService {

    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 趋势查询允许的最大天数，防止一次拉取过多数据。 */
    private static final int MAX_TREND_DAYS = 90;

    private final LinkStatsMapper statsMapper;
    private final LinkUvLogMapper uvLogMapper;
    private final ShortLinkMapper shortLinkMapper;
    private final AccessLogBatchWriter accessLogBatchWriter;

    public StatsServiceImpl(LinkStatsMapper statsMapper,
                            LinkUvLogMapper uvLogMapper,
                            ShortLinkMapper shortLinkMapper,
                            AccessLogBatchWriter accessLogBatchWriter) {
        this.statsMapper = statsMapper;
        this.uvLogMapper = uvLogMapper;
        this.shortLinkMapper = shortLinkMapper;
        this.accessLogBatchWriter = accessLogBatchWriter;
    }

    @Override
    public int recordAccess(List<LinkAccessMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        // 1. 明细日志批量写入
        List<LinkAccessLog> logs = buildLogs(messages);
        accessLogBatchWriter.writeBatch(logs);

        // 2. 按 (短码, 日期) 聚合：PV 记总数，UV 只统计「当天首次访问」
        Map<CodeDate, Accumulator> accumulators = aggregate(messages);

        // 3. 落聚合表 + 累加短链冗余字段
        accumulators.forEach(this::flushAccumulator);

        log.debug("访问日志落库完成 logs={} groups={}", logs.size(), accumulators.size());
        return messages.size();
    }

    @Override
    public LinkStatsVO statsOf(String shortCode, int days) {
        if (StringUtils.isBlank(shortCode)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "短码不能为空");
        }
        int window = normalizeDays(days);
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(window - 1L);

        ShortLink link = shortLinkMapper.selectOne(
                Wrappers.<ShortLink>lambdaQuery().eq(ShortLink::getShortCode, shortCode));
        if (link == null) {
            throw new BizException(ErrorCode.LINK_NOT_FOUND);
        }

        StatsSummaryVO summary = statsMapper.sumByCode(shortCode);
        List<TrendPointVO> trend = statsMapper.selectTrend(shortCode, startDate);

        LinkStatsVO vo = new LinkStatsVO();
        vo.setShortCode(shortCode);
        vo.setOriginalUrl(link.getOriginalUrl());
        vo.setTitle(link.getTitle());
        vo.setStatus(link.getStatus());
        vo.setCreateTime(format(link.getCreateTime()));
        vo.setExpireTime(format(link.getExpireTime()));
        vo.setLastAccess(format(link.getLastAccess()));
        vo.setTotalPv(link.getPv() == null ? 0L : link.getPv());
        vo.setTotalUv(link.getUv() == null ? 0L : link.getUv());
        vo.setStatsPvSum(summary == null ? 0L : summary.safePv());
        vo.setStatsUvSum(summary == null ? 0L : summary.safeUv());
        vo.setStartDate(startDate);
        vo.setEndDate(endDate);
        vo.setTrend(trend);
        return vo;
    }

    @Override
    public List<TrendPointVO> trendAll(int days) {
        int window = normalizeDays(days);
        return statsMapper.selectTrendAll(LocalDate.now().minusDays(window - 1L));
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /** 明细日志实体转换：时间列冗余出 accessDate / hour，让报表查询能走索引。 */
    private List<LinkAccessLog> buildLogs(List<LinkAccessMessage> messages) {
        List<LinkAccessLog> logs = new ArrayList<>(messages.size());
        for (LinkAccessMessage message : messages) {
            if (message == null || StringUtils.isBlank(message.getShortCode())) {
                continue;
            }
            LocalDateTime accessTime = message.getAccessTime() == null
                    ? LocalDateTime.now()
                    : message.getAccessTime();

            LinkAccessLog log = new LinkAccessLog();
            log.setId(IdWorker.getId());
            log.setShortCode(message.getShortCode());
            log.setLinkId(message.getLinkId());
            log.setOriginalUrl(truncate(message.getOriginalUrl(), 2048));
            log.setClientIp(truncate(message.getClientIp(), 64));
            log.setIpHash(message.getIpHash());
            log.setUserAgent(truncate(message.getUserAgent(), 512));
            log.setReferer(truncate(message.getReferer(), 1024));
            log.setAccessTime(accessTime);
            log.setAccessDate(accessTime.toLocalDate());
            log.setHour(accessTime.getHour());
            logs.add(log);
        }
        return logs;
    }

    /**
     * 按 (短码, 日期) 聚合。
     *
     * <p>UV 在这里逐条尝试 INSERT IGNORE：只有真正插入成功（返回 1）才算当天首次访问，
     * 从而把 UV 的准确性交给数据库唯一键保证，而不是依赖应用侧的去重。
     */
    private Map<CodeDate, Accumulator> aggregate(List<LinkAccessMessage> messages) {
        Map<CodeDate, Accumulator> accumulators = new HashMap<>();
        for (LinkAccessMessage message : messages) {
            if (message == null || StringUtils.isBlank(message.getShortCode())) {
                continue;
            }
            LocalDate date = message.getAccessTime() == null
                    ? LocalDate.now()
                    : message.getAccessTime().toLocalDate();
            CodeDate key = new CodeDate(message.getShortCode(), date);

            Accumulator acc = accumulators.computeIfAbsent(key,
                    k -> new Accumulator(message.getLinkId()));
            acc.pv++;
            // 没有 ipHash 时无法去重，保守地不计 UV（宁少不错）
            if (StringUtils.isNotBlank(message.getIpHash())
                    && uvLogMapper.tryInsertUv(IdWorker.getId(), key.shortCode(), key.date(),
                    message.getIpHash()) == 1) {
                acc.uv++;
            }
        }
        return accumulators;
    }

    private void flushAccumulator(CodeDate key, Accumulator acc) {
        statsMapper.upsertStats(IdWorker.getId(), key.shortCode(), acc.linkId, key.date(), acc.pv, acc.uv);
        // linkId 缺失（历史消息或异常数据）时跳过冗余字段累加，不影响聚合表
        if (acc.linkId != null) {
            shortLinkMapper.accumulateStats(acc.linkId, acc.pv, acc.uv, LocalDateTime.now());
        }
    }

    private int normalizeDays(int days) {
        if (days <= 0) {
            return 7;
        }
        return Math.min(days, MAX_TREND_DAYS);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(DATE_TIME_FORMAT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 聚合键：(短码, 日期)。 */
    private record CodeDate(String shortCode, LocalDate date) {
    }

    /** 单组累加器。 */
    private static final class Accumulator {
        private final Long linkId;
        private long pv;
        private long uv;

        private Accumulator(Long linkId) {
            this.linkId = linkId;
        }
    }
}
