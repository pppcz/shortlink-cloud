package com.shortlink.cloud.config;

import com.shortlink.cloud.mq.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑与序列化配置。
 *
 * <p>拓扑：
 * <pre>
 *   shortlink.access.exchange (direct)
 *        └─ routing key "link.access" → shortlink.access.queue
 *             消费失败 3 次 → shortlink.access.dlx.exchange
 *                              └─ "link.access.dlq" → shortlink.access.dlx.queue
 * </pre>
 *
 * <p>业务队列绑定死信交换机：即使消费逻辑持续失败，消息也不会被静默丢弃，
 * 而是沉到死信队列里可人工排查与重放。
 *
 * @author shortlink-cloud
 */
@Configuration
public class RabbitMqConfig {

    // ------------------------------------------------------------------
    // 业务队列
    // ------------------------------------------------------------------

    @Bean
    public DirectExchange accessExchange() {
        return new DirectExchange(MqConstants.ACCESS_EXCHANGE, true, false);
    }

    @Bean
    public Queue accessQueue() {
        return QueueBuilder.durable(MqConstants.ACCESS_QUEUE)
                .withArgument("x-dead-letter-exchange", MqConstants.ACCESS_DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.ACCESS_DLX_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding accessBinding(Queue accessQueue, DirectExchange accessExchange) {
        return BindingBuilder.bind(accessQueue)
                .to(accessExchange)
                .with(MqConstants.ACCESS_ROUTING_KEY);
    }

    // ------------------------------------------------------------------
    // 死信队列
    // ------------------------------------------------------------------

    @Bean
    public DirectExchange accessDlxExchange() {
        return new DirectExchange(MqConstants.ACCESS_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue accessDlxQueue() {
        return QueueBuilder.durable(MqConstants.ACCESS_DLX_QUEUE).build();
    }

    @Bean
    public Binding accessDlxBinding(Queue accessDlxQueue, DirectExchange accessDlxExchange) {
        return BindingBuilder.bind(accessDlxQueue)
                .to(accessDlxExchange)
                .with(MqConstants.ACCESS_DLX_ROUTING_KEY);
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 使用 JSON 而非 JDK 序列化。
     *
     * <p>理由：消息在管理台可读、跨语言兼容，且避免 Java 反序列化安全风险。
     *
     * <p>显式声明受信任包：消费侧反序列化时若类不在白名单内会直接抛异常，
     * 这是防反序列化攻击的关键设置，不能依赖默认值。
     *
     * @return 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.shortlink.cloud.mq", "java.util", "java.time");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
