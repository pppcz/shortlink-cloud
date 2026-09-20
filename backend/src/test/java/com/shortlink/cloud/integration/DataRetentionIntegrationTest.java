package com.shortlink.cloud.integration;

import com.shortlink.cloud.config.RetentionProperties;
import com.shortlink.cloud.service.DataRetentionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据保留与清理的集成测试。
 *
 * <p>为什么单独写：清理用的是手写 {@code DELETE ... LIMIT}，
 * <b>{@code DELETE} 带 {@code LIMIT} 是 MySQL 方言</b>，用 H2 或 mock 都验不出来。
 * 一旦语法错误，线上表现是"每天凌晨清理任务静默失败"——
 * 而失败的表现只是表继续变大，很难被及时发现。
 *
 * @author shortlink-cloud
 */
@Tag("integration")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DataRetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataRetentionService dataRetentionService;

    @Autowired
    private RetentionProperties retentionProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.execute("DELETE FROM t_link_uv_log");
        jdbcTemplate.execute("DELETE FROM t_link_access_log");
    }

    @Test
    @DisplayName("访问明细清理：只删保留窗口之前的数据，窗口内必须保留")
    void shouldPurgeAccessLogsOutsideRetentionWindow() {
        LocalDate today = LocalDate.now();
        int keepDays = retentionProperties.getAccessLogDays();

        // 窗口内（应保留）
        insertAccessLog(today);
        insertAccessLog(today.minusDays(1));
        // 窗口外（应删除）
        insertAccessLog(today.minusDays(keepDays + 1));
        insertAccessLog(today.minusDays(keepDays + 30));

        long deleted = dataRetentionService.purgeAccessLogs();

        assertThat(deleted).as("应删除 2 条窗口外记录").isEqualTo(2L);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_link_access_log", Integer.class);
        assertThat(remaining).as("窗口内的 2 条必须保留").isEqualTo(2);
    }

    @Test
    @DisplayName("UV 记录清理：同样只删保留窗口之前的数据")
    void shouldPurgeUvLogsOutsideRetentionWindow() {
        LocalDate today = LocalDate.now();
        int keepDays = retentionProperties.getUvLogDays();

        insertUvLog(today, "hash-keep-1");
        insertUvLog(today.minusDays(keepDays + 1), "hash-drop-1");
        insertUvLog(today.minusDays(keepDays + 5), "hash-drop-2");

        long deleted = dataRetentionService.purgeUvLogs();

        assertThat(deleted).isEqualTo(2L);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_link_uv_log", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    @DisplayName("没有过期数据时清理是无害的空操作")
    void shouldBeNoOpWhenNothingToPurge() {
        insertAccessLog(LocalDate.now());

        assertThat(dataRetentionService.purgeAccessLogs()).isZero();
        assertThat(dataRetentionService.purgeUvLogs()).isZero();
    }

    @Test
    @DisplayName("清理按批次上限执行，不会一次删光造成长事务")
    void shouldRespectBatchSizeLimit() {
        LocalDate old = LocalDate.now().minusDays(retentionProperties.getAccessLogDays() + 10);

        // 造出超过「单批 × 单次最大批次」的数据量
        int batchSize = retentionProperties.getBatchSize();
        int maxBatches = retentionProperties.getMaxBatchesPerRun();
        int total = batchSize * maxBatches + 50;
        for (int i = 0; i < total; i++) {
            insertAccessLog(old);
        }

        long deleted = dataRetentionService.purgeAccessLogs();

        // 单次调用最多删 batchSize * maxBatches 行
        assertThat(deleted)
                .as("单次清理应受批次上限约束")
                .isEqualTo((long) batchSize * maxBatches);

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_link_access_log", Integer.class);
        assertThat(remaining)
                .as("超出部分应留到下次调度继续清理")
                .isEqualTo(50);
    }

    // ------------------------------------------------------------------

    private void insertAccessLog(LocalDate accessDate) {
        jdbcTemplate.update("""
                INSERT INTO t_link_access_log
                    (id, short_code, link_id, original_url, client_ip, ip_hash,
                     user_agent, access_time, access_date, hour)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                System.nanoTime(),
                "testcode",
                1L,
                "https://example.com",
                "1.2.3.4",
                "hash-" + System.nanoTime(),
                "curl/8.0",
                accessDate.atTime(10, 0),
                java.sql.Date.valueOf(accessDate),
                10);
    }

    private void insertUvLog(LocalDate statDate, String ipHash) {
        jdbcTemplate.update("""
                INSERT INTO t_link_uv_log (id, short_code, stat_date, ip_hash)
                VALUES (?, ?, ?, ?)
                """,
                System.nanoTime(),
                "testcode",
                java.sql.Date.valueOf(statDate),
                ipHash);
    }
}
