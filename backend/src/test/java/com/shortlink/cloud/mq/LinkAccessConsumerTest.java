package com.shortlink.cloud.mq;

import com.shortlink.cloud.service.StatsService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LinkAccessConsumer} 单元测试。
 *
 * <p>重点验证「刷盘成功才 ack」这条不变式——它是统计不丢数的核心保证。
 *
 * @author shortlink-cloud
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LinkAccessConsumerTest {

    @Mock
    private StatsService statsService;

    @Mock
    private Channel channel;

    private LinkAccessConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new LinkAccessConsumer(statsService);
    }

    @Test
    @DisplayName("收到消息先入缓冲，不立即落库")
    void shouldBufferWithoutImmediateFlush() {
        consumer.onMessage(message("abc1234"), channel, 1L, new Message(new byte[0], new MessageProperties()));

        assertThat(consumer.bufferSize()).isEqualTo(1);
        verify(statsService, never()).recordAccess(any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("定时刷盘后落库成功才 ack")
    void shouldAckAfterSuccessfulFlush() throws IOException {
        when(statsService.recordAccess(any())).thenReturn(1);
        consumer.onMessage(message("abc1234"), channel, 7L, new Message(new byte[0], new MessageProperties()));

        consumer.flush();

        assertThat(consumer.bufferSize()).isZero();
        verify(statsService).recordAccess(any());
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("落库失败时 nack 并 requeue，绝不 ack（否则统计会永久丢数）")
    void shouldNackWhenFlushFails() throws IOException {
        when(statsService.recordAccess(any())).thenThrow(new RuntimeException("db down"));
        consumer.onMessage(message("abc1234"), channel, 9L, new Message(new byte[0], new MessageProperties()));

        consumer.flush();

        verify(channel).basicNack(9L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("批量落库时传入完整批次")
    void shouldPassWholeBatchToStatsService() {
        when(statsService.recordAccess(any())).thenReturn(3);
        Message amqp = new Message(new byte[0], new MessageProperties());
        consumer.onMessage(message("code001"), channel, 1L, amqp);
        consumer.onMessage(message("code002"), channel, 2L, amqp);
        consumer.onMessage(message("code003"), channel, 3L, amqp);

        consumer.flush();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<LinkAccessMessage>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(statsService).recordAccess(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        verify(channel, times(3)).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("空缓冲刷盘是空操作")
    void shouldNoOpOnEmptyBuffer() {
        consumer.flush();

        verify(statsService, never()).recordAccess(any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("消息缺少短码时直接 ack 丢弃，避免毒消息堵队列")
    void shouldDiscardMalformedMessage() throws IOException {
        LinkAccessMessage broken = new LinkAccessMessage();

        consumer.onMessage(broken, channel, 5L, new Message(new byte[0], new MessageProperties()));

        verify(channel).basicAck(5L, false);
        assertThat(consumer.bufferSize()).isZero();
    }

    @Test
    @DisplayName("ack 本身失败不应向外抛异常")
    void shouldSwallowAckFailure() throws IOException {
        when(statsService.recordAccess(any())).thenReturn(1);
        doThrow(new IOException("channel closed")).when(channel).basicAck(anyLong(), anyBoolean());
        consumer.onMessage(message("abc1234"), channel, 1L, new Message(new byte[0], new MessageProperties()));

        // 不抛异常即通过
        consumer.flush();
        verify(channel).basicAck(1L, false);
    }

    private LinkAccessMessage message(String code) {
        LinkAccessMessage message = new LinkAccessMessage();
        message.setShortCode(code);
        message.setLinkId(1L);
        message.setIpHash("hash");
        return message;
    }
}
