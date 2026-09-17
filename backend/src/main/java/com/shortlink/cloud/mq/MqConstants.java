package com.shortlink.cloud.mq;

/**
 * RabbitMQ 队列 / 交换机 / 路由键常量。
 *
 * @author shortlink-cloud
 */
public final class MqConstants {

    private MqConstants() {
    }

    /** 访问日志交换机（direct）。 */
    public static final String ACCESS_EXCHANGE = "shortlink.access.exchange";
    /** 访问日志队列。 */
    public static final String ACCESS_QUEUE = "shortlink.access.queue";
    /** 访问日志路由键。 */
    public static final String ACCESS_ROUTING_KEY = "link.access";

    /** 死信交换机。 */
    public static final String ACCESS_DLX_EXCHANGE = "shortlink.access.dlx.exchange";
    /** 死信队列。 */
    public static final String ACCESS_DLX_QUEUE = "shortlink.access.dlx.queue";
    /** 死信路由键。 */
    public static final String ACCESS_DLX_ROUTING_KEY = "link.access.dlq";

    /** 消费失败后的最大重试次数，超过后进入死信队列。 */
    public static final int MAX_RETRY = 3;
}
