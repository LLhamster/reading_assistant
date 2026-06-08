package com.example.httpreading.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
    代码配置rabbitmq的方式就是，先配置EXCHANGE_NAME、QUEUE_NAME、ROUTING_KEY的关系，以及
    RabbitTemplate的发送方式，然后在其他的controller调用RabbitTemplate的convertAndSend来
    发送用户ID以及行为（这个时生产者），然后又规定了消费者接收到发送到队列中的mseeage信息的处
    理方式，例如通过id寻找书籍内容以及章节内容，然后保存在es中，而底层的队列以及线程分配都是已
    经在底层写好了的。
*/


@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "book.index.exchange";
    public static final String QUEUE_NAME = "book.index.queue";
    public static final String ROUTING_KEY = "book.index";

    /** 交换机 */
    @Bean
    public DirectExchange bookIndexExchange() {
        return new DirectExchange(EXCHANGE_NAME);
    }

    /** 队列 */
    @Bean
    public Queue bookIndexQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    /** 绑定：队列 + 交换机 + 路由键 */
    @Bean
    public Binding bookIndexBinding(Queue bookIndexQueue, DirectExchange bookIndexExchange) {
        return BindingBuilder.bind(bookIndexQueue).to(bookIndexExchange).with(ROUTING_KEY);
    }

    /** JSON 序列化方式（发消息时自动把对象转 JSON） */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /** RabbitTemplate 注入 messageConverter */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }
}
