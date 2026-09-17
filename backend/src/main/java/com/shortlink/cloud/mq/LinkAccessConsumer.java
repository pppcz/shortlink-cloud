package com.shortlink.cloud.mq;

import com.shortlink.cloud.service.StatsService;
import com.rabbitmq.client.Channel;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问日志消费者：内存攒批 + 定时/定量刷盘 + 成功后统一 ack。
 *
 * <p>为什么不逐条落库：跳转峰值下逐条 INSERT 会把数据库打满，
 * 攒批后一次批量提交能把写放大降低一到两个数量级。
 *
 * <p>为什么「刷盘成功才 ack」：先 ack 再写库，一旦写库失败消息就永久丢了。
 * 反过来只要不 ack，消费失败会重投；代价是可能重复消费
 * （UV 靠唯一键去重，PV 按 (短码, 日期) 聚合后累加，重投不会重复计数）。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
public class LinkAccessConsumer {

    /** 单个批次最多积攒的消息数。 */
    private static final int BATCH_SIZE = 500;
    /** 定时刷盘间隔（毫秒）。 */
    private static final long FLUSH_INTERVAL_MS = 2000L;

    private final StatsService statsService;

    /** 待落库缓冲。监听线程写入、调度线程刷盘，需要同步保护。 */
    private final List<PendingMessage> buffer = new ArrayList<>(BATCH_SIZE);

    public LinkAccessConsumer(StatsService statsService) {
        this.statsService = statsService;
    }

    /**
     * 接收一条访问日志。
     *
     * @param body        消息体（已由 Jackson 转换器反序列化）
     * @param channel     AMQP channel，用于手动 ack
     * @param deliveryTag 投递标签
     * @param amqpMessage 原始消息（用于读取重投标记）
     */
    @RabbitListener(queues = MqConstants.ACCESS_QUEUE)
    public void onMessage(LinkAccessMessage body,
                          @Header(AmqpHeaders.CHANNEL) Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          Message amqpMessage) {
        if (body == null || body.getShortCode() == null) {
            // 无法处理的消息直接 ack 丢弃，避免毒消息把队列堵死
            safeAck(channel, deliveryTag);
            return;
        }
        if (isRedelivered(amqpMessage)) {
            log.warn("检测到重投消息 code={} deliveryTag={}", body.getShortCode(), deliveryTag);
        }

        boolean shouldFlush;
        synchronized (buffer) {
            buffer.add(new PendingMessage(body, deliveryTag, channel));
            shouldFlush = buffer.size() >= BATCH_SIZE;
        }
        if (shouldFlush) {
            // 达到批量阈值立刻刷盘，避免积压无限增长
            flush();
        }
    }

    /**
     * 定时刷盘。
     *
     * <p>用 {@code fixedDelay} 而非 {@code fixedRate}：上一次刷盘没结束时
     * 不要叠加新的调度，避免线程堆叠。
     */
    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flushOnSchedule() {
        flush();
    }

    /** 优雅关闭：把缓冲里剩余的消息落库，避免停机丢统计。 */
    @PreDestroy
    public void flushOnShutdown() {
        int pending;
        synchronized (buffer) {
            pending = buffer.size();
        }
        if (pending > 0) {
            log.info("应用关闭，刷盘剩余访问日志 bufferSize={}", pending);
        }
        flush();
    }

    /**
     * 刷盘：把缓冲中的消息交给 {@link StatsService} 落库，全部成功后再 ack。
     */
    public void flush() {
        List<PendingMessage> batch;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(buffer);
            buffer.clear();
        }

        List<LinkAccessMessage> messages = batch.stream().map(PendingMessage::body).toList();
        try {
            int saved = statsService.recordAccess(messages);
            // 落库成功才 ack：保证「已确认的消息一定已持久化」
            batch.forEach(pending -> safeAck(pending.channel(), pending.deliveryTag()));
            log.debug("访问日志刷盘成功 size={} saved={}", batch.size(), saved);
        } catch (Exception ex) {
            log.error("访问日志刷盘失败 size={}，将 requeue 重投", batch.size(), ex);
            batch.forEach(pending -> safeNack(pending.channel(), pending.deliveryTag()));
        }
    }

    /** 当前缓冲区大小，供健康检查 / 测试观测。 */
    public int bufferSize() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    private void safeAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.warn("ack 失败 deliveryTag={} err={}", deliveryTag, ex.getMessage());
        }
    }

    private void safeNack(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.warn("nack 失败 deliveryTag={} err={}", deliveryTag, ex.getMessage());
        }
    }

    private boolean isRedelivered(Message message) {
        return message != null && message.getMessageProperties().isRedelivered();
    }

    /**
     * 缓冲中的一条待确认消息（需同时持有 channel 才能 ack）。
     *
     * @param body        消息体
     * @param deliveryTag 投递标签
     * @param channel     AMQP channel
     */
    private record PendingMessage(LinkAccessMessage body, long deliveryTag, Channel channel) {
    }
}
