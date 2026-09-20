package com.shortlink.cloud.service.impl;

import com.shortlink.cloud.config.RetentionProperties;
import com.shortlink.cloud.mapper.LinkAccessLogMapper;
import com.shortlink.cloud.mapper.LinkUvLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataRetentionServiceImpl} 单元测试。
 *
 * <p>覆盖分批删除的循环控制逻辑——这部分是纯算法，不需要容器。
 * 真实的 {@code DELETE ... LIMIT} 语法由
 * {@code DataRetentionIntegrationTest} 在真实 MySQL 上验证。
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRetentionServiceImplTest {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES = 5;

    @Mock
    private LinkAccessLogMapper accessLogMapper;

    @Mock
    private LinkUvLogMapper uvLogMapper;

    private RetentionProperties properties;
    private DataRetentionServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new RetentionProperties();
        properties.setBatchSize(BATCH_SIZE);
        properties.setMaxBatchesPerRun(MAX_BATCHES);
        properties.setAccessLogDays(90);
        properties.setUvLogDays(180);
        service = new DataRetentionServiceImpl(accessLogMapper, uvLogMapper, properties);
    }

    @Test
    @DisplayName("删满一批后继续下一批，直到不足一批即停止")
    void shouldLoopUntilPartialBatch() {
        // 前两批各返回满额，第三批返回不足（说明删完了）
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE, BATCH_SIZE, 40);

        long total = service.purgeAccessLogs();

        assertThat(total).isEqualTo(BATCH_SIZE * 2L + 40L);
        // 第三批不足额后立即停止，不应有第四次调用
        verify(accessLogMapper, times(3)).deleteBeforeDate(any(LocalDate.class), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("没有数据时只调用一次就返回 0")
    void shouldStopImmediatelyWhenNothingToDelete() {
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), anyInt())).thenReturn(0);

        long total = service.purgeAccessLogs();

        assertThat(total).isZero();
        verify(accessLogMapper, times(1)).deleteBeforeDate(any(LocalDate.class), anyInt());
    }

    @Test
    @DisplayName("持续满额时受单次最大批次上限约束，不会无限循环")
    void shouldRespectMaxBatchesLimit() {
        // 永远返回满额，模拟积压极多的情况
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), eq(BATCH_SIZE)))
                .thenReturn(BATCH_SIZE);

        long total = service.purgeAccessLogs();

        assertThat(total).isEqualTo((long) BATCH_SIZE * MAX_BATCHES);
        verify(accessLogMapper, times(MAX_BATCHES)).deleteBeforeDate(any(LocalDate.class), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("删除截止日期 = 今天减去保留天数")
    void shouldUseConfiguredRetentionWindow() {
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), anyInt())).thenReturn(0);

        service.purgeAccessLogs();

        LocalDate expected = LocalDate.now().minusDays(properties.getAccessLogDays());
        verify(accessLogMapper).deleteBeforeDate(eq(expected), anyInt());
    }

    @Test
    @DisplayName("UV 清理使用独立的保留天数")
    void shouldUseSeparateWindowForUvLogs() {
        when(uvLogMapper.deleteBeforeDate(any(LocalDate.class), anyInt())).thenReturn(0);

        service.purgeUvLogs();

        LocalDate expected = LocalDate.now().minusDays(properties.getUvLogDays());
        verify(uvLogMapper).deleteBeforeDate(eq(expected), anyInt());
        // 不应误删访问明细
        verify(accessLogMapper, times(0)).deleteBeforeDate(any(LocalDate.class), anyInt());
    }

    @Test
    @DisplayName("清理任务关闭时不执行任何删除")
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);

        service.scheduledPurge();

        verify(accessLogMapper, times(0)).deleteBeforeDate(any(LocalDate.class), anyInt());
        verify(uvLogMapper, times(0)).deleteBeforeDate(any(LocalDate.class), anyInt());
    }

    @Test
    @DisplayName("清理异常被吞掉，不能让调度线程带着异常退出")
    void shouldSwallowExceptionInScheduledRun() {
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        // 不抛异常即通过
        service.scheduledPurge();
    }

    @Test
    @DisplayName("放弃跨天的临界场景：保留 0 天时截止日期就是今天")
    void shouldHandleZeroRetentionDays() {
        properties.setAccessLogDays(0);
        when(accessLogMapper.deleteBeforeDate(any(LocalDate.class), anyInt())).thenReturn(0);

        service.purgeAccessLogs();

        verify(accessLogMapper).deleteBeforeDate(eq(LocalDate.now()), anyInt());
    }
}
