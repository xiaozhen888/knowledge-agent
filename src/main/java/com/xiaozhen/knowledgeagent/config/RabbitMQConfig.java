package com.xiaozhen.knowledgeagent.config;

import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.amqp.core.*;

@Configuration
public class RabbitMQConfig {

    public static final String QUEUE_DOCUMENT = "document.process.queue";
    public static final String EXCHANGE_DOCUMENT = "document.process.exchange";
    public static final String ROUTING_KEY = "document.process";

    @Bean
    public Queue documentQueue() {
        return QueueBuilder.durable(QUEUE_DOCUMENT).build();
    }

    @Bean
    public DirectExchange documentExchange() {
        return new DirectExchange(EXCHANGE_DOCUMENT);
    }

    @Bean
    public Binding binding(Queue documentQueue, DirectExchange documentExchange) {
        return BindingBuilder.bind(documentQueue).to(documentExchange).with(ROUTING_KEY);
    }

    // 加上这个：使用JSON序列化，而不是Java序列化
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}